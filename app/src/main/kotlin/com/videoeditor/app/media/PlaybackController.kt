package com.videoeditor.app.media

import android.content.Context
import android.view.TextureView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import android.util.Log
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.videoeditor.core.AudioFx
import com.videoeditor.core.Clip
import com.videoeditor.core.FxSegment
import com.videoeditor.core.PlayMap
import com.videoeditor.core.Compound
import com.videoeditor.core.MediaKind
import com.videoeditor.core.Timeline
import com.videoeditor.core.TimelineOps
import com.videoeditor.core.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Preview playback for the whole timeline. Must be used from the main thread.
 *
 * - The main track is one ExoPlayer playlist; every clip is a clipped view of its source file,
 *   so split / AutoCut pieces play with the audio and video of the same source timestamps.
 * - Playlist changes are applied incrementally (only the clips that changed are replaced), and
 *   while a finger is dragging (trim) they are held back and applied once the drag pauses, so the
 *   preview never stalls on a storm of re-preparations.
 * - Scrubbing is "latest wins": while a seek is still being processed only the newest target is
 *   remembered, then jumped to. Fast key-frame seeks while the finger moves, an exact seek at rest.
 * - Sound effects, music and video overlays each get a small player that is started, stopped
 *   and re-synchronised against the main timeline position ~30 times per second.
 */
class PlaybackController(private val context: Context, private val scope: CoroutineScope) {
    /** Voice enhance of the main track (the plan follows the playlist, see [updateFx]). */
    val mainFx = FxState(sourceTime = false)
    val main: ExoPlayer = ExoPlayer.Builder(context, FxRenderersFactory(context, mainFx))
        .setLoadControl(previewLoadControl())
        .setMediaSourceFactory(VoiceMediaSourceFactory(context))
        .build()
    val positionMs = MutableStateFlow(0L)
    val isPlaying = MutableStateFlow(false)

    private var mainClips: List<Clip> = emptyList()      // what the player currently plays
    private var wantedClips: List<Clip> = emptyList()    // what the timeline says
    private var mainSignature: List<String> = emptyList()
    private var pendingApply: Job? = null
    private var auxClips: List<Clip> = emptyList()
    private val aux = HashMap<String, ExoPlayer>()
    private val auxFx = HashMap<String, FxState>()
    /** The isolated voice file each overlay / sound player plays ("" = its own sound). */
    private val auxVoice = HashMap<String, String>()
    /** Sound settings as the timeline says (by piece id), applied to whatever is playing. */
    private var wantedFx: Map<String, AudioFx> = emptyMap()
    private val overlayViews = HashMap<String, TextureView>()
    private var released = false
    private var exporting = false
    private var exportPosition = 0L

    /** Stop and clear preview sources before allocating export decoders/buffers. */
    fun suspendForExport() {
        if (released || exporting) return
        exportPosition = positionMs.value
        exporting = true
        pendingApply?.cancel()
        pendingApply = null
        main.pause()
        main.stop()
        main.clearMediaItems()
        mainSignature = emptyList()
        aux.values.forEach { it.release() }
        aux.clear()
        auxFx.clear()
        auxVoice.clear()
        isPlaying.value = false
    }

    fun resumeAfterExport(timeline: Timeline) {
        if (released || !exporting) return
        exporting = false
        setTimeline(timeline)
        seekTo(exportPosition)
    }

    private fun previewLoadControl() = DefaultLoadControl.Builder()
        .setBufferDurationsMs(1_000, 5_000, 250, 500)
        .setTargetBufferBytes(8 * 1024 * 1024)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()

    // scrubbing state
    private var seekInFlightSince = 0L
    private var pendingSeekMs: Long? = null
    private var scrubbing = false

    /** Number of playlist rebuilds / incremental updates (for tests). */
    var playlistUpdates = 0
        private set

