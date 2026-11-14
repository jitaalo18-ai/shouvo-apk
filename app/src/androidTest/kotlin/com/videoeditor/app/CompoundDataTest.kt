package com.videoeditor.app

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.videoeditor.app.data.AppDatabase
import com.videoeditor.app.data.ProjectRepository
import com.videoeditor.app.data.clipFrom
import com.videoeditor.app.media.Exporter
import com.videoeditor.app.media.MediaProbe
import com.videoeditor.core.Compound
import com.videoeditor.core.MediaKind
import com.videoeditor.core.Timeline
import com.videoeditor.core.TimelineOps
import com.videoeditor.core.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.math.abs

/** Compound clips on a real device: storage (incl. the database upgrade) and rendering. */
@RunWith(AndroidJUnit4::class)
class CompoundDataTest {
    private val ctx get() = Fixtures.target
    private fun id() = UUID.randomUUID().toString()

    /** A database exactly as V0.13 (schema version 3) left it on the phone, with one project. */
    private fun createVersion3(name: String) {
        ctx.deleteDatabase(name)
        val db = SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath(name).apply { parentFile?.mkdirs() }, null)
        db.execSQL("CREATE TABLE IF NOT EXISTS `projects` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `clips` (`id` TEXT NOT NULL, `projectId` INTEGER NOT NULL, `track` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`sourceUri` TEXT NOT NULL, `mimeType` TEXT, `name` TEXT NOT NULL, `libraryItemId` INTEGER, `sourceDurationMs` INTEGER NOT NULL, " +
                "`sourceStartMs` INTEGER NOT NULL, `sourceEndMs` INTEGER NOT NULL, `timelineStartMs` INTEGER NOT NULL, `hasAudio` INTEGER NOT NULL, " +
                "`aspect` REAL NOT NULL, `layer` INTEGER NOT NULL, `posX` REAL NOT NULL, `posY` REAL NOT NULL, `scale` REAL NOT NULL, " +
                "`selected` INTEGER NOT NULL, `rotation` REAL NOT NULL DEFAULT 0, `stretchX` REAL NOT NULL DEFAULT 1, `stretchY` REAL NOT NULL DEFAULT 1, " +
                "PRIMARY KEY(`id`), FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_clips_projectId` ON `clips` (`projectId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_clips_libraryItemId` ON `clips` (`libraryItemId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `library_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`fileName` TEXT NOT NULL, `mimeType` TEXT, `durationMs` INTEGER NOT NULL, `hasAudio` INTEGER NOT NULL, `aspect` REAL NOT NULL, " +
                "`addedAt` INTEGER NOT NULL, `removed` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_library_items_kind` ON `library_items` (`kind`)")
        db.execSQL("INSERT INTO projects (id, name, createdAt, updatedAt) VALUES (1, 'Old project', 1, 1)")
        db.execSQL(
            "INSERT INTO clips (id, projectId, track, kind, sourceUri, mimeType, name, libraryItemId, sourceDurationMs, sourceStartMs, " +
                "sourceEndMs, timelineStartMs, hasAudio, aspect, layer, posX, posY, scale, selected, rotation, stretchX, stretchY) " +
                "VALUES ('old', 1, 'MAIN', 'VIDEO', 'file:///x.mp4', 'video/mp4', 'x', NULL, 9000, 1000, 5000, 0, 1, 1.7, 0, 0.5, 0.5, 0.4, 0, 15, 1.2, 1)",
        )
        db.execSQL("INSERT INTO library_items (id, kind, name, fileName, mimeType, durationMs, hasAudio, aspect, addedAt, removed) VALUES (7, 'VIDEO_FX', 'fx', 'fx.mp4', NULL, 3000, 1, 1.0, 1, 0)")
        db.version = 3
        db.close()
    }

    @Test fun oldProjectsSurviveTheUpgradeAndCompoundsAreStored() = runBlocking {
        val name = "migration_test.db"
        createVersion3(name)
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6)
            .build()
        try {
            val repo = ProjectRepository(db)
            val old = repo.load(1)
            assertEquals(1, old.clips.size)
            val c = old.clips.single()
            assertEquals(4000L, c.durationMs)
            assertEquals(15f, c.rotation, 0.001f)
            assertEquals(1.2f, c.stretchX, 0.001f)
            assertTrue(c.children.isEmpty())
            Report.note("db: version 3 -> 6 upgrade kept the old project")
            assertEquals("", c.text)
            assertEquals(com.videoeditor.core.AudioFx.NONE, c.audio)

            // captions / texts (version 5) are stored, also inside compounds
            val caption = com.videoeditor.core.Captions.textClip("cap", 500, 1_000, "আমি বাংলায় বলছি", name = "Caption", style = "BOX", posY = 0.8f)
            repo.save(1, Timeline(old.clips + caption))
            val reloaded = repo.load(1).byId("cap")
            assertEquals(caption, reloaded)

            // Voice enhance settings (version 6) are stored
            val voiced = c.copy(audio = com.videoeditor.core.AudioFx(volume = 1.5f, denoise = 0.7f, enhance = 0.2f, fadeInMs = 500, fadeOutMs = 800))
            repo.save(1, Timeline(listOf(voiced, caption)))
            assertEquals(voiced.audio, repo.load(1).byId("old")!!.audio)

            // a nested compound with a library clip deep inside is stored and read back exactly
            val fx = c.copy(id = "fx", libraryItemId = 7, sourceUri = "file:///fx.mp4", audio = voiced.audio)
            var t = TimelineOps.normalize(Timeline(listOf(c, fx, c.copy(id = "third"), caption)))
            t = Compound.create(t, listOf("old", "fx", "cap"), ::id, 9f / 16f).timeline
            t = Compound.create(t, t.main.map { it.id }, ::id, 9f / 16f).timeline
            t = TimelineOps.transformMain(t, t.main.single().id, 0.6f, 0.4f, 1.5f, 30f)
            repo.save(1, t)
            val back = repo.load(1)
            assertEquals(t.clips.toSet(), back.clips.toSet())
            assertEquals(4, Compound.leafCount(back.main.single()))
            assertEquals("caption inside the compound keeps its words", "আমি বাংলায় বলছি",
                Compound.flatten(back, 9f / 16f).track(com.videoeditor.core.Track.TEXT).single().text)
            assertEquals("library item inside the compound counts as used", 1, db.library().usageCount(7))
            assertEquals(listOf("Old project"), db.library().projectsUsing(7))

            // deleting the project removes its clips
            repo.delete(1)
            assertEquals(0, db.library().usageCount(7))
            assertTrue(repo.load(1).clips.isEmpty())
        } finally {
            db.close()
            ctx.deleteDatabase(name)
        }
    }

    @Test fun compoundWithFramingExportsWithTheRightLength() = runBlocking {
        val speech = clipFrom(Fixtures.info("speech_test.mp4"), Track.MAIN)
        val portrait = clipFrom(Fixtures.info("portrait_test.mp4"), Track.MAIN)
        val image = clipFrom(Fixtures.info("image_test.png"), Track.MAIN)
        var t = TimelineOps.appendMain(Timeline(), listOf(speech.copy(sourceStartMs = 0, sourceEndMs = 5_000), portrait, image))
        t = Compound.create(t, t.main.map { it.id }, ::id, 9f / 16f).timeline
        val compound = t.main.single()
        // frame it (zoom + turn), split it, hide the last second
        t = TimelineOps.transformMain(t, compound.id, 0.55f, 0.5f, 1.3f, 10f)
        t = TimelineOps.split(t, compound.id, 8_000, ::id).timeline
        val second = t.main[1]
        t = TimelineOps.trimMain(t, second.id, newSourceEndMs = second.sourceEndMs - 1_000)
        assertEquals(5_000L + portrait.durationMs + image.durationMs - 1_000L, t.durationMs)
        assertTrue(t.main.all { it.isCompound })
        val t0 = System.currentTimeMillis()
        val out = Exporter(ctx).export(t, 9f / 16f) { }
        Report.note("export of a compound timeline (${t.durationMs} ms, ${Compound.flatten(t, 9f / 16f).main.size} pieces): ${System.currentTimeMillis() - t0} ms")
        val info = MediaProbe.probe(ctx, out)
        try {
            assertNotNull(info)
            assertEquals(MediaKind.VIDEO, info!!.kind)
            assertTrue("exported ${info.durationMs} ms, expected ${t.durationMs} ms", abs(info.durationMs - t.durationMs) < 500)
            assertTrue("exported video has sound", info.hasAudio)
            assertTrue("portrait output", info.aspect < 1f)
        } finally {
            ctx.contentResolver.delete(out, null, null)
        }
    }
}
