package com.videoeditor.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Streams image downloads to a temporary file, checks their real format, then hands them to the library. */
object WebImages {
    const val MAX_BYTES = 30L * 1024 * 1024
    fun searchUrl(query: String, google: Boolean): String =
        if (google) "https://www.google.com/search?tbm=isch&q=${Uri.encode(query.trim())}"
        else "https://www.bing.com/images/search?q=${Uri.encode(query.trim())}"

    suspend fun preview(context: Context, uri: Uri, maxSize: Int = 1280): Bitmap? = withContext(Dispatchers.IO) {
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val ratio = maxSize.toFloat() / maxOf(info.size.width, info.size.height)
                if (ratio < 1) decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1), (info.size.height * ratio).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (_: Exception) { null }
    }

    suspend fun download(context: Context, address: String): File = withContext(Dispatchers.IO) {
        val temp = File(context.cacheDir, "image_${UUID.randomUUID()}.part")
        try {
            if (address.startsWith("data:image/")) {
                require(address.length < 6 * 1024 * 1024) { "Embedded image is too large. Open the original image before saving." }
                val split = address.indexOf(',')
                require(split > 0 && address.substring(0, split).endsWith(";base64")) { "Open the full image before saving." }
                temp.writeBytes(Base64.decode(address.substring(split + 1), Base64.DEFAULT))
            } else {
                var current = address
                var downloaded = false
                repeat(6) {
                    if (!downloaded) {
                        val url = URL(current)
                        require(url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null) { "Choose an HTTPS image link." }
                        val connection = url.openConnection() as HttpURLConnection
                        try {
                            connection.instanceFollowRedirects = false
                            connection.connectTimeout = 15000
                            connection.readTimeout = 15000
                            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) NockNock/0.23")
                            val status = connection.responseCode
                            if (status in 300..399) {
                                current = URL(url, connection.getHeaderField("Location") ?: error("Image redirect failed.")).toString()
                            } else {
                                check(status in 200..299) { "The website refused this download ($status). Try opening the original image." }
                                require(connection.contentLengthLong <= MAX_BYTES) { "Image is too large (30 MB maximum)." }
                                connection.inputStream.use { input -> temp.outputStream().use { output ->
                                    val buffer = ByteArray(64*1024)
                                    var total = 0L
                                    while (true) {
                                        coroutineContext.ensureActive()
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        total += n
                                        require(total <= MAX_BYTES) { "Image is too large (30 MB maximum)." }
                                        output.write(buffer, 0, n)
                                    }
                                } }
                                downloaded = true
                            }
                        } finally { connection.disconnect() }
                    }
                }
                check(downloaded) { "Too many redirects. Try another image." }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temp.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong()*bounds.outHeight <= 200_000_000) { "This link is not a supported image, or the image is too large." }
            val ext = when (bounds.outMimeType) {
                "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"
                "image/heif", "image/heic" -> "heic"; "image/avif" -> "avif"
                else -> error("Unsupported image format. Choose JPEG, PNG or WebP.")
            }
            val result = File(context.cacheDir, "Image_${UUID.randomUUID()}.$ext")
            check(temp.renameTo(result)) { "Could not save the downloaded image." }
            result
        } finally { temp.delete() }
    }
}
