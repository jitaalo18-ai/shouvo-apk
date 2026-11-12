package com.videoeditor.app.media

import android.content.Context
import android.view.TextureView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.videoeditor.core.Clip
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
 * - Sound effects, music and video overlays each get a small player that is started, stopped
 *   and re-synchronised against the main timeline position ~30 times per second.
 */
class PlaybackController(private val context: Context, scope: CoroutineScope) {
    val main: ExoPlayer = ExoPlayer.Builder(context).build()
    val positionMs = MutableStateFlow(0L)
    val isPlaying = MutableStateFlow(false)

    private var mainClips: List<Clip> = emptyList()
    private var mainSignature: List<String> = emptyList()
    private var auxClips: List<Clip> = emptyList()
    private val aux = HashMap<String, ExoPlayer>()
    private val overlayViews = HashMap<String, TextureView>()
    private var released = false

    private val ticker: Job = scope.launch {
        while (isActive) {
            tick()
            delay(33)
        }
    }

    val durationMs: Long get() = mainClips.sumOf { it.durationMs }

    fun setTimeline(timeline: Timeline) {
        if (released) return
        val newMain = timeline.main
        val signature = newMain.map { "${it.kind}|${it.sourceUri}|${it.sourceStartMs}|${it.sourceEndMs}" }
        mainClips = newMain
        if (signature != mainSignature) {
            mainSignature = signature
            val keep = positionMs.value
            main.setMediaItems(newMain.map { mainItem(it) })
            main.prepare()
            seekTo(keep)
        }
        syncAuxPlayers(timeline)
    }

    private fun mainItem(c: Clip): MediaItem {
        val b = MediaItem.Builder().setUri(c.sourceUri).setMediaId(c.id)
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
        val wanted = timeline.clips.filter { it.track != Track.MAIN && it.kind != MediaKind.IMAGE }
        val ids = wanted.map { it.id }.toSet()
        for (id in aux.keys.filter { it !in ids }) {
            aux.remove(id)?.release()
            overlayViews.remove(id)
        }
        for (c in wanted) {
            if (c.id !in aux) {
                val p = ExoPlayer.Builder(context).build()
                p.setMediaItem(MediaItem.fromUri(c.sourceUri))
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
        if (released || mainClips.isEmpty()) return
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
        if (released || mainClips.isEmpty()) return
        val ms = timelineMs.coerceIn(0, durationMs)
        val pos = TimelineOps.locate(Timeline(mainClips), ms) ?: return
        val inClip = (ms - pos.clip.timelineStartMs).coerceIn(0, (pos.clip.durationMs - 1).coerceAtLeast(0))
        main.seekTo(pos.index, inClip)
        positionMs.value = ms
    }

    private fun tick() {
        if (released || mainClips.isEmpty()) return
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
        main.release()
        aux.values.forEach { it.release() }
        aux.clear()
        overlayViews.clear()
    }
}
