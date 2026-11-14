package com.videoeditor.app.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.videoeditor.app.App
import com.videoeditor.app.data.LibraryItemEntity
import com.videoeditor.app.data.LibraryKind
import com.videoeditor.app.data.clipFrom
import com.videoeditor.app.data.newClipId
import com.videoeditor.app.media.AutoCutEngine
import com.videoeditor.app.media.AutoCutOutcome
import com.videoeditor.app.media.CanvasFormat
import com.videoeditor.app.media.Exporter
import com.videoeditor.app.media.LiveFrames
import com.videoeditor.app.media.MediaProbe
import com.videoeditor.app.media.PlaybackController
import com.videoeditor.app.media.canvasSize
import com.videoeditor.core.AutoCutStrength
import com.videoeditor.core.Clip
import com.videoeditor.app.captions.CaptionModel
import com.videoeditor.app.captions.CaptionModels
import com.videoeditor.app.captions.CaptionLanguage
import com.videoeditor.app.captions.AutoCaptioner
import com.videoeditor.core.Captions
import com.videoeditor.core.Compound
import com.videoeditor.core.MediaKind
import com.videoeditor.core.Snap
import com.videoeditor.core.Timeline
import com.videoeditor.core.TimelineOps
import com.videoeditor.core.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

data class Busy(val label: String, val progress: Float? = null)

/**
 * Timeline Editor state for one open project. Every edit produces a new immutable [Timeline]
 * (see TimelineOps), pushes it to the preview player and saves it to Room, so closing the app at
 * any moment keeps the project.
 */
class EditorController(private val app: App, val projectId: Long, uiScope: CoroutineScope) {
    /**
     * The screen's scope, pinned to the main thread. ExoPlayer and Compose state must only be touched
     * from the main thread; a plain composition scope does not promise which thread a coroutine
     * resumes on after a Room call (seen on the emulator: "Player is accessed on the wrong thread").
     */
    private val scope = CoroutineScope(uiScope.coroutineContext + Dispatchers.Main.immediate)
    val player = PlaybackController(app, scope)

    var timeline by mutableStateOf(Timeline())
        private set
    var projectName by mutableStateOf("")
        private set
    var loaded by mutableStateOf(false)
        private set
    var busy by mutableStateOf<Busy?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var library by mutableStateOf<LibraryKind?>(null)

    private val saveLock = Mutex()
    private var pendingSave: Job? = null

    // Output format (16:9, 9:16, 1:1 or the original shape). Stored per project in app preferences.
    private val settings = app.getSharedPreferences("project_settings", android.content.Context.MODE_PRIVATE)
    var format by mutableStateOf(
        try {
            CanvasFormat.valueOf(settings.getString("format_$projectId", null) ?: CanvasFormat.ORIGINAL.name)
        } catch (e: Exception) {
            CanvasFormat.ORIGINAL
        },
    )
        private set

    fun chooseFormat(value: CanvasFormat) {
        format = value
        settings.edit().putString("format_$projectId", value.name).apply()
    }

    private val targetAspect: Float
        get() = format.ratio ?: timeline.main.firstOrNull()?.aspect ?: (16f / 9f)

    /** Width / height of the output frame; the videos are fitted inside it. */
    val canvasAspect: Float
        get() {
            val (w, h) = canvasSize(targetAspect)
            return w.toFloat() / h
        }

    private val position: Long get() = player.positionMs.value

    private var flatKey: Pair<Timeline, Float>? = null
    private var flatValue: Timeline = Timeline()

    /** The timeline as it plays: compound clips replaced by the pieces inside them. */
    val flat: Timeline
        get() {
            val key = timeline to canvasAspect
            if (key != flatKey) {
                flatValue = Compound.flatten(timeline, canvasAspect)
                flatKey = key
            }
            return flatValue
        }

    init {
        current = this
        scope.launch {
            projectName = app.projects.get(projectId)?.name ?: ""
            timeline = TimelineOps.normalize(app.projects.load(projectId))
            player.setTimeline(timeline)
            loaded = true
        }
    }

    companion object {
        /**
         * Copied clip (Copy button). Kept for as long as the app runs, so a clip can be copied in
         * one project and pasted into another.
         */
        var clipboard by mutableStateOf<Clip?>(null)

        /** The open editor (used by the on-device tests to look at the state). */
        @Volatile
        var current: EditorController? = null
            private set
    }

