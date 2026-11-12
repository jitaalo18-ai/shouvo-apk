package com.videoeditor.app.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** One row per timeline clip = one editing decision. Source media is stored elsewhere. */
@Entity(
    tableName = "clips",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("projectId"), Index("libraryItemId")],
)
data class ClipEntity(
    @PrimaryKey val id: String,
    val projectId: Long,
    val track: String,
    val kind: String,
    val sourceUri: String,
    val mimeType: String?,
    val name: String,
    val libraryItemId: Long?,
    val sourceDurationMs: Long,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val timelineStartMs: Long,
    val hasAudio: Boolean,
    val aspect: Float,
    val layer: Int,
    val posX: Float,
    val posY: Float,
    val scale: Float,
    val selected: Boolean,
    @ColumnInfo(defaultValue = "0") val rotation: Float = 0f,
    @ColumnInfo(defaultValue = "1") val stretchX: Float = 1f,
    @ColumnInfo(defaultValue = "1") val stretchY: Float = 1f,
)

/**
 * Metadata for one saved library file. [kind] is VIDEO_FX, SOUND_FX or MUSIC; each kind has its
 * own records and its own folder in app-private storage.
 * [removed] = the user removed it from the library while a project still uses it: it is hidden
 * from the library but the file is kept until no project references it any more.
 */
@Entity(tableName = "library_items", indices = [Index("kind")])
data class LibraryItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val name: String,
    val fileName: String,
    val mimeType: String?,
    val durationMs: Long,
    val hasAudio: Boolean,
    val aspect: Float,
    val addedAt: Long,
    val removed: Boolean = false,
)

@Dao
abstract class ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    abstract fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    abstract suspend fun get(id: Long): ProjectEntity?

    @Query("SELECT COUNT(*) FROM projects")
    abstract suspend fun count(): Int

    @Insert
    abstract suspend fun insert(project: ProjectEntity): Long

    @Query("UPDATE projects SET updatedAt = :time WHERE id = :id")
    abstract suspend fun touch(id: Long, time: Long)

    @Query("SELECT * FROM clips WHERE projectId = :projectId")
    abstract suspend fun clips(projectId: Long): List<ClipEntity>

    @Query("SELECT * FROM clips WHERE projectId = :projectId AND track = 'MAIN' ORDER BY timelineStartMs LIMIT 1")
    abstract suspend fun firstMainClip(projectId: Long): ClipEntity?

    @Query("DELETE FROM clips WHERE projectId = :projectId")
    abstract suspend fun deleteClips(projectId: Long)

    @Insert
    abstract suspend fun insertClips(clips: List<ClipEntity>)

    @Transaction
    open suspend fun replaceClips(projectId: Long, clips: List<ClipEntity>, time: Long) {
        deleteClips(projectId)
        insertClips(clips)
        touch(projectId, time)
    }
}

@Dao
abstract class LibraryDao {
    @Query("SELECT * FROM library_items WHERE kind = :kind AND removed = 0 ORDER BY addedAt DESC")
    abstract fun observe(kind: String): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_items WHERE id = :id")
    abstract suspend fun get(id: Long): LibraryItemEntity?

    @Insert
    abstract suspend fun insert(item: LibraryItemEntity): Long

    @Query("UPDATE library_items SET name = :name WHERE id = :id")
    abstract suspend fun rename(id: Long, name: String)

    @Query("UPDATE library_items SET removed = 1 WHERE id = :id")
    abstract suspend fun markRemoved(id: Long)

    @Query("DELETE FROM library_items WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("SELECT * FROM library_items WHERE removed = 1")
    abstract suspend fun removedItems(): List<LibraryItemEntity>

    @Query("SELECT COUNT(*) FROM clips WHERE libraryItemId = :itemId")
    abstract suspend fun usageCount(itemId: Long): Int

    @Query("SELECT DISTINCT p.name FROM projects p INNER JOIN clips c ON c.projectId = p.id WHERE c.libraryItemId = :itemId")
    abstract suspend fun projectsUsing(itemId: Long): List<String>
}

@Database(
    entities = [ProjectEntity::class, ClipEntity::class, LibraryItemEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projects(): ProjectDao
    abstract fun library(): LibraryDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "video_editor.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()

        /** 0.8: clips can be made wider / taller. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE clips ADD COLUMN stretchX REAL NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE clips ADD COLUMN stretchY REAL NOT NULL DEFAULT 1")
            }
        }

        /** 0.7: clips can be rotated. Existing projects keep everything; rotation starts at 0. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE clips ADD COLUMN rotation REAL NOT NULL DEFAULT 0")
            }
        }
    }
}
