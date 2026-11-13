package com.videoeditor.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import com.videoeditor.core.MediaKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.TreeMap

/**
 * Thumbnail engine for the timeline, libraries and project list.
 *
 * Why it is built like this (the timeline used to stay grey while scrolling / playing):
 *  - One MediaMetadataRetriever per video file is kept open and reused (opening a file for every
 *    frame was the slow part).
 *  - Work runs in the engine's own scope: a frame that was requested is finished and cached even
 *    if the tile scrolled away in the meantime, so nothing is decoded twice.
 *  - Newest requests are served first (what is on screen now), the queue is bounded.
 *  - Frames are written to a small disk cache, so a reopened project fills instantly.
 *  - [nearest] returns the closest already-known frame of the same file, used as a placeholder.
 */
object Thumbnails {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val memory = object : LruCache<String, Bitmap>(40 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    /** uri -> (time -> memory key) of frames decoded so far, for placeholders. */
    private val known = HashMap<String, TreeMap<Long, String>>()
    private val inFlight = HashMap<String, CompletableDeferred<Bitmap?>>()
    private val queue = ArrayDeque<Request>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private const val MAX_QUEUE = 160
    private const val WORKERS = 3

    /** Number of frames decoded (not from cache) - lets tests see that work is happening. */
    val decodedCount = MutableStateFlow(0)

    private class Request(
        val context: Context,
        val key: String,
        val uri: String,
        val kind: MediaKind,
        val timeMs: Long,
        val size: Int,
        val exact: Boolean,
        val result: CompletableDeferred<Bitmap?>,
    )

    init {
        repeat(WORKERS) {
            scope.launch {
                while (true) {
                    val r = synchronized(queue) { queue.removeLastOrNull() }
                    if (r == null) {
                        wake.receive()
                        continue
                    }
                    val bmp = try {
                        produce(r)
                    } catch (e: Exception) {
                        null
                    }
                    finish(r, bmp)
                }
            }
        }
    }

    /**
     * Timeline grid: thumbnail times are rounded to a step that only depends on how much time a
     * tile covers, so zooming reuses frames that are already decoded.
     */
    fun gridMs(tileMs: Long): Long {
        val steps = longArrayOf(500, 1_000, 2_000, 5_000, 10_000, 20_000, 30_000, 60_000, 120_000)
        return steps.firstOrNull { it * 2 >= tileMs } ?: 300_000L
    }

    private fun key(uri: String, kind: MediaKind, timeMs: Long, size: Int, exact: Boolean) =
        if (kind == MediaKind.IMAGE) "$uri@img@$size" else "$uri@$timeMs@$size@${if (exact) "x" else "s"}"

    /** Frame already in memory, or null. */
    fun peek(uri: String, kind: MediaKind, timeMs: Long, size: Int = 160, exact: Boolean = false): Bitmap? =
        memory.get(key(uri, kind, timeMs, size, exact))

    /** Closest frame of the same file that is already in memory (any time), or null. */
    fun nearest(uri: String, timeMs: Long, size: Int = 160): Bitmap? {
        val map = synchronized(known) { known[uri]?.let { TreeMap(it) } } ?: return null
        val lo = map.floorEntry(timeMs)
        val hi = map.ceilingEntry(timeMs)
        val best = listOfNotNull(lo, hi).minByOrNull { kotlin.math.abs(it.key - timeMs) } ?: return null
        return memory.get(best.value)?.takeIf { it.width >= size / 2 || it.height >= size / 2 }
    }

    /**
     * Frame of a video at [timeMs] (closest key frame unless [exact]) or a scaled-down image.
     * The work is not cancelled when the caller is; the result stays cached.
     */
    suspend fun load(
        context: Context,
        uri: String,
        kind: MediaKind,
        timeMs: Long,
        size: Int = 160,
        exact: Boolean = false,
    ): Bitmap? {
        if (kind == MediaKind.AUDIO || kind == MediaKind.COMPOUND) return null
        val k = key(uri, kind, timeMs, size, exact)
        memory.get(k)?.let { return it }
        val deferred = synchronized(queue) {
            inFlight[k] ?: CompletableDeferred<Bitmap?>().also { d ->
                inFlight[k] = d
                queue.addLast(Request(context.applicationContext, k, uri, kind, timeMs, size, exact, d))
                while (queue.size > MAX_QUEUE) {
                    val dropped = queue.removeFirst()
                    inFlight.remove(dropped.key)
                    dropped.result.complete(null)
                }
            }
        }
        wake.trySend(Unit)
        return deferred.await()
    }

    private fun finish(r: Request, bmp: Bitmap?) {
        if (bmp != null) {
            memory.put(r.key, bmp)
            if (r.kind == MediaKind.VIDEO && !r.exact) {
                synchronized(known) { known.getOrPut(r.uri) { TreeMap() }[r.timeMs] = r.key }
            }
        }
        synchronized(queue) { inFlight.remove(r.key) }
        r.result.complete(bmp)
    }

    private suspend fun produce(r: Request): Bitmap? {
        diskRead(r)?.let { return it }
        val bmp = when (r.kind) {
            MediaKind.IMAGE -> decodeImage(r.context, Uri.parse(r.uri), r.size)
            MediaKind.VIDEO -> Retrievers.frame(r.context, r.uri, r.timeMs, r.size, r.exact)
            MediaKind.AUDIO, MediaKind.COMPOUND -> null
        } ?: return null
        decodedCount.value = decodedCount.value + 1
        diskWrite(r, bmp)
        return bmp
    }

    // ---- disk cache ---------------------------------------------------------------------

    private fun diskFile(r: Request): File {
        val dir = File(r.context.cacheDir, "thumbs").apply { mkdirs() }
        val digest = MessageDigest.getInstance("SHA-1").digest(r.key.toByteArray())
        return File(dir, digest.joinToString("") { "%02x".format(it) } + ".jpg")
    }

    private fun diskRead(r: Request): Bitmap? {
        if (r.exact) return null
        val f = diskFile(r)
        if (!f.exists()) return null
        return try {
            BitmapFactory.decodeFile(f.absolutePath)
        } catch (e: Exception) {
            null
        }
    }

    private fun diskWrite(r: Request, bmp: Bitmap) {
        if (r.exact) return
        try {
            diskFile(r).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        } catch (e: Exception) {
            // cache only
        }
    }

    /** For tests: forget everything kept in memory (the disk cache stays). */
    fun clearMemory() {
        memory.evictAll()
        synchronized(known) { known.clear() }
    }

    // ---- helpers used elsewhere ---------------------------------------------------------

    /**
     * A thumbnail that shows what a video looks like. Many clips start with a black frame, so
     * frames from the middle of the clip are tried and the first clearly visible one (or else
     * the brightest) is used.
     */
    suspend fun representative(context: Context, uri: String, durationMs: Long, maxSize: Int = 160): Bitmap? {
        val k = "$uri@rep@$maxSize"
        memory.get(k)?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            var best: Bitmap? = null
            var bestBrightness = -1f
            for (fraction in floatArrayOf(0.5f, 0.25f, 0.75f, 0.1f)) {
                val frame = Retrievers.frame(context, uri, (durationMs * fraction).toLong(), maxSize, exact = true)
                    ?: continue
                val b = brightness(frame)
                if (b > bestBrightness) {
                    best = frame
                    bestBrightness = b
                }
                if (b > 40f) break
            }
            best
        }
        if (bmp != null) memory.put(k, bmp)
        return bmp
    }