    // ---- Undo / redo ---------------------------------------------------------------------
    private val undoStack = ArrayDeque<Timeline>()
    private val redoStack = ArrayDeque<Timeline>()
    private var dragOpen = false
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private fun commitEdit(t: Timeline, saveNow: Boolean = true, undoable: Boolean = true) {
        if (undoable && t != timeline) {
            // a whole drag (many small changes) counts as one undo step
            if (saveNow || !dragOpen) {
                undoStack.addLast(timeline)
                if (undoStack.size > 50) undoStack.removeFirst()
            }
            dragOpen = !saveNow
            redoStack.clear()
            canUndo = true
            canRedo = false
        }
        timeline = t
        player.setTimeline(t, interactive = !saveNow)
        if (saveNow) save() else saveSoon()
    }

    private fun restore(t: Timeline) {
        dragOpen = false
        timeline = t
        player.setTimeline(t)
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
        save()
    }

    fun undo() {
        if (busy != null || undoStack.isEmpty()) return
        multiPick = null
        redoStack.addLast(timeline)
        restore(undoStack.removeLast())
    }

    fun redo() {
        if (busy != null || redoStack.isEmpty()) return
        multiPick = null
        undoStack.addLast(timeline)
        restore(redoStack.removeLast())
    }

    /** Saves the latest timeline. Runs on the app scope so it finishes even if the screen closes. */
    fun save() {
        pendingSave?.cancel()
        pendingSave = null
        // Never save before the project has been loaded: that would overwrite it with nothing.
        if (!loaded) return
        app.appScope.launch {
            saveLock.withLock { app.projects.save(projectId, timeline) }
            app.library.cleanUpRemoved()
        }
    }

    /** Used while dragging: saves shortly after the last change. */
    private fun saveSoon() {
        pendingSave?.cancel()
        pendingSave = scope.launch {
            delay(400)
            dragOpen = false
            save()
        }
    }

    fun select(id: String?) {
        // while picking, a tap ticks / unticks a clip (any track) instead
        if (multiPick != null) {
            if (id != null) {
                val owner = Compound.ownerId(id)
                if (timeline.byId(owner) != null) togglePick(owner)
            }
            return
        }
        val target = if (id != null && timeline.selected?.id == id) null else id
        if (target == null && timeline.selected == null) return
        commitEdit(TimelineOps.select(timeline, target), undoable = false)
    }

    // ---- Step 3: Split -------------------------------------------------------------------

    fun split() {
        val clip = timeline.selected ?: return
        val result = TimelineOps.split(timeline, clip.id, position, ::newClipId)
        if (result.changed) commitEdit(result.timeline) else message = result.reason
    }

    // ---- Step 4: AutoCut -----------------------------------------------------------------

    /** AutoCut result waiting for the user to choose a strength and apply it (review sheet). */
    var autoCutReview by mutableStateOf<AutoCutOutcome.Ready?>(null)
        private set
    var autoCutStrength by mutableStateOf(AutoCutStrength.NORMAL)

    fun autoCut() {
        val clip = timeline.selected ?: return
        if (clip.isCompound) {
            message = "AutoCut works on single video clips. Tap Uncompound first, then AutoCut the clip you want."
            return
        }
        if (clip.track != Track.MAIN || clip.kind != MediaKind.VIDEO) {
            message = "AutoCut works on video clips."
            return
        }
        if (busy != null) return
        player.pause()
        busy = Busy("Finding the parts where nobody is speaking…", 0f)
        scope.launch {
            try {
                when (val outcome = AutoCutEngine.analyze(app, clip) { p -> busy = Busy("Finding the parts where nobody is speaking…", p) }) {
                    is AutoCutOutcome.Unchanged -> message = outcome.message
                    is AutoCutOutcome.Ready -> autoCutReview = outcome
                }
            } finally {
                busy = null
            }
        }
    }

    fun cancelAutoCut() {
        autoCutReview = null
    }

