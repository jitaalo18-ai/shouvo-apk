package com.videoeditor.app.data

import android.content.Context
import android.net.Uri
import com.videoeditor.app.media.MediaInfo
import com.videoeditor.app.media.MediaProbe
import com.videoeditor.core.AudioFx
import com.videoeditor.core.Clip
import com.videoeditor.core.Compound
import com.videoeditor.core.MediaKind
import com.videoeditor.core.Timeline
import com.videoeditor.core.TimelineOps
import com.videoeditor.core.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

const val DEFAULT_IMAGE_MS = 3000L

fun newClipId(): String = UUID.randomUUID().toString()

fun ClipEntity.toClip() = Clip(
    children = ClipJson.decode(childrenJson),
    text = text,
    style = style,
    audio = AudioFx.decode(audioFx),
    id = id,
    track = Track.valueOf(track),
    kind = MediaKind.valueOf(kind),
    sourceUri = sourceUri,
    mimeType = mimeType,
    name = name,
    libraryItemId = libraryItemId,
    sourceDurationMs = sourceDurationMs,
    sourceStartMs = sourceStartMs,
    sourceEndMs = sourceEndMs,
    timelineStartMs = timelineStartMs,
    hasAudio = hasAudio,
    aspect = aspect,
    layer = layer,
    posX = posX,
    posY = posY,
    scale = scale,
    selected = selected,
    rotation = rotation,
    stretchX = stretchX,
    stretchY = stretchY,
)

fun Clip.toEntity(projectId: Long) = ClipEntity(
    id = id,
    projectId = projectId,
    track = track.name,
    kind = kind.name,
    sourceUri = sourceUri,
    mimeType = mimeType,
    name = name,
    libraryItemId = libraryItemId,
    sourceDurationMs = sourceDurationMs,
    sourceStartMs = sourceStartMs,
    sourceEndMs = sourceEndMs,
    timelineStartMs = timelineStartMs,
    hasAudio = hasAudio,
    aspect = aspect,
    layer = layer,
    posX = posX,
    posY = posY,
    scale = scale,
    selected = selected,
    rotation = rotation,
    stretchX = stretchX,
    stretchY = stretchY,
    childrenJson = if (children.isEmpty()) null else ClipJson.encode(children),
    childLibraryIds = Compound.libraryIds(this).takeIf { it.isNotEmpty() }?.joinToString(",", ",", ","),
    text = text,
    style = style,
    audioFx = audio.encode(),
)

/** Builds a clip that plays the whole of [info]. */
fun clipFrom(info: MediaInfo, track: Track, timelineStartMs: Long = 0, libraryItemId: Long? = null): Clip {
    val dur = if (info.kind == MediaKind.IMAGE) DEFAULT_IMAGE_MS else info.durationMs
    return Clip(
        id = newClipId(),
        track = track,
        kind = info.kind,
        sourceUri = info.uri.toString(),
        mimeType = info.mimeType,
        name = info.name,
        libraryItemId = libraryItemId,
        sourceDurationMs = if (info.kind == MediaKind.IMAGE) 0 else info.durationMs,
        sourceStartMs = 0,
        sourceEndMs = dur,
        timelineStartMs = timelineStartMs,
        hasAudio = info.hasAudio,
        aspect = info.aspect,
    )
}

/** Project Management: projects and their editing decisions (Room). */
class ProjectRepository(private val db: AppDatabase) {
    private val dao = db.projects()

    fun observeProjects(): Flow<List<ProjectEntity>> = dao.observeAll()

    suspend fun get(id: Long): ProjectEntity? = dao.get(id)

    /** The first playable clip (inside a compound if the project starts with one) - for the cover. */
    suspend fun firstMainClip(id: Long): Clip? = dao.firstMainClip(id)?.toClip()?.let { if (it.isCompound) Compound.pieces(it, 1f).firstOrNull() ?: Compound.firstLeaf(it) else it }

    /**
     * Deletes a project and its editing decisions. The original videos / photos are not
     * touched; library files that were only kept for this project are cleaned up by the caller.
     */
    suspend fun delete(id: Long) = dao.delete(id)

    /** Creates a project whose main track holds [media] in the order it was picked. */
    suspend fun create(media: List<MediaInfo>): Long {
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(now))
        val id = dao.insert(ProjectEntity(name = "Project ${dao.count() + 1} · $stamp", createdAt = now, updatedAt = now))
        val timeline = TimelineOps.appendMain(Timeline(), media.map { clipFrom(it, Track.MAIN) })
        save(id, timeline)
        return id
    }

    suspend fun load(id: Long): Timeline = Timeline(dao.clips(id).map { it.toClip() })

    suspend fun save(id: Long, timeline: Timeline) {
        dao.replaceClips(id, timeline.clips.map { it.toEntity(id) }, System.currentTimeMillis())
    }
}

enum class LibraryKind(val folder: String, val title: String, val addLabel: String, val mime: String) {
    VIDEO_FX("video_fx", "Video FX", "Add Video", "video/*"),
    SOUND_FX("sound_fx", "Sound FX", "Add Sound", "audio/*"),
    MUSIC("music", "BG MUSIC", "Add Music", "audio/*"),
}