    init {
        main.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) seekDone()
            }

            override fun onRenderedFirstFrame() = seekDone()

            override fun onPlayerError(error: PlaybackException) {
                // never leave the preview stuck: note it, prepare again and carry on from here
                errors++
                lastError = "${error.errorCodeName}: ${error.cause?.message ?: error.message}"
                Log.e("PlaybackController", "player error, recovering", error)
                val now = System.currentTimeMillis()
                recoveries.addLast(now)
                while (recoveries.isNotEmpty() && now - recoveries.first() > 10_000) recoveries.removeFirst()
                if (released || recoveries.size > 3) return
                val wasPlaying = main.playWhenReady
                val at = positionMs.value
                main.prepare()
                seekTo(at)
                if (wasPlaying) main.play()
            }
        })
    }

    /** Player errors so far (tests check there are none) and the last one. */
    @Volatile
    var errors = 0
        private set
    @Volatile
    var lastError: String? = null
        private set
    private val recoveries = ArrayDeque<Long>()

    /** A file's noise was learned: the players use it from now on. */
    private val fxWatcher: Job = scope.launch {
        VoiceProcessing.version.collect {
            if (released || exporting) return@collect
            updateFx()
            // a voice was isolated: the clips that wait for it switch to it now
            if (pendingApply?.isActive != true) applyMain()
            refreshAuxVoices()
        }
    }

    private fun refreshAuxVoices() {
        for (c in auxClips) {
            val p = aux[c.id] ?: continue
            val (item, voice) = auxItem(c)
            if (auxVoice[c.id] == voice) continue
            auxVoice[c.id] = voice
            p.setMediaItem(item)
            p.prepare()
        }
    }

    private val ticker: Job = scope.launch {
        while (isActive) {
            tick()
            delay(33)
        }
    }

    val durationMs: Long get() = wantedClips.sumOf { it.durationMs }

    /**
     * Shows [timeline]. With [interactive] (a finger is still dragging) the main playlist is
     * updated a moment after the last change instead of immediately.
     */
    fun setTimeline(timeline: Timeline, interactive: Boolean = false) {
        if (released || exporting) return
        // compound clips play as the pieces inside them, on every track (framing does not
        // matter to the player)
        val flat = Compound.flatten(timeline, 1f)
        wantedClips = flat.main
        wantedFx = flat.clips.filter { !it.audio.isDefault }.associate { it.id to it.audio }
        syncAuxPlayers(flat)
        refreshAuxVoices()
        updateFx()
        pendingApply?.cancel()
        if (interactive) {
            pendingApply = scope.launch {
                delay(220)
                applyMain()
            }
        } else {
            applyMain()
        }
    }

    /** Applies any held-back playlist change now (e.g. when the drag ends). */
    fun flush() {
        if (released) return
        pendingApply?.cancel()
        pendingApply = null
        applyMain()
    }

    private fun signatureOf(c: Clip) = "${c.kind}|${c.sourceUri}|${c.sourceStartMs}|${c.sourceEndMs}|${voiceOf(c) ?: ""}"

    /** The isolated voice file a clip plays, when Isolate voice is on and the voice is ready. */
    private fun voiceOf(c: Clip): String? =
        if (c.audio.isolate && c.isAudible) VoiceProcessing.isolatedVoice(c.sourceUri)?.absolutePath else null

    private fun auxItem(c: Clip): Pair<MediaItem, String> {
        val voice = voiceOf(c)
        val b = MediaItem.Builder().setUri(c.sourceUri)
        if (voice != null) b.setTag(IsolatedAudio(voice)).setCustomCacheKey(IsolatedAudio.key(voice))
        return b.build() to (voice ?: "")
    }

    private fun applyMain() {
        val newMain = wantedClips
        val signature = newMain.map { signatureOf(it) }
        if (signature == mainSignature) {
            mainClips = newMain
            updateFx()
            return
        }
        val keep = positionMs.value
        val old = mainSignature
        // keep the unchanged clips at both ends; replace only what is in between
        var prefix = 0
        while (prefix < old.size && prefix < signature.size && old[prefix] == signature[prefix]) prefix++
        var suffix = 0
        while (suffix < old.size - prefix && suffix < signature.size - prefix &&
            old[old.size - 1 - suffix] == signature[signature.size - 1 - suffix]
        ) suffix++
        val items = newMain.subList(prefix, newMain.size - suffix).map { mainItem(it) }
        if (old.isEmpty() || main.mediaItemCount != old.size) {
            main.setMediaItems(newMain.map { mainItem(it) })
        } else {
            val removeTo = old.size - suffix
            if (items.isEmpty()) {
                main.removeMediaItems(prefix, removeTo)
            } else if (removeTo == prefix) {
                main.addMediaItems(prefix, items)
            } else {
                main.replaceMediaItems(prefix, removeTo, items)
            }
        }
        mainSignature = signature
        mainClips = newMain
        updateFx()
        playlistUpdates++
        if (main.playbackState == Player.STATE_IDLE) main.prepare()
        seekTo(keep)
    }

    /**
     * Hands the sound settings to the players: the main player's per clip in timeline time (for
     * the playlist that is really playing), each sound effect / overlay player's in source time.
     */
    private fun updateFx() {
        val fx = wantedFx
        mainFx.map.items = mainClips.map {
            PlayMap.Item(it.id, it.timelineStartMs * 1000, it.sourceStartMs * 1000, it.durationMs * 1000, it.isAudible)
        }
        mainFx.plan = mainClips.filter { it.isAudible }.map {
            FxSegment(
                it.id, it.timelineStartMs * 1000, it.timelineEndMs * 1000, fx[it.id] ?: AudioFx.NONE,
                profile = VoiceProcessing.profile(it.sourceUri),
            )
        }
        for (c in auxClips) {
            val state = auxFx[c.id] ?: continue
            state.plan = listOf(
                FxSegment(
                    c.id, c.sourceStartMs * 1000, c.sourceEndMs * 1000, fx[c.id] ?: AudioFx.NONE,
                    profile = VoiceProcessing.profile(c.sourceUri),
                ),
            )
        }
    }

    /** The sound processing state of the overlay / sound player of [clipId] (for tests). */
    fun auxFx(clipId: String): FxState? = auxFx[clipId]

    private fun mainItem(c: Clip): MediaItem {
        val b = MediaItem.Builder().setUri(c.sourceUri).setMediaId(c.id)
        voiceOf(c)?.let { b.setTag(IsolatedAudio(it)).setCustomCacheKey(IsolatedAudio.key(it)) }
        if (c.kind == MediaKind.IMAGE) {
            b.setMimeType(c.mimeType ?: "image/jpeg").setImageDurationMs(c.durationMs)
        } else {
            b.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(c.sourceStartMs)
                    .setEndPositionMs(c.sourceEndMs)
                    .build(),
            )
        }
        return b.build()
    }

    private fun syncAuxPlayers(timeline: Timeline) {
        // overlay videos, sound effects and music (images and texts make no sound)
        val wanted = timeline.clips.filter { it.track != Track.MAIN && (it.kind == MediaKind.VIDEO || it.kind == MediaKind.AUDIO) }
        val ids = wanted.map { it.id }.toSet()
        for (id in aux.keys.filter { it !in ids }) {
            aux.remove(id)?.release()
            auxFx.remove(id)
            auxVoice.remove(id)
            overlayViews.remove(id)
        }
        for (c in wanted) {
            if (c.id !in aux) {
                val fx = FxState(sourceTime = true)
                auxFx[c.id] = fx
                val p = ExoPlayer.Builder(context, FxRenderersFactory(context, fx))
                    .setLoadControl(previewLoadControl())
                    .setMediaSourceFactory(VoiceMediaSourceFactory(context))
                    .build()
                val item = auxItem(c)
                auxVoice[c.id] = item.second
                p.setMediaItem(item.first)
                p.playWhenReady = false
                p.prepare()
                overlayViews[c.id]?.let { p.setVideoTextureView(it) }
                aux[c.id] = p
            }
        }
        auxClips = wanted
    }

    /** Connects (or disconnects, with null) the on-screen surface of a video overlay. */
    fun bindOverlayView(clipId: String, view: TextureView?) {
        if (released) return
        if (view == null) {
            val old = overlayViews.remove(clipId)
            if (old != null) aux[clipId]?.clearVideoTextureView(old)
        } else {
            overlayViews[clipId] = view
            aux[clipId]?.setVideoTextureView(view)
        }
    }

    fun play() {
        if (released || exporting) return
        if (pendingApply != null) flush()
        if (scrubbing) endScrub()
        if (mainClips.isEmpty()) return
        if (positionMs.value >= durationMs - 50) seekTo(0)
        main.play()
    }

    fun pause() {
        if (!released) main.pause()
    }

    fun toggle() {
        if (isPlaying.value) pause() else play()
    }

    fun seekTo(timelineMs: Long) {
        if (released || exporting || mainClips.isEmpty()) return
        val ms = timelineMs.coerceIn(0, mainClips.sumOf { it.durationMs })
        val pos = TimelineOps.locate(Timeline(mainClips), ms) ?: return
        val inClip = (ms - pos.clip.timelineStartMs).coerceIn(0, (pos.clip.durationMs - 1).coerceAtLeast(0))
        mainFx.map.hintUs = ms * 1000
        main.seekTo(pos.index, inClip)
        seekInFlightSince = System.currentTimeMillis()
        positionMs.value = ms
    }

    /**
     * Seek while a finger drags the timeline: only the newest target matters. Uses fast key-frame
     * seeks; call [endScrub] when the finger lifts to land exactly.
     */
    fun scrubTo(timelineMs: Long) {
        if (released || exporting || mainClips.isEmpty()) return
        if (!scrubbing) {
            scrubbing = true
            main.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        }
        positionMs.value = timelineMs.coerceIn(0, durationMs)
        if (seekInFlight()) {
            pendingSeekMs = timelineMs
        } else {
            pendingSeekMs = null
            seekTo(timelineMs)
        }
    }

    fun endScrub() {
        if (!scrubbing || released) return
        scrubbing = false
        main.setSeekParameters(SeekParameters.EXACT)
        val target = pendingSeekMs ?: positionMs.value
        pendingSeekMs = null
        seekTo(target)
    }

    private fun seekInFlight(): Boolean =
        seekInFlightSince != 0L && System.currentTimeMillis() - seekInFlightSince < 250

    private fun seekDone() {
        seekInFlightSince = 0L
        val next = pendingSeekMs ?: return
        pendingSeekMs = null
        seekTo(next)
    }

    private fun tick() {
        if (released || exporting || mainClips.isEmpty()) return
        // a seek that never reported back: let the newest scrub target through
        if (pendingSeekMs != null && !seekInFlight()) seekDone()
        if (scrubbing || pendingSeekMs != null) return
        val index = main.currentMediaItemIndex.coerceIn(0, mainClips.lastIndex)
        val clip = mainClips[index]
        var pos = clip.timelineStartMs + main.currentPosition.coerceIn(0, clip.durationMs)
        val ended = main.playbackState == Player.STATE_ENDED
        if (ended) {
            pos = durationMs
            if (main.playWhenReady) main.pause()
        }
        val playing = main.playWhenReady && !ended
        positionMs.value = pos
        isPlaying.value = playing
        mainFx.map.hintUs = pos * 1000
        val total = durationMs
        for (c in auxClips) {
            val p = aux[c.id] ?: continue
            val active = pos >= c.timelineStartMs && pos < c.timelineEndMs && pos < total
            if (!active) {
                if (p.playWhenReady) p.pause()
                continue
            }
            val want = c.sourceStartMs + (pos - c.timelineStartMs)
            val drift = abs(p.currentPosition - want)
            if (playing) {
                if (!p.playWhenReady || drift > 300) p.seekTo(want)
                if (!p.playWhenReady) p.play()
            } else {
                if (p.playWhenReady) p.pause()
                if (drift > 60) p.seekTo(want)
            }
        }
    }

    fun release() {
        if (released) return
        released = true
        ticker.cancel()
        fxWatcher.cancel()
        pendingApply?.cancel()
        main.release()
        aux.values.forEach { it.release() }
        aux.clear()
        overlayViews.clear()
    }
}