    /** Applies the reviewed AutoCut with the chosen strength. */
    fun applyAutoCut() {
        val review = autoCutReview ?: return
        autoCutReview = null
        val now = timeline.byId(review.clip.id)
        if (now == null || now.sourceUri != review.clip.sourceUri || now.sourceStartMs != review.clip.sourceStartMs ||
            now.sourceEndMs != review.clip.sourceEndMs
        ) {
            message = "The clip changed while AutoCut was open. Run AutoCut again."
            return
        }
        val plan = review.planFor(autoCutStrength)
        if (plan.keep.isEmpty() || plan.removedMs < 100) {
            message = "Nothing to remove at this strength. Nothing was changed."
            return
        }
        val result = TimelineOps.applyKeepRanges(timeline, review.clip.id, plan.keep, ::newClipId)
        if (result.changed) {
            commitEdit(result.timeline)
            player.seekTo(review.clip.timelineStartMs)
            val seconds = String.format(Locale.US, "%.1f", plan.removedMs / 1000f)
            message = "AutoCut removed $seconds s of pauses (${plan.cuts} cuts)."
        } else {
            message = result.reason ?: "Nothing was changed."
        }
    }

    // ---- Steps 5-7: libraries ------------------------------------------------------------

    fun importToLibrary(kind: LibraryKind, uris: List<Uri>) {
        if (uris.isEmpty() || busy != null) return
        busy = Busy("Saving to ${kind.title}…")
        scope.launch {
            try {
                val count = app.library.import(kind, uris)
                if (count < uris.size) {
                    message = if (count == 0) "These files could not be imported." else "$count of ${uris.size} files were imported."
                }
            } finally {
                busy = null
            }
        }
    }

    /** Inserts a saved library item at the playhead: Video FX into the main track, audio on its track. */
    fun insertFromLibrary(kind: LibraryKind, item: LibraryItemEntity) {
        val info = app.library.toMediaInfo(item)
        if (!app.library.file(item).exists()) {
            message = "The saved file is missing."
            return
        }
        when (kind) {
            LibraryKind.VIDEO_FX -> {
                // Video FX go on the layer above the video (overlay track), at the playhead,
                // fitted to the frame - the main video stays as it is.
                val fit = if (info.aspect >= canvasAspect) 1f else info.aspect / canvasAspect
                val clip = clipFrom(info, Track.OVERLAY, timelineStartMs = position, libraryItemId = item.id)
                    .copy(posX = 0.5f, posY = 0.5f, scale = fit.coerceIn(TimelineOps.MIN_SCALE, 1f))
                commitEdit(TimelineOps.select(TimelineOps.addClip(timeline, clip), clip.id))
                message = "Video FX added on the layer above the video."
            }
            LibraryKind.SOUND_FX, LibraryKind.MUSIC -> {
                val track = if (kind == LibraryKind.SOUND_FX) Track.SFX else Track.MUSIC
                val clip = clipFrom(info, track, timelineStartMs = position, libraryItemId = item.id)
                commitEdit(TimelineOps.select(TimelineOps.addClip(timeline, clip), clip.id))
            }
        }
        library = null
    }

    // ---- Step 8: Overlay -----------------------------------------------------------------

    fun addOverlay(uri: Uri) {
        scope.launch {
            persistRead(app, uri)
            val info = withContext(Dispatchers.IO) { MediaProbe.probe(app, uri) }
            if (info == null || info.kind == MediaKind.AUDIO) {
                message = "Choose an image or a video for the overlay."
                return@launch
            }
            val clip = clipFrom(info, Track.OVERLAY, timelineStartMs = position)
            commitEdit(TimelineOps.select(TimelineOps.addClip(timeline, clip), clip.id))
        }
    }

    /** Deletes the selected clip (any track). The last main clip cannot be deleted. */
    fun deleteSelected() {
        val clip = timeline.selected ?: return
        if (clip.track == Track.MAIN && timeline.main.size <= 1) {
            message = "A project needs at least one clip on the main track."
            return
        }
        commitEdit(TimelineOps.remove(timeline, clip.id))
    }

    /** Zoom / move the main video inside the frame (two-finger pinch in the preview). */
    fun transformMain(id: String, x: Float, y: Float, zoom: Float, rotation: Float) =
        commitEdit(TimelineOps.transformMain(timeline, id, x, y, zoom, rotation), saveNow = false)

