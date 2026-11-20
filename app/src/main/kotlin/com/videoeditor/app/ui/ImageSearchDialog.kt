package com.videoeditor.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.videoeditor.app.App
import com.videoeditor.app.data.LibraryKind
import com.videoeditor.app.media.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import java.io.File

private val imageRequests = Semaphore(4)
private val thumbs = object : android.util.LruCache<String, Bitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
}

@Composable
private fun SearchThumbnail(app: App, item: SearchImage, modifier: Modifier = Modifier) {
    var retry by remember(item.thumbnail) { mutableIntStateOf(0) }
    var failed by remember(item.thumbnail) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(thumbs.get(item.thumbnail), item.thumbnail, retry) {
        if (value == null) imageRequests.withPermit {
            failed = false
            for (address in listOf(item.thumbnail, item.url).distinct()) {
                var downloaded: File? = null
                try {
                    downloaded = WebImages.download(app, address)
                    val decoded = WebImages.preview(app, Uri.fromFile(downloaded), 480)
                    if (decoded != null) {
                        value = decoded
                        thumbs.put(item.thumbnail, decoded)
                        break
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    android.util.Log.w("ImageSearch", "Preview download failed", error)
                } finally {
                    downloaded?.delete()
                }
            }
            failed = value == null
        }
    }
    val ratio = bitmap?.let { it.width.toFloat()/it.height }?.coerceIn(0.45f,2f) ?: 0.72f
    Box(modifier.fillMaxWidth().aspectRatio(ratio).background(Color(0xff292d30)), contentAlignment=Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(),item.title,Modifier.fillMaxSize().testTag("loadedSearchThumbnail"),contentScale=ContentScale.Crop) }
            ?: if (failed) IconButton(onClick = { retry++ }) {
                Icon(Icons.Default.Refresh, "Retry thumbnail", tint = Color.White)
            } else CircularProgressIndicator(Modifier.size(24.dp))
    }
}

