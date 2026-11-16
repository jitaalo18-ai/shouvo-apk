package com.videoeditor.app.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.text.Html
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

data class SearchImage(val url: String, val thumbnail: String, val title: String, val source: String)

/** Public image-result metadata. No browser UI, credentials or script bridge. */
object ImageSearch {
    fun parse(html: String): List<SearchImage> = Regex("\\bm=\"([^\"]+)\"").findAll(html).mapNotNull { match ->
        try {
            val j = JSONObject(Html.fromHtml(match.groupValues[1], Html.FROM_HTML_MODE_LEGACY).toString())
            val url = j.optString("murl")
            val thumb = j.optString("turl")
            if (!url.startsWith("https://") || !thumb.startsWith("https://")) null
            else SearchImage(url, thumb, j.optString("t", "Image"), j.optString("purl"))
        } catch (_: Exception) { null }
    }.distinctBy { it.url }.toList()

    suspend fun search(query: String, offset: Int = 0): List<SearchImage> = withContext(Dispatchers.IO) {
        val url = URL("https://www.bing.com/images/async?q=${Uri.encode(query.trim())}&first=$offset&count=35&adlt=moderate")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000; connection.readTimeout = 20000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
            check(connection.responseCode == 200) { "Image search is unavailable. Please retry." }
            val bytes = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16384)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buffer); if (n < 0) break
                    check(bytes.size() + n <= 5 * 1024 * 1024) { "Search response is too large." }
                    bytes.write(buffer, 0, n)
                }
            }
            val results = parse(bytes.toString("UTF-8"))
            check(results.isNotEmpty()) { "No images returned. Try another keyword or retry later." }
            results
        } finally { connection.disconnect() }
    }

    /** Publishes only complete files. A failed/cancelled write removes the pending row. */
    suspend fun downloadToGallery(context: Context, file: File): Uri = withContext(Dispatchers.IO) {
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension) ?: "image/jpeg"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "NockNock_${System.currentTimeMillis()}.${file.extension}")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NockNock")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Could not create download.")
        try {
            resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { coroutineContext.ensureActive(); val n=input.read(buffer); if(n<0) break; output.write(buffer,0,n) }
            } } ?: error("Could not write download.")
            coroutineContext.ensureActive()
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) { resolver.delete(uri,null,null); throw e }
    }
}
