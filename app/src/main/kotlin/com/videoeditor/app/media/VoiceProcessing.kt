package com.videoeditor.app.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Process
import android.util.Log
import com.videoeditor.core.NoiseProfile
import com.videoeditor.core.NoiseProfiler
import com.videoeditor.core.Resampler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext

/**
 * The one-time "Processing" of Voice enhance (like Edits): the whole sound of a video / audio
 * file is decoded once and its noise is learned ([NoiseProfiler]). The result is small, kept in
 * memory and on disk (so it never has to happen again for that file), and used by the preview
 * and the export. Runs on its own low-priority thread, one file after another, so playback and
 * editing go on undisturbed.
 */
object VoiceProcessing {
    private const val TAG = "VoiceProcessing"

    private val profiles = ConcurrentHashMap<String, NoiseProfile>()
    /** Files that have no usable sound (no audio track, unreadable, silent). */
    private val nothing = ConcurrentHashMap.newKeySet<String>()
    private val jobs = HashMap<String, CompletableDeferred<NoiseProfile?>>()

    private val _decoding = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** Files being processed (decoded and analysed) now -> progress 0..1. */
    val decoding: StateFlow<Map<String, Float>> = _decoding

    private val _version = MutableStateFlow(0)
    /** Goes up whenever a file's processing finished (players pick up the new profiles). */
    val version: StateFlow<Int> = _version