@Composable
fun ImageSearchDialog(app: App, onDismiss: () -> Unit, onSaved: () -> Unit,
    searcher: suspend (String, Int) -> List<SearchImage> = { q, offset -> ImageSearch.search(q,offset) }) {
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        val keyboard = LocalSoftwareKeyboardController.current
        val scope = rememberCoroutineScope()
        val prefs = remember { app.getSharedPreferences("image_search",0) }
        var history by remember { mutableStateOf(try { val a=JSONArray(prefs.getString("history","[]")); (0 until a.length()).map { a.getString(it) } } catch(_:Exception) { emptyList<String>() }) }
        var query by remember { mutableStateOf("") }
        var submitted by remember { mutableStateOf("") }
        var results by remember { mutableStateOf(emptyList<SearchImage>()) }
        var selected by remember { mutableStateOf<SearchImage?>(null) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var job by remember { mutableStateOf<Job?>(null) }
        var more by remember { mutableStateOf(true) }
        fun search(loadMore: Boolean = false) {
            val term = if(loadMore) submitted else query.trim()
            if(term.isBlank()) return
            keyboard?.hide(); job?.cancel()
            if(!loadMore) { results=emptyList(); submitted=term; more=true; history=(listOf(term)+history.filterNot { it==term }).take(20); prefs.edit().putString("history",JSONArray(history).toString()).apply() }
            loading=true; error=null
            job=scope.launch {
                try { val next=searcher(term,if(loadMore) results.size else 0); val all=(results+next).distinctBy { it.url }; more=all.size>results.size; results=all }
                catch(e:CancellationException) { throw e } catch(e:Exception) { error=e.message ?: "Search failed. Retry." }
                finally { loading=false }
            }
        }
        BackHandler { if(selected!=null) selected=null else onDismiss() }
        Surface(Modifier.fillMaxSize().testTag("imageSearchScreen"),color=Color(0xff181d20),contentColor=Color.White) {
            if(selected!=null) key(selected!!.url) {
                ImageDetail(app,selected!!,results.filterNot { it.url==selected!!.url },{ selected=null },{ selected=it },onSaved)
            } else Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick=onDismiss) { Icon(Icons.Default.ArrowBack,"Close search") }
                    TextField(query,{query=it},Modifier.weight(1f).testTag("imageSearchQuery"),placeholder={Text("Search images")},singleLine=true,
                        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={search()}),
                        colors=TextFieldDefaults.colors(focusedContainerColor=Color.Transparent,unfocusedContainerColor=Color.Transparent))
                    IconButton(onClick={query=""}) { Icon(Icons.Default.Close,"Clear search") }
                    IconButton(onClick={search()},modifier=Modifier.testTag("imageSearchGo")) { Icon(Icons.Default.Search,"Search") }
                }
                if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it,Modifier.padding(12.dp),color=MaterialTheme.colorScheme.error); TextButton(onClick={search(results.isNotEmpty())}) { Text("Retry") } }
                if(submitted.isBlank()) Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                    Text("Recent searches")
                    for(term in history) TextButton(onClick={query=term;search()}) { Text(term) }
                    if(history.isNotEmpty()) TextButton(onClick={history=emptyList();prefs.edit().remove("history").apply()}) {Text("Clear history")}
                } else {
                    LazyVerticalStaggeredGrid(columns=StaggeredGridCells.Fixed(3),modifier=Modifier.weight(1f).testTag("imageResultsGrid"),horizontalArrangement=Arrangement.spacedBy(3.dp),verticalItemSpacing=3.dp) {
                        items(results,key={it.url}) { item -> SearchThumbnail(app,item,Modifier.clickable { keyboard?.hide();selected=item }.testTag("imageResult")) }
                        item(span=StaggeredGridItemSpan.FullLine) {
                            if(more && results.isNotEmpty()) TextButton(enabled=!loading,onClick={search(true)},modifier=Modifier.fillMaxWidth()) {Text(if(loading) "Loading…" else "Load more")}
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageDetail(app: App,item: SearchImage,related: List<SearchImage>,onBack:()->Unit,onSelect:(SearchImage)->Unit,onSaved:()->Unit) {
    val scope=rememberCoroutineScope()
    var file by remember { mutableStateOf<File?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var dimensions by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var galleryUri by remember { mutableStateOf<Uri?>(null) }
    LaunchedEffect(item.url,retry) {
        error=null
        try {
            val downloaded=WebImages.download(app,item.url); file=downloaded
            bitmap=WebImages.preview(app,Uri.fromFile(downloaded))
            val bounds=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
            withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeFile(downloaded.absolutePath,bounds) }
            dimensions="${bounds.outWidth} × ${bounds.outHeight}"
        } catch(e:CancellationException) { throw e } catch(e:Exception) { error=e.message ?: "Image could not load." }
    }
    DisposableEffect(Unit) { onDispose { file?.delete() } }
    fun download(share:Boolean=false) {
        val source=file ?: return
        busy=true; message=null
        scope.launch {
            try {
                val uri=galleryUri ?: ImageSearch.downloadToGallery(app,source).also { galleryUri=it }
                message="Downloaded to Pictures/NockNock"
                if(share) app.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type=app.contentResolver.getType(uri);putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) },"Share image").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch(e:CancellationException) { throw e } catch(e:Exception) { message=e.message ?: "Download failed." } finally {busy=false}
        }
    }
    Column(Modifier.fillMaxSize().testTag("imageDetail").verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            IconButton(enabled=!busy,onClick=onBack) { Icon(Icons.Default.ArrowBack,"Back to images") }
            Column(Modifier.weight(1f).padding(8.dp)) {
                Text(item.title,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text("$dimensions · ${Uri.parse(item.source).host.orEmpty()}",style=MaterialTheme.typography.bodySmall,color=Color.Gray,maxLines=1)
            }
        }
        Box(Modifier.fillMaxWidth().heightIn(min=260.dp,max=520.dp),contentAlignment=Alignment.Center) {
            bitmap?.let { Image(it.asImageBitmap(),"Full image",Modifier.fillMaxWidth().heightIn(max=520.dp),contentScale=ContentScale.Fit) }
                ?: if(error==null) CircularProgressIndicator() else Text(error!!,Modifier.padding(16.dp))
        }
        if(error!=null) TextButton(onClick={retry++}) {Text("Retry image")}
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
            TextButton(enabled=file!=null&&!busy,onClick={download()},modifier=Modifier.testTag("downloadImage")) {Icon(Icons.Default.Download,null);Text("Download")}
            TextButton(enabled=file!=null&&!busy,onClick={download(true)}) {Icon(Icons.Default.Share,null);Text("Share")}
        }
        TextButton(enabled=file!=null&&!busy,onClick={
            val source=file ?: return@TextButton
            busy=true
            scope.launch { try {
                val imported=app.library.importItems(LibraryKind.OVERLAYS,listOf(Uri.fromFile(source)))
                check(imported.size==1) {"Could not save image. Check free storage."}
                app.library.rename(imported.single().id,item.title.take(120));onSaved()
            } catch(e:CancellationException) {throw e} catch(e:Exception) {message=e.message} finally {busy=false} }
        },modifier=Modifier.fillMaxWidth().testTag("saveSearchOverlay")) {Icon(Icons.Default.Add,null);Text("Save to Overlay Folder")}
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let {Text(it,Modifier.padding(12.dp))}
        HorizontalDivider()
        Text("↓ Related images ↓",Modifier.align(Alignment.CenterHorizontally).padding(10.dp),style=MaterialTheme.typography.titleLarge)
        HorizontalDivider()
        // Same-query results stay available without opening a website or losing the search.
        for(row in related.take(18).chunked(3)) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(3.dp)) {
            for(other in row) Box(Modifier.weight(1f)) {SearchThumbnail(app,other,Modifier.clickable(enabled=!busy) {onSelect(other)})}
            repeat(3-row.size) {Spacer(Modifier.weight(1f))}
        }
    }
}
