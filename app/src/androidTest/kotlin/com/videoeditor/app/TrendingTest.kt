package com.videoeditor.app

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videoeditor.app.data.*
import com.videoeditor.core.MediaKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrendingTest {
    @Test fun allSortOrdersAreStableAndCaseInsensitive() {
        fun item(id:Long,name:String,date:Long,duration:Long)=LibraryItemEntity(id,"VIDEO_FX",name,"$id.mp4","video/mp4",duration,true,1f,date)
        val a=item(1,"alpha",300,20); val b=item(2,"Beta",100,30); val c=item(3,"zebra",200,10)
        val items=listOf(c,b,a)
        fun ids(order:LibrarySort)=order.apply(items).map {it.id}
        assertEquals(listOf(1L,2L,3L),ids(LibrarySort.NAME_ASC))
        assertEquals(listOf(3L,2L,1L),ids(LibrarySort.NAME_DESC))
        assertEquals(listOf(1L,3L,2L),ids(LibrarySort.NEWEST))
        assertEquals(listOf(2L,3L,1L),ids(LibrarySort.OLDEST))
        assertEquals(listOf(3L,1L,2L),ids(LibrarySort.SHORTEST))
        assertEquals(listOf(2L,1L,3L),ids(LibrarySort.LONGEST))
        assertEquals(listOf(1L,4L),LibrarySort.NAME_ASC.apply(listOf(a.copy(id=4,name="ALPHA"),a)).map {it.id})
    }
    @Test fun trendingHasIndependentFilesAndVideoMetadata() = runBlocking {
        val context=Fixtures.target
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        val repo=LibraryRepository(context,db)
        val source=repo.importItems(LibraryKind.VIDEO_FX,listOf(Fixtures.uri("portrait_test.mp4"))).single()
        val copied=repo.importItems(LibraryKind.TRENDING,listOf(Uri.fromFile(repo.file(source)))).single()
        try {
            assertEquals(MediaKind.VIDEO,repo.mediaKind(copied))
            assertNotEquals(repo.file(source).absolutePath,repo.file(copied).absolutePath)
            assertArrayEquals(repo.file(source).readBytes(),repo.file(copied).readBytes())
            repo.rename(copied.id,"My trending clip")
            assertEquals("My trending clip",repo.observe(LibraryKind.TRENDING).first().single().name)
            repo.remove(source.id)
            assertTrue(repo.file(copied).exists())
            assertEquals(1,LibraryRepository(context,db).observe(LibraryKind.TRENDING).first().size)
            repo.remove(copied.id);assertFalse(repo.file(copied).exists())
        } finally {repo.remove(source.id);repo.remove(copied.id);db.close()}
    }
}
