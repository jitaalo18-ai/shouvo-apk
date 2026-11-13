package com.videoeditor.app.captions

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Speech-recognition models for Auto Captions. They are too big for the APK, so they are
 * downloaded once (from this project's GitHub release "models-v1") into app-private storage.
 */
enum class CaptionLanguage(val label: String, val code: String, val model: CaptionModel) {
    BANGLA("বাংলা", "bn", CaptionModel.BANGLA_TURBO),
    ENGLISH("English", "en", CaptionModel.SMALL),
    AUTO("Other / auto", "auto", CaptionModel.SMALL),
}

enum class CaptionModel(
    val title: String,
    val fileName: String,
    val approxBytes: Long,
    /** longest caption piece in UTF-8 bytes (Bangla letters take 3 bytes) */
    val maxLen: Int,
    val beamSize: Int,
) {
    BANGLA_TURBO("Bangla (Whisper Turbo, trained on Bangla)", "ggml-turbo-bn-q5_0.bin", 574_000_000L, 72, 3),
    SMALL("Whisper Small (English and 98 other languages)", "ggml-small-q5_1.bin", 190_000_000L, 42, 3),
    ;

    val url: String get() = "$RELEASE/$fileName"

    companion object {
        const val RELEASE = "https://github.com/jitaalo18-ai/shouvo-apk/releases/download/models-v1"
        const val VAD_FILE = "ggml-silero-v6.2.0.bin"
    }
}

object CaptionModels {
    fun dir(context: Context): File = File(context.filesDir, "models").apply { mkdirs() }

    fun file(context: Context, model: CaptionModel): File = File(dir(context), model.fileName)

    fun vadFile(context: Context): File = File(dir(context), CaptionModel.VAD_FILE)

    /** A complete model file is there (size and ggml magic number checked). */
    fun isReady(context: Context, model: CaptionModel): Boolean = looksLikeModel(file(context, model))

    fun looksLikeModel(f: File): Boolean {
        if (!f.isFile || f.length() < 10_000_000L) return false
        return try {
            RandomAccessFile(f, "r").use { raf ->
                val b = ByteArray(4)
                raf.readFully(b)
                // "lmgg" = 0x67676d6c little-endian (ggml)
                b[0] == 0x6c.toByte() && b[1] == 0x6d.toByte() && b[2] == 0x67.toByte() && b[3] == 0x67.toByte()
            }
        } catch (e: Exception) {
            false
        }
    }

    /** The small voice-activity model is a ggml file too, just much smaller. */
    fun looksLikeVad(f: File): Boolean = f.isFile && f.length() > 100_000L

    fun delete(context: Context, model: CaptionModel) {
        file(context, model).delete()
        File(dir(context), model.fileName + ".part").delete()
    }

    /**
     * Downloads [model] (continuing a download that was interrupted). [onProgress] gets
     * (bytes so far, total bytes). Throws on network errors; cancellation stops it.
     */
    suspend fun download(context: Context, model: CaptionModel, onProgress: (Long, Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val target = file(context, model)
            if (looksLikeModel(target)) return@withContext
            fetch(model.url, target, onProgress)
            if (!looksLikeModel(target)) {
                target.delete()
                throw IllegalStateException("The downloaded file is not a speech model")
            }
        }

    private suspend fun fetch(url: String, target: File, onProgress: (Long, Long) -> Unit) {
        val part = File(target.parentFile, target.name + ".part")
        var have = if (part.isFile) part.length() else 0L
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = conn.responseCode
            if (code == 200) have = 0                     // server ignored the range: start over
            else if (code != 206) throw IllegalStateException("Download failed (HTTP $code)")
            val total = have + conn.contentLengthLong.coerceAtLeast(0)
            conn.inputStream.use { input ->
                RandomAccessFile(part, "rw").use { out ->
                    out.setLength(have)
                    out.seek(have)
                    val buf = ByteArray(256 * 1024)
                    var done = have
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 1_000_000) {
                            lastReport = done
                            onProgress(done, total)
                        }
                    }
                    onProgress(done, total)
                }
            }
        } finally {
            conn.disconnect()
        }
        if (!part.renameTo(target)) throw IllegalStateException("Could not save the model")
    }
}