    /** Average brightness (0..255) of a bitmap, sampled on an 8 x 8 grid. */
    private fun brightness(bitmap: Bitmap): Float {
        var sum = 0L
        var count = 0
        for (gy in 0 until 8) {
            for (gx in 0 until 8) {
                val x = (bitmap.width * (gx + 0.5f) / 8f).toInt().coerceIn(0, bitmap.width - 1)
                val y = (bitmap.height * (gy + 0.5f) / 8f).toInt().coerceIn(0, bitmap.height - 1)
                val c = bitmap.getPixel(x, y)
                sum += ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
                count += 3
            }
        }
        return if (count == 0) 0f else sum.toFloat() / count
    }

    fun decodeImage(context: Context, uri: Uri, maxSize: Int): Bitmap? {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val scale = maxSize.toFloat() / maxOf(w, h)
            if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
        }
    }
}

/**
 * Open MediaMetadataRetrievers, one per file, reused for every frame of that file (at most a few
 * files open at once). Each retriever is used by one thread at a time.
 */
object Retrievers {
    private class Entry(val retriever: MediaMetadataRetriever, val lock: Mutex = Mutex())

    private val open = object : LinkedHashMap<String, Entry>(8, 0.75f, true) {}
    private val failed = HashSet<String>()
    private const val MAX_OPEN = 4

    suspend fun frame(context: Context, uri: String, timeMs: Long, size: Int, exact: Boolean): Bitmap? {
        val entry = synchronized(open) {
            if (uri in failed) return null
            open[uri] ?: run {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(context, Uri.parse(uri))
                } catch (e: Exception) {
                    try {
                        r.release()
                    } catch (ignored: Exception) {
                        // ignore
                    }
                    failed.add(uri)
                    return null
                }
                Entry(r).also {
                    open[uri] = it
                    while (open.size > MAX_OPEN) {
                        val eldest = open.entries.first()
                        open.remove(eldest.key)
                        closeLater(eldest.value)
                    }
                }
            }
        }
        return entry.lock.withLock {
            try {
                val option = if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                entry.retriever.getScaledFrameAtTime(timeMs.coerceAtLeast(0) * 1000, option, size, size)
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun closeLater(e: Entry) {
        CoroutineScope(Dispatchers.IO).launch {
            e.lock.withLock {
                try {
                    e.retriever.release()
                } catch (ignored: Exception) {
                    // ignore
                }
            }
        }
    }
}

/**
 * Exact frames for the preview while a trim handle is dragged: only the newest request matters,
 * older ones are skipped instead of queued.
 */
object LiveFrames {
    data class Frame(val uri: String, val timeMs: Long, val bitmap: Bitmap)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = MutableStateFlow<Triple<Context, String, Long>?>(null)
    val frame = MutableStateFlow<Frame?>(null)

    init {
        scope.launch {
            requests.collect { r ->
                if (r == null) return@collect
                val (context, uri, timeMs) = r
                val bmp = Retrievers.frame(context, uri, timeMs, 720, exact = true)
                if (bmp != null && requests.value?.second == uri) frame.value = Frame(uri, timeMs, bmp)
            }
        }
    }

    fun request(context: Context, uri: String, timeMs: Long) {
        requests.value = Triple(context.applicationContext, uri, timeMs)
    }

    fun clear() {
        requests.value = null
        frame.value = null
    }
}
