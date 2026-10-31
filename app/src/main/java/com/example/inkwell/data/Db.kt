package com.example.inkwell.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/** Single DB thread keeps saves and loads strictly ordered. */
val DbDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
val AppScope = CoroutineScope(SupervisorJob())

@Entity
data class Notebook(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long
)

@Entity(indices = [Index("notebookId")])
class Page(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val notebookId: Long,
    val position: Int,
    val template: Int,
    val strokes: ByteArray?
)

@Dao
interface NotebookDao {
    @Query("SELECT * FROM Notebook ORDER BY createdAt DESC")
    suspend fun notebooks(): List<Notebook>

    @Insert
    suspend fun insert(n: Notebook): Long

    @Query("DELETE FROM Notebook WHERE id = :id")
    suspend fun deleteNotebook(id: Long)

    @Query("DELETE FROM Page WHERE notebookId = :id")
    suspend fun deletePages(id: Long)

    @Query("SELECT id FROM Page WHERE notebookId = :nb ORDER BY position")
    suspend fun pageIds(nb: Long): List<Long>

    @Query("SELECT * FROM Page WHERE id = :id")
    suspend fun page(id: Long): Page?

    @Insert
    suspend fun insertPage(p: Page): Long

    @Query("UPDATE Page SET strokes = :data WHERE id = :id")
    suspend fun saveStrokes(id: Long, data: ByteArray)

    @Query("UPDATE Page SET template = :t WHERE id = :id")
    suspend fun saveTemplate(id: Long, t: Int)
}

@Database(entities = [Notebook::class, Page::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): NotebookDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "inkwell.db")
                .build().also { inst = it }
        }
    }
}
