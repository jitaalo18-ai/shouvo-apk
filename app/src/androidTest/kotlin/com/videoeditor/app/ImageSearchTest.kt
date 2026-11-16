package com.videoeditor.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videoeditor.app.media.ImageSearch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageSearchTest {
    @Test fun parserRejectsMalformedAndUnsafeResultsAndDeduplicates() {
        val tile="""<a m="{&quot;murl&quot;:&quot;https://example.com/image.png&quot;,&quot;turl&quot;:&quot;https://example.com/thumb.png&quot;,&quot;t&quot;:&quot;A &amp; B&quot;,&quot;purl&quot;:&quot;https://example.com/page&quot;}">"""
        val items=ImageSearch.parse(tile+tile+"<a m=\"bad\">"+tile.replace("https://example.com/image.png","javascript:alert(1)"))
        assertEquals(1,items.size);assertEquals("A & B",items.single().title)
    }
    @Test fun galleryDownloadPreservesBytesAndPublishesRow() = runBlocking {
        val context=Fixtures.target
        val original=Fixtures.file("image_test.png")
        val uri=ImageSearch.downloadToGallery(context,original)
        try {
            assertArrayEquals(original.readBytes(),context.contentResolver.openInputStream(uri)!!.use {it.readBytes()})
            context.contentResolver.query(uri,arrayOf(android.provider.MediaStore.Images.Media.IS_PENDING),null,null,null)!!.use {assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
        } finally {context.contentResolver.delete(uri,null,null)}
    }
}
