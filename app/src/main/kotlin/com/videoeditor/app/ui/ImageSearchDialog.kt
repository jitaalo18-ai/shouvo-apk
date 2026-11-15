package com.videoeditor.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.videoeditor.app.App
import com.videoeditor.app.data.LibraryKind
import com.videoeditor.app.media.WebImages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ImageSearchDialog(app: App, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val prefs = remember { app.getSharedPreferences("image_search", 0) }
    var history by remember { mutableStateOf(try {
        val a = JSONArray(prefs.getString("history", "[]")); (0 until a.length()).map { a.getString(it) }
    } catch (_: Exception) { emptyList<String>() }) }
    var query by remember { mutableStateOf("") }
    var google by remember { mutableStateOf(true) }
    var page by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(100) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var candidate by remember { mutableStateOf<File?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var browser by remember { mutableStateOf<WebView?>(null) }
    fun storeHistory(values: List<String>) {
        history = values
        prefs.edit().putString("history", JSONArray(values).toString()).apply()
    }
    fun search(term: String = query) {
        if (term.isBlank()) return
        query = term.trim(); error = null
        storeHistory((listOf(query) + history.filterNot { it.equals(query, true) }).take(20))
        page = WebImages.searchUrl(query, google)
        browser?.loadUrl(page)
    }
    val selectImage: (String) -> Unit = { url ->
        if (!saving && candidate == null) {
            saving = true; error = null
            scope.launch {
                try {
                    val file = WebImages.download(app, url)
                    candidate = file
                    bitmap = WebImages.preview(app, Uri.fromFile(file))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "Could not download this image." }
                finally { saving = false }
            }
        }
    }
    val latestSelect by rememberUpdatedState(selectImage)
    DisposableEffect(Unit) {
        onDispose { candidate?.delete(); browser?.stopLoading(); browser?.destroy() }
    }
    Dialog(onDismissRequest = { if (!saving) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("imageSearchScreen"), color = Bg) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("Image Search", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(enabled = !saving, onClick = onDismiss) { Text("Close search") }
                }
                Row(Modifier.fillMaxWidth()) {
                    OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("Search images, logos, people…") }, modifier = Modifier.weight(1f).testTag("imageSearchQuery"))
                    TextButton(enabled = query.isNotBlank() && !saving, onClick = { search() }, modifier = Modifier.testTag("imageSearchGo")) { Text("Search") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = google, onClick = { google = true; if (query.isNotBlank()) { page = WebImages.searchUrl(query,true); browser?.loadUrl(page) } }, label = { Text("Google") })
                    FilterChip(selected = !google, onClick = { google = false; if (query.isNotBlank()) { page = WebImages.searchUrl(query,false); browser?.loadUrl(page) } }, label = { Text("Bing") })
                    TextButton(onClick = { browser?.let { if (it.canGoBack()) it.goBack() } }) { Text("Back") }
                    TextButton(onClick = { error = null; browser?.reload() }) { Text("Reload") }
                }
                Text("Open an image, then press and hold it to save a copy. Choose the full-size image for best quality.", style = MaterialTheme.typography.bodySmall)
                if (progress < 100 || saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (page.isBlank()) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Row(Modifier.fillMaxWidth()) {
                            Text("Search history", Modifier.weight(1f))
                            TextButton(onClick = { storeHistory(emptyList()) }) { Text("Clear history") }
                        }
                        for (term in history) {
                            Row {
                                TextButton(onClick = { search(term) }, modifier = Modifier.weight(1f)) { Text(term) }
                                TextButton(onClick = { storeHistory(history - term) }) { Text("Remove") }
                            }
                        }
                    }
                } else {
                    AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.safeBrowsingEnabled = true
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, value: Int) { progress = value }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.scheme != "https"
                                override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                                    if (request.isForMainFrame) { error = "Page could not load. Check your connection, reload, or try the other search provider."; progress = 100 }
                                }
                            }
                            setDownloadListener { url, _, _, _, _ -> latestSelect(url) }
                            setOnLongClickListener {
                                val hit = hitTestResult
                                if (hit.type == WebView.HitTestResult.IMAGE_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                                    val fallback = hit.extra
                                    val handler = Handler(Looper.getMainLooper()) { message ->
                                        (message.data.getString("src") ?: fallback)?.let { latestSelect(it) }
                                        true
                                    }
                                    requestFocusNodeHref(handler.obtainMessage())
                                    true
                                } else false
                            }
                            browser = this
                            loadUrl(page)
                        }
                    }, onRelease = { it.stopLoading(); it.destroy(); if (browser === it) browser = null })
                }
            }
        }
        candidate?.let { file ->
            AlertDialog(onDismissRequest = { if (!saving) { file.delete(); candidate = null; bitmap = null } },
                title = { Text("Save to Overlay Folder?") },
                text = { Column {
                    bitmap?.let { Image(it.asImageBitmap(), "Downloaded image preview", Modifier.fillMaxWidth().heightIn(max = 320.dp), contentScale = ContentScale.Fit) }
                    Text("A separate copy will stay in the app. Use images you have permission to reuse.")
                } },
                confirmButton = { TextButton(enabled = !saving, onClick = {
                    saving = true
                    scope.launch {
                        try {
                            val imported = app.library.importItems(LibraryKind.OVERLAYS, listOf(Uri.fromFile(file)))
                            check(imported.size == 1) { "Could not save this image. Check free storage." }
                            if (query.isNotBlank()) app.library.rename(imported.single().id, query.take(120))
                            file.delete(); candidate = null; bitmap = null; onSaved()
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message }
                        finally { saving = false }
                    }
                }) { Text(if (saving) "Saving…" else "Save image") } },
                dismissButton = { TextButton(enabled = !saving, onClick = { file.delete(); candidate = null; bitmap = null }) { Text("Cancel") } })
        }
    }
}