data class RemoveCheck(val item: LibraryItemEntity, val usedInProjects: List<String>)

/**
 * Video FX / Sound FX / Background Music libraries.
 *
 * Every imported file is COPIED into filesDir/library/<kind>/ (app-private, persistent, not
 * cache). The Gallery original is never referenced again, so deleting it does not affect the
 * library. A library file is deleted only through [remove] (explicit user action).
 */
class LibraryRepository(private val context: Context, db: AppDatabase) {
    private val dao = db.library()

    private fun dir(kind: LibraryKind): File =
        File(context.filesDir, "library/${kind.folder}").apply { mkdirs() }

    fun file(item: LibraryItemEntity): File = File(dir(LibraryKind.valueOf(item.kind)), item.fileName)

    fun observe(kind: LibraryKind): Flow<List<LibraryItemEntity>> = dao.observe(kind.name)

    /** Copies each picked file into private storage. Returns how many were imported. */
    suspend fun import(kind: LibraryKind, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        var imported = 0
        for (uri in uris) {
            val source = MediaProbe.probe(context, uri) ?: continue
            val wantedKind = if (kind == LibraryKind.VIDEO_FX) MediaKind.VIDEO else MediaKind.AUDIO
            if (source.kind != wantedKind) continue
            val ext = source.name.substringAfterLast('.', "").lowercase().take(5)
                .ifEmpty { if (wantedKind == MediaKind.VIDEO) "mp4" else "m4a" }
            val target = File(dir(kind), "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.$ext")
            val copied = try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                    true
                } ?: false
            } catch (e: Exception) {
                false
            }
            if (!copied || target.length() == 0L) {
                target.delete()
                continue
            }
            // Probe the private copy so durations describe the file the app will really use.
            val info = MediaProbe.probe(context, Uri.fromFile(target)) ?: source
            dao.insert(
                LibraryItemEntity(
                    kind = kind.name,
                    name = source.name.substringBeforeLast('.').ifEmpty { source.name },
                    fileName = target.name,
                    mimeType = source.mimeType,
                    durationMs = if (info.durationMs > 0) info.durationMs else source.durationMs,
                    hasAudio = info.hasAudio,
                    aspect = info.aspect,
                    addedAt = System.currentTimeMillis(),
                ),
            )
            imported++
        }
        imported
    }

    /** A new, empty file inside this library's private folder (for audio the app creates itself). */
    fun newFile(kind: LibraryKind, extension: String): File =
        File(dir(kind), "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.$extension")

    /** Registers a file that already lies in the library folder (see [newFile]). */
    suspend fun addFile(kind: LibraryKind, name: String, file: File, mimeType: String?): Boolean =
        withContext(Dispatchers.IO) {
            val info = MediaProbe.probe(context, Uri.fromFile(file))
            if (info == null || info.durationMs <= 0) {
                file.delete()
                return@withContext false
            }
            dao.insert(
                LibraryItemEntity(
                    kind = kind.name,
                    name = name,
                    fileName = file.name,
                    mimeType = mimeType,
                    durationMs = info.durationMs,
                    hasAudio = info.hasAudio,
                    aspect = info.aspect,
                    addedAt = System.currentTimeMillis(),
                ),
            )
            true
        }

    suspend fun rename(id: Long, name: String) {
        val clean = name.trim()
        if (clean.isNotEmpty()) dao.rename(id, clean)
    }

    /** Which projects still use this item - shown in the confirmation before removal. */
    suspend fun checkRemove(id: Long): RemoveCheck? {
        val item = dao.get(id) ?: return null
        return RemoveCheck(item, dao.projectsUsing(id))
    }

    /**
     * Explicit removal. If no project uses the item, the file and its record are deleted now.
     * If a project uses it, the item disappears from the library but the file is kept so the
     * project has no broken media reference; it is deleted once nothing references it.
     */
    suspend fun remove(id: Long) = withContext(Dispatchers.IO) {
        val item = dao.get(id) ?: return@withContext
        if (dao.usageCount(id) == 0) {
            file(item).delete()
            dao.delete(id)
        } else {
            dao.markRemoved(id)
        }
    }

    /** Deletes files of items the user already removed once no project references them. */
    suspend fun cleanUpRemoved() = withContext(Dispatchers.IO) {
        for (item in dao.removedItems()) {
            if (dao.usageCount(item.id) == 0) {
                file(item).delete()
                dao.delete(item.id)
            }
        }
    }

    fun toMediaInfo(item: LibraryItemEntity): MediaInfo = MediaInfo(
        uri = Uri.fromFile(file(item)),
        name = item.name,
        mimeType = item.mimeType,
        kind = if (item.kind == LibraryKind.VIDEO_FX.name) MediaKind.VIDEO else MediaKind.AUDIO,
        durationMs = item.durationMs,
        hasAudio = item.hasAudio,
        aspect = item.aspect,
    )
}
