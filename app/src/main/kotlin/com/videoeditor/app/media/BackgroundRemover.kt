package com.videoeditor.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * "Transparent image": removes the background of a picture on the phone and gives a PNG whose
 * background is see-through. The subject (people, animals, things) is found by Google's ML Kit
 * Subject Segmentation, which runs on the device; Google Play services downloads its model once
 * (a few MB, needs internet that first time).
 */
object BackgroundRemover {
    /** Longest side of the result (plenty for a video overlay; keeps memory in check). */
    const val MAX_SIDE = 2048

    /**
     * Cuts the subject out of a picture: same size in, the background fully transparent out.
     * Tests replace it; null = ML Kit.
     */
    @Volatile
    var cutter: (suspend (Context, Bitmap, (String) -> Unit) -> Bitmap)? = null

    class Failed(message: String) : Exception(message)

    /** Writes [source] without its background as a PNG into the cache and returns it. */
    suspend fun removeBackground(context: Context, source: File, onStage: (String) -> Unit = {}): File {
        val app = context.applicationContext
        val bitmap = withContext(Dispatchers.IO) { decode(source) }
        try {
            val cut = (cutter ?: ::mlKit)(app, bitmap, onStage)
            try {
                return withContext(Dispatchers.Default) {
                    if (!hasSubject(cut)) throw Failed("No subject was found in this picture.")
                    val out = File(app.cacheDir, "transparent_${System.nanoTime()}.png")
                    try {
                        out.outputStream().use { check(cut.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    } catch (e: Exception) {
                        out.delete()
                        throw Failed("Could not save the picture. Check free storage.")
                    }
                    out
                }
            } finally {
                if (cut !== bitmap) cut.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** The picture upright (EXIF), at most [MAX_SIDE] on its longer side, in memory the CPU can read. */
    private fun decode(source: File): Bitmap = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val w = info.size.width
            val h = info.size.height
            val longest = maxOf(w, h)
            if (longest > MAX_SIDE) {
                val scale = MAX_SIDE.toFloat() / longest
                decoder.setTargetSize(maxOf(1, Math.round(w * scale)), maxOf(1, Math.round(h * scale)))
            }
        }.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false).also { _ -> it.recycle() } }
    } catch (e: Exception) {
        throw Failed("This picture cannot be opened.")
    }

    /** Something is left (more than a few stray pixels) and it is not simply the whole picture gone. */
    internal fun hasSubject(cut: Bitmap): Boolean {
        val w = cut.width
        val h = cut.height
        val row = IntArray(w)
        var solid = 0L
        var seen = 0L
        val stepY = maxOf(1, h / 400)
        var y = 0
        while (y < h) {
            cut.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            val stepX = maxOf(1, w / 400)
            while (x < w) {
                if ((row[x] ushr 24) > 128) solid++
                seen++
                x += stepX
            }
            y += stepY
        }
        return seen > 0 && solid * 500 >= seen       // at least 0.2 % of the picture
    }

    // ---- ML Kit ------------------------------------------------------------------------------

    private suspend fun mlKit(context: Context, bitmap: Bitmap, onStage: (String) -> Unit): Bitmap {
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) {
            throw Failed("Transparent image needs Google Play services on this phone.")
        }
        val segmenter = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundBitmap().build())
        try {
            val modules = ModuleInstall.getClient(context)
            val ready = try {
                modules.areModulesAvailable(segmenter).await().areModulesAvailable()
            } catch (e: Exception) {
                false
            }
            if (!ready) {
                onStage("Downloading the background remover (first time only)…")
                val done = CompletableDeferred<Boolean>()
                val listener = InstallStatusListener { update ->
                    when (update.installState) {
                        ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> done.complete(true)
                        ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
                        ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> done.complete(false)
                        else -> update.progressInfo?.let { p ->
                            if (p.totalBytesToDownload > 0) {
                                onStage("Downloading the background remover… ${(100 * p.bytesDownloaded / p.totalBytesToDownload).toInt()}%")
                            }
                        }
                    }
                }
                try {
                    val response = try {
                        modules.installModules(ModuleInstallRequest.newBuilder().addApi(segmenter).setListener(listener).build()).await()
                    } catch (e: Exception) {
                        throw Failed("The background remover could not be downloaded. Check the internet connection and try again.")
                    }
                    val ok = response.areModulesAlreadyInstalled() || withTimeoutOrNull(INSTALL_TIMEOUT_MS) { done.await() } == true
                    if (!ok) throw Failed("The background remover could not be downloaded. Check the internet connection and try again.")
                } finally {
                    modules.unregisterListener(listener)
                }
            }
            onStage("Removing the background…")
            val result = try {
                segmenter.process(InputImage.fromBitmap(bitmap, 0)).await()
            } catch (e: Exception) {
                throw Failed("The background could not be removed from this picture.")
            }
            return result.foregroundBitmap ?: throw Failed("No subject was found in this picture.")
        } finally {
            segmenter.close()
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { if (cont.isActive) cont.resume(it) }
        addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }

    private const val INSTALL_TIMEOUT_MS = 180_000L
}