    /** "+" on the timeline: adds more videos / images to the end of the main track. */
    fun appendMedia(uris: List<Uri>) {
        if (uris.isEmpty() || busy != null) return
        busy = Busy("Adding to the timeline…")
        scope.launch {
            try {
                val media = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri ->
                        persistRead(app, uri)
                        MediaProbe.probe(app, uri)
                    }.filter { it.kind != MediaKind.AUDIO }
                }
                if (media.isEmpty()) {
                    message = "None of the selected files could be opened as a video or image."
                } else {
                    commitEdit(TimelineOps.appendMain(timeline, media.map { clipFrom(it, Track.MAIN) }))
                }
            } finally {
                busy = null
            }
        }
    }

    // ---- Gestures on the timeline ----------------------------------------------------------

    /** Timeline time the clip being dragged has locked onto (shown as a guide line), or null. */
    var snapGuideMs by mutableStateOf<Long?>(null)
        private set

    /** Main clip being trimmed: the preview shows its live frame at the moving edge. */
    var liveTrimClipId by mutableStateOf<String?>(null)
        private set
    private var trimEdgeTimelineMs: Long? = null

    /**
     * Alignment guides to draw over the preview while something is dragged in the frame (bit
     * mask, see core Align). Only on screen - never part of the exported video.
     */
    var alignGuides by mutableStateOf(0)

    /** The playable clip whose frame the preview shows while a trim handle is dragged. */
    var liveTrimPiece by mutableStateOf<Clip?>(null)
        private set

    private fun targets(excludeId: String) = Snap.targets(timeline, excludeId, position)

    /** Trim handles of a main-track clip. The preview follows the edge being dragged. */
    fun trimMain(id: String, sourceStartMs: Long? = null, sourceEndMs: Long? = null, snapMs: Long = 0) {
        var end = sourceEndMs
        val before = timeline.byId(id) ?: return
        snapGuideMs = null
        if (end != null && snapMs > 0 && before.kind != MediaKind.IMAGE) {
            // the right edge of a main clip moves on the timeline: let it lock onto the playhead etc.
            val rawEdge = before.timelineStartMs + (end - before.sourceStartMs)
            val snapped = Snap.edge(rawEdge, Snap.mainEdgeTargets(timeline, id, position), snapMs)
            if (snapped.guideMs != null) {
                end = before.sourceStartMs + (snapped.value - before.timelineStartMs)
                snapGuideMs = snapped.guideMs
            }
        }
        val t = TimelineOps.trimMain(timeline, id, sourceStartMs, end)
        commitEdit(t, saveNow = false)
        val after = t.byId(id) ?: return
        val edge = if (sourceStartMs != null) after.timelineStartMs else (after.timelineEndMs - 40).coerceAtLeast(after.timelineStartMs)
        // inside a compound the frame comes from the piece at the edge
        val piece = if (after.isCompound) Compound.pieceAt(after, edge, canvasAspect) else after
        if (piece.kind == MediaKind.VIDEO) {
            liveTrimClipId = id
            liveTrimPiece = piece
            LiveFrames.request(app, piece.sourceUri, piece.timelineToSource(edge))
        }
        trimEdgeTimelineMs = edge
    }

    fun move(id: String, startMs: Long, snapMs: Long = 0) {
        val clip = timeline.byId(id) ?: return
        val snapped = if (snapMs > 0) Snap.move(startMs, clip.durationMs, targets(id), snapMs) else com.videoeditor.core.Snapped(startMs, null)
        snapGuideMs = snapped.guideMs
        commitEdit(TimelineOps.move(timeline, id, snapped.value), saveNow = false)
    }

    fun trimStart(id: String, startMs: Long, snapMs: Long = 0) {
        val snapped = if (snapMs > 0) Snap.edge(startMs, targets(id), snapMs) else com.videoeditor.core.Snapped(startMs, null)
        snapGuideMs = snapped.guideMs
        commitEdit(TimelineOps.trimStart(timeline, id, snapped.value), saveNow = false)
    }

    fun trimEnd(id: String, endMs: Long, snapMs: Long = 0) {
        val snapped = if (snapMs > 0) Snap.edge(endMs, targets(id), snapMs) else com.videoeditor.core.Snapped(endMs, null)
        snapGuideMs = snapped.guideMs
        commitEdit(TimelineOps.trimEnd(timeline, id, snapped.value), saveNow = false)
    }

    /** A drag on the timeline ended: apply it to the preview at once and save. */
    fun endGesture() {
        android.util.Log.d("NockNock", "endGesture edge=$trimEdgeTimelineMs live=$liveTrimClipId")
        snapGuideMs = null
        val edge = trimEdgeTimelineMs
        trimEdgeTimelineMs = null
        player.flush()
        if (liveTrimClipId != null) {
            liveTrimClipId = null
            liveTrimPiece = null
            LiveFrames.clear()
            if (edge != null) player.seekTo(edge)
        }
        if (pendingSave != null) save()
    }

    /** Moves the selected main-track clip one place earlier (-1) or later (+1). */
    fun moveSelectedMain(delta: Int) {
        val clip = timeline.selected ?: return
        if (clip.track != Track.MAIN) return
        val moved = TimelineOps.moveMain(timeline, clip.id, delta)
        if (moved == timeline) {
            message = if (delta < 0) "This clip is already the first one." else "This clip is already the last one."
        } else {
            commitEdit(moved)
        }
    }

    /** Makes a clip wider / taller (W x H control). */
    fun stretch(id: String, stretchX: Float, stretchY: Float) =
        commitEdit(TimelineOps.stretch(timeline, id, stretchX, stretchY), saveNow = false)

    /** Size of the rendered frame in pixels (used for the position read-out). */
    val canvasPixels: Pair<Int, Int> get() = canvasSize(targetAspect)

    /** Duplicate: a main clip's copy goes right after it, other clips get a copy on top. */
    fun duplicateSelected() {
        val clip = timeline.selected ?: return
        if (clip.track == Track.MAIN) {
            commitEdit(TimelineOps.duplicateMain(timeline, clip.id, ::newClipId))
            message = "Duplicated - the copy is right after the clip."
        } else {
            commitEdit(TimelineOps.duplicate(timeline, clip.id, ::newClipId))
        }
    }

    // ---- Copy / paste --------------------------------------------------------------------

    fun copySelected() {
        val clip = timeline.selected ?: return
        clipboard = clip.copy(selected = false)
        message = "Copied. Move the playhead and tap Paste (also works in another project)."
    }

    /** Pastes the copied clip at the playhead (a main clip under the playhead is split there). */
    fun paste() {
        val clip = clipboard ?: return
        val missing = leaves(clip).firstOrNull { leaf ->
            val uri = Uri.parse(leaf.sourceUri)
            uri.scheme == "file" && uri.path?.let { !java.io.File(it).exists() } == true
        }
        if (missing != null) {
            message = "The copied clip's file no longer exists, so it cannot be pasted."
            return
        }
        commitEdit(TimelineOps.paste(timeline, clip, position, ::newClipId))
        message = if (clip.track == Track.MAIN) "Pasted at the playhead." else "Pasted at the playhead on its own track."
    }

    private fun leaves(c: Clip): List<Clip> = if (c.isCompound) c.children.flatMap { leaves(it) } else listOf(c)

    // ---- Auto captions and texts ---------------------------------------------------------

    /** The Auto Captions sheet is open. */
    var captionSheet by mutableStateOf(false)
    var captionLanguage by mutableStateOf(CaptionLanguage.BANGLA)
    var captionStyle by mutableStateOf("CLASSIC")

    /** Model download in progress: bytes so far / total (null = not downloading). */
    var modelDownload by mutableStateOf<Pair<Long, Long>?>(null)
        private set
    var modelReadyTick by mutableStateOf(0)
        private set
    private var captionJob: Job? = null

    /** Captions are being made (the progress dialog shows Cancel). */
    var captionsRunning by mutableStateOf(false)
        private set

    /** Which caption / text the edit dialog is open for. */
    var editingTextId by mutableStateOf<String?>(null)
    var textStyleSheet by mutableStateOf(false)

    val hasAutoCaptions: Boolean
        get() = timeline.clips.any { it.track == Track.TEXT && it.name == Captions.AUTO_NAME }

    fun modelReady(model: CaptionModel): Boolean {
        modelReadyTick // read so the UI updates after a download / delete
        return CaptionModels.isReady(app, model)
    }

    fun downloadModel(model: CaptionModel) {
        if (modelDownload != null) return
        modelDownload = 0L to model.approxBytes
        captionJob = scope.launch {
            try {
                CaptionModels.download(app, model) { done, total -> modelDownload = done to (if (total > 0) total else model.approxBytes) }
                message = "Speech model downloaded. It stays on the phone for next time."
            } catch (e: kotlinx.coroutines.CancellationException) {
                message = "Download stopped. It continues where it stopped next time."
            } catch (e: Exception) {
                message = "Download failed: ${e.message ?: e.javaClass.simpleName}. Check the internet and try again."
            } finally {
                modelDownload = null
                modelReadyTick++
            }
        }
    }

    fun deleteModel(model: CaptionModel) {
        CaptionModels.delete(app, model)
        modelReadyTick++
    }

    fun stopCaptionWork() {
        captionJob?.cancel()
    }

    /** Recognises the speech of the main track and puts captions on the text track. */
    fun createCaptions() {
        if (busy != null || timeline.main.isEmpty()) return
        val language = captionLanguage
        if (!modelReady(language.model)) {
            message = "Download the speech model first."
            return
        }
        player.pause()
        captionSheet = false
        busy = Busy("Reading the sound…", 0f)
        captionsRunning = true
        val project = timeline
        captionJob = scope.launch {
            try {
                val pieces = AutoCaptioner.recognise(app, flat.main, language) { text, p -> busy = Busy(text, p) }
                val captions = Captions.build(pieces, project.durationMs, ::newClipId, style = captionStyle)
                if (captions.isEmpty()) {
                    message = "No speech was found, so no captions were added."
                } else {
                    commitEdit(Captions.replaceAuto(timeline, captions))
                    message = "${captions.size} captions added. Tap one to edit its words or style."
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                message = "Captions stopped. Nothing was changed."
            } catch (e: AutoCaptioner.Failed) {
                message = e.message
            } catch (e: Exception) {
                message = "Captions failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = null
                captionJob = null
                captionsRunning = false
            }
        }
    }

    /** Removes every auto caption (typed texts stay). */
    fun removeAutoCaptions() {
        if (!hasAutoCaptions) return
        commitEdit(Captions.replaceAuto(timeline, emptyList()))
        message = "Captions removed."
    }

    /** "Text": a new text at the playhead, 3 s long, and the editor for its words. */
    fun addText() {
        val start = position.coerceIn(0, (timeline.durationMs - 500).coerceAtLeast(0))
        val clip = Captions.textClip(newClipId(), start, 3_000, "Text", style = captionStyle, posY = 0.5f, size = 0.07f)
        commitEdit(TimelineOps.select(TimelineOps.addClip(timeline, clip), clip.id))
        editingTextId = clip.id
    }

    fun setText(id: String, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) {
            commitEdit(TimelineOps.remove(timeline, id))
        } else {
            commitEdit(Captions.setText(timeline, id, clean))
        }
        editingTextId = null
    }

    /**
     * Style / size / height of captions. Auto captions change together (they are one set, like in
     * Edits); a typed text changes on its own.
     */
    fun restyleText(id: String, style: String? = null, posY: Float? = null, size: Float? = null, live: Boolean = false) {
        val clip = timeline.byId(id) ?: return
        val ids = if (clip.name == Captions.AUTO_NAME) {
            timeline.clips.filter { it.track == Track.TEXT && it.name == Captions.AUTO_NAME }.map { it.id }.toSet()
        } else setOf(id)
        if (style != null) captionStyle = style
        commitEdit(Captions.restyle(timeline, ids, style, posY, size), saveNow = !live)
    }

    /** Dragging a caption / text in the preview (auto captions move together). */
    fun moveText(id: String, x: Float, y: Float) {
        val clip = timeline.byId(id) ?: return
        val dx = x - clip.posX
        val dy = y - clip.posY
        val group = clip.name == Captions.AUTO_NAME
        commitEdit(
            Timeline(timeline.clips.map {
                if (it.id == id || (group && it.track == Track.TEXT && it.name == Captions.AUTO_NAME)) {
                    it.copy(posX = (it.posX + dx).coerceIn(0f, 1f), posY = (it.posY + dy).coerceIn(0f, 1f))
                } else it
            }),
            saveNow = false,
        )
    }

    // ---- Multi-select and compound clips --------------------------------------------------

    /**
     * Multi-select mode (like CapCut): the clips the user has picked, on any track (null = not
     * picking). Long-press a clip to start; then tap or long-press more clips (timeline or
     * preview) to add or remove them.
     */
    var multiPick by mutableStateOf<Set<String>?>(null)
        private set

    /** Main-track clips that join a compound because they lie between picked main clips. */
    val pickRange: IntRange?
        get() {
            val pick = multiPick ?: return null
            val idx = timeline.main.mapIndexedNotNull { i, c -> if (c.id in pick) i else null }
            return if (idx.isEmpty()) null else idx.min()..idx.max()
        }

    /** How many clips a compound made now would hold (picked + main clips in between). */
    val pickCount: Int
        get() {
            val pick = multiPick ?: return 0
            val mainIds = timeline.main.map { it.id }.toSet()
            val range = pickRange
            return (if (range == null) 0 else range.last - range.first + 1) +
                pick.count { it !in mainIds && timeline.byId(it) != null }
        }

    private fun startPicking(first: String?) {
        if (busy != null) return
        player.pause()
        multiPick = setOfNotNull(first)
        if (timeline.selected != null) commitEdit(TimelineOps.select(timeline, null), undoable = false)
    }

    /** Long-press on a clip (timeline or preview): start picking, or add / remove it. */
    fun longPress(id: String) {
        val owner = Compound.ownerId(id)
        if (timeline.byId(owner) == null) return
        if (multiPick == null) startPicking(owner) else togglePick(owner)
    }

    /** "Compound" button: start picking with the selected clip already picked. */
    fun startCompound() {
        startPicking(timeline.selected?.id)
    }

    fun togglePick(id: String) {
        val pick = multiPick ?: return
        multiPick = if (id in pick) pick - id else pick + id
    }

    fun pickAll() {
        if (multiPick == null) return
        val all = timeline.clips.map { it.id }.toSet()
        multiPick = if (multiPick == all) emptySet() else all
    }

    fun cancelPicking() {
        multiPick = null
    }

    fun makeCompound() {
        val pick = multiPick ?: return
        val addingToOne = pick.any { timeline.byId(it)?.isCompound == true }
        val result = Compound.create(timeline, pick, ::newClipId, canvasAspect)
        if (!result.changed) {
            message = result.reason
            return
        }
        multiPick = null
        commitEdit(result.timeline)
        val count = result.timeline.selected?.let { Compound.leafCount(it) } ?: pick.size
        val text = if (addingToOne) "Added to the compound clip - it now holds $count clips."
        else "Compound clip made from $count clips. Tap Uncompound to take them apart again."
        message = text + (result.reason?.let { " $it" } ?: "")
    }

    /** Deletes every picked clip (one undo step). The main track keeps at least one clip. */
    fun deletePicked() {
        val pick = multiPick ?: return
        if (pick.isEmpty()) return
        if (timeline.main.all { it.id in pick }) {
            message = "A project needs at least one clip on the main track."
            return
        }
        multiPick = null
        commitEdit(TimelineOps.normalize(Timeline(timeline.clips.filter { it.id !in pick })))
        message = "Deleted ${pick.size} clip(s)."
    }

    fun uncompoundSelected() {
        val clip = timeline.selected ?: return
        val result = Compound.uncompound(timeline, clip.id, ::newClipId, canvasAspect)
        if (result.changed) {
            commitEdit(result.timeline)
            message = "Compound clip taken apart into ${clip.children.size} clips (back on their tracks)."
        } else {
            message = result.reason
        }
    }

    fun comingSoon(feature: String) {
        message = "$feature is not available yet. It is planned for a later version."
    }

    fun transform(id: String, x: Float, y: Float, scale: Float, rotation: Float) =
        commitEdit(TimelineOps.transform(timeline, id, x, y, scale, rotation), saveNow = false)


    // ---- Rendering -----------------------------------------------------------------------

    fun export() {
        if (busy != null || timeline.main.isEmpty()) return
        player.pause()
        busy = Busy("Rendering video…", 0f)
        scope.launch {
            try {
                Exporter(app).export(timeline, targetAspect) { p -> busy = Busy("Rendering video…", p) }
                message = "Saved to Movies/VideoEditor in your Gallery."
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Rendering failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = null
            }
        }
    }

    fun release() {
        save()
        LiveFrames.clear()
        player.release()
        if (current === this) current = null
    }
}