    private val thread = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "voice-processing")
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + thread)

    /** For tests: processing takes this many seconds per second of sound at least (0 = full speed). */
    @Volatile
    var testSlowdown = 0f

    /** For tests: how many files were really decoded (not taken from memory or disk). */
    @Volatile
    var decodedFiles = 0
        private set

    /** The learned noise of [uri], if it is known already (memory only, never blocks). */
    fun profile(uri: String): NoiseProfile? = profiles[uri]

    /** Whether [uri] still needs processing (not known, not found to be silent). */
    fun needs(uri: String): Boolean = profiles[uri] == null && uri !in nothing

    /**
     * Makes sure [uri] gets processed: from memory, else from the disk cache, else decoded and
     * analysed. Calling it again while it runs returns the same job.
     */
    fun request(context: Context, uri: String): Deferred<NoiseProfile?> {
        profiles[uri]?.let { return CompletableDeferred(it) }
        if (uri in nothing) return CompletableDeferred(null)
        synchronized(jobs) {
            jobs[uri]?.let { return it }
            val job = CompletableDeferred<NoiseProfile?>()
            jobs[uri] = job
            val app = context.applicationContext
            scope.launch {
                val result = try {
                    fromDisk(app, uri) ?: process(app, uri)
                } catch (e: Throwable) {
                    Log.w(TAG, "processing $uri failed", e)
                    null
                }
                if (result != null) profiles[uri] = result else nothing += uri
                synchronized(jobs) { jobs.remove(uri) }
                _decoding.value = _decoding.value - uri
                _version.value = _version.value + 1
                job.complete(result)
            }
            return job
        }
    }

    suspend fun ensure(context: Context, uri: String): NoiseProfile? = request(context, uri).await()

    /** Forgets what is in memory (tests: the next request has to come from disk). */
    fun clearMemory() {
        profiles.clear()
        nothing.clear()
        isolated.clear()
        noVoice.clear()
    }

    // ---- Isolate voice ------------------------------------------------------------------------

    private val isolated = ConcurrentHashMap<String, File>()
    /** Files whose voice cannot be isolated (no sound, or this build has no isolation engine). */
    private val noVoice = ConcurrentHashMap.newKeySet<String>()
    private val isoJobs = HashMap<String, CompletableDeferred<File?>>()

    /** The isolated voice of [uri] (48 kHz mono WAV, aligned to the file's own time), if ready. */
    fun isolatedVoice(uri: String): File? = isolated[uri]

    fun needsIsolation(uri: String): Boolean = isolated[uri] == null && uri !in noVoice

    /** Whether this build can isolate voices at all (the neural network is in the APK). */
    val isolationAvailable: Boolean get() = VoiceIsolationNative.loaded

    /** For tests: files really run through the network (not taken from disk). */
    @Volatile
    var isolatedFiles = 0
        private set

    /** Makes sure [uri]'s voice gets isolated (from memory, else disk, else worked out). */
    fun requestIsolation(context: Context, uri: String): Deferred<File?> {
        isolated[uri]?.let { return CompletableDeferred(it) }
        if (uri in noVoice) return CompletableDeferred(null)
        synchronized(isoJobs) {
            isoJobs[uri]?.let { return it }
            val job = CompletableDeferred<File?>()
            isoJobs[uri] = job
            val app = context.applicationContext
            scope.launch {
                val result = try {
                    isolatedFromDisk(app, uri) ?: isolate(app, uri)
                } catch (e: Throwable) {
                    Log.w(TAG, "isolating $uri failed", e)
                    null
                }
                if (result != null) isolated[uri] = result else noVoice += uri
                synchronized(isoJobs) { isoJobs.remove(uri) }
                _decoding.value = _decoding.value - uri
                _version.value = _version.value + 1
                job.complete(result)
            }
            return job
        }
    }

    suspend fun ensureIsolation(context: Context, uri: String): File? = requestIsolation(context, uri).await()

    private fun isolatedFile(context: Context, uri: String): File? {
        val f = cacheFile(context, uri) ?: return null
        return File(File(context.filesDir, "isolated_voice"), f.name.removeSuffix(".nnp") + "_dfn3.wav")
    }

    private fun isolatedFromDisk(context: Context, uri: String): File? {
        val f = isolatedFile(context, uri) ?: return null
        return if (f.exists() && f.length() > 44) f else null
    }

    private suspend fun isolate(context: Context, uri: String): File? {
        if (!VoiceIsolationNative.loaded) return null
        val out = isolatedFile(context, uri) ?: return null
        out.parentFile?.mkdirs()
        _decoding.value = _decoding.value + (uri to 0f)
        isolatedFiles++
        val writer = IsolationWriter(VoiceIsolationNative.model(context), File(out.parentFile, out.name + ".tmp"))
        try {
            var resampler: Resampler? = null
            var channels = 1
            var mono = FloatArray(0)
            var containerUs = 0L
            var started = false
            val ok = decodeAudio(context, Uri.parse(uri), onFormat = { rate, ch ->
                if (resampler == null) {
                    resampler = Resampler(rate, IsolationWriter.RATE)
                    channels = ch
                    true
                } else resampler!!.inRate == rate && channels == ch
            }, onPcm = { pcm, frames, ptsUs ->
                if (!started) {
                    started = true
                    // sample 0 of the result is time 0 of the file: silence before the first sound
                    writer.start(ptsUs)
                }
                if (mono.size < frames) mono = FloatArray(frames)
                for (f in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until channels) sum += pcm[f * channels + c]
                    mono[f] = sum / channels
                }
                writer.write(resampler!!.feed(mono, frames))
            }, onProgress = { p -> _decoding.value = _decoding.value + (uri to p) }, onDuration = { containerUs = it })
            if (!ok || !started) {
                writer.abandon()
                return null
            }
            writer.write(resampler!!.finish())
            writer.finish(containerUs)
            writer.close()
            out.delete()
            if (!writer.target.renameTo(out)) return null
            Log.i(TAG, "isolated voice of $uri: ${out.length() / 96_000} s")
            return out
        } catch (e: Throwable) {
            writer.abandon()
            throw e
        } finally {
            writer.close()
        }
    }

    /** Removes the disk cache as well (tests). */
    fun clearAll(context: Context) {
        clearMemory()
        dir(context).deleteRecursively()
        File(context.filesDir, "isolated_voice").deleteRecursively()
    }

    // ---- disk cache ---------------------------------------------------------------------------

    private fun dir(context: Context) = File(context.filesDir, "voice_profiles")

    /** Cache file name: the source and its size (a changed file is processed again). */
    private fun cacheFile(context: Context, uri: String): File? {
        val size = try {
            context.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { it.length } ?: return null
        } catch (e: Exception) {
            return null
        }
        val digest = MessageDigest.getInstance("SHA-1").digest("$uri|$size|v1".toByteArray())
        return File(dir(context), digest.joinToString("") { "%02x".format(it) } + ".nnp")
    }

    private fun fromDisk(context: Context, uri: String): NoiseProfile? {
        val f = cacheFile(context, uri) ?: return null
        if (!f.exists()) return null
        return NoiseProfile.decode(f.readBytes())
    }

    private fun toDisk(context: Context, uri: String, profile: NoiseProfile) {
        val f = cacheFile(context, uri) ?: return
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(profile.encode())
        tmp.renameTo(f)
    }

    // ---- processing ---------------------------------------------------------------------------

    private suspend fun process(context: Context, uri: String): NoiseProfile? {
        _decoding.value = _decoding.value + (uri to 0f)
        decodedFiles++
        var profiler: NoiseProfiler? = null
        val ok = decodeAudio(context, Uri.parse(uri), onFormat = { rate, channels ->
            if (profiler == null) profiler = NoiseProfiler(rate, channels)
            profiler!!.sampleRate == rate && profiler!!.channels == channels
        }, onPcm = { pcm, frames, _ -> profiler?.feed(pcm, frames) }, onProgress = { p ->
            _decoding.value = _decoding.value + (uri to p)
        })
        val profile = if (ok) profiler?.finish() else null
        if (profile != null) toDisk(context, uri, profile)
        Log.i(TAG, "processed $uri: ${profile?.let { "noise %.1f dBFS".format(it.levelDb) } ?: "no usable sound"}")
        return profile
    }

    /**
     * Decodes the whole first audio track of [uri] at its own rate and channels. [onFormat] is
     * told the format (return false to stop: the format changed half way); [onPcm] gets
     * interleaved floats. Returns false when there is no audio track.
     */
    internal suspend fun decodeAudio(
        context: Context,
        uri: Uri,
        onFormat: (rate: Int, channels: Int) -> Boolean,
        onPcm: (pcm: FloatArray, frames: Int, ptsUs: Long) -> Unit,
        onProgress: (Float) -> Unit,
        onDuration: (containerUs: Long) -> Unit = {},
    ): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            val format = extractor.getTrackFormat(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            // the longest track (the video may run a little longer than its sound)
            onDuration((0 until extractor.trackCount).maxOf {
                val f = extractor.getTrackFormat(it)
                if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
            })
            extractor.selectTrack(track)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmFloat = false
            var formatTold = false
            var floats = FloatArray(0)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastProgress = -1f
            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val i = decoder.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = decoder.getInputBuffer(i)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = decoder.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = decoder.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    pcmFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    if (formatTold && !onFormat(rate, channels)) return true
                } else if (o >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    val bytes = if (pcmFloat) 4 else 2
                    val frames = info.size / (bytes * channels)
                    if (frames > 0) {
                        if (!formatTold) {
                            formatTold = true
                            if (!onFormat(rate, channels)) return true
                        }
                        val buf = decoder.getOutputBuffer(o)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.order(ByteOrder.nativeOrder())
                        if (floats.size < frames * channels) floats = FloatArray(frames * channels)
                        if (pcmFloat) buf.asFloatBuffer().get(floats, 0, frames * channels)
                        else {
                            val sb = buf.asShortBuffer()
                            for (k in 0 until frames * channels) floats[k] = sb.get(k) / 32768f
                        }
                        onPcm(floats, frames, info.presentationTimeUs)
                        if (testSlowdown > 0f) kotlinx.coroutines.delay((frames * 1000L * testSlowdown / rate).toLong())
                        if (durationUs > 0) {
                            val p = (info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
                            if (p - lastProgress >= 0.01f) {
                                lastProgress = p
                                onProgress(p)
                            }
                        }
                    }
                    decoder.releaseOutputBuffer(o, false)
                }
            }
            onProgress(1f)
            return true
        } finally {
            try {
                codec?.stop()
            } catch (e: Exception) {
            }
            try {
                codec?.release()
            } catch (e: Exception) {
            }
            extractor.release()
        }
    }
}
