package com.clhs.score.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId

@Entity(tableName = "announcement_read_state")
data class AnnouncementReadState(@PrimaryKey val announcementKey: String, val isRead: Boolean)

@Dao
interface AnnouncementReadDao {
    @Query("SELECT announcementKey FROM announcement_read_state WHERE isRead = 0")
    fun observeUnreadKeys(): kotlinx.coroutines.flow.Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDiscovered(states: List<AnnouncementReadState>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markRead(state: AnnouncementReadState)

    @Query("UPDATE announcement_read_state SET isRead = 1 WHERE announcementKey IN (:keys)")
    suspend fun markKeysRead(keys: List<String>)

    @Transaction
    suspend fun markSnapshotRead(keys: Set<String>) {
        // Stay below SQLite's bind limit, including older Android SQLite versions.
        keys.chunked(500).forEach { markKeysRead(it) }
    }
}

@Database(entities = [AnnouncementReadState::class], version = 1, exportSchema = true)
abstract class AnnouncementReadDatabase : RoomDatabase() {
    abstract fun readDao(): AnnouncementReadDao
}

class AnnouncementReadRepository internal constructor(
    private val dao: AnnouncementReadDao,
    private val metadata: AnnouncementReminderRepository,
    private val today: () -> LocalDate = { LocalDate.now(ZoneId.of("Asia/Taipei")) },
) {
    private val migrationMutex = Mutex()
    private var initializedCutoff: LocalDate? = null
    val discoveryAvailable = MutableStateFlow(true)
    val errorMessage = MutableStateFlow<String?>(null)
    val unreadIds = flow {
        initialize()
        emitAll(dao.observeUnreadKeys().map { it.toSet() })
    }

    suspend fun initialize(): LocalDate = migrationMutex.withLock {
        initializedCutoff?.let { return@withLock it }
        val (cutoff, legacyUnread) = metadata.prepareReadMigration(today())
        if (legacyUnread != null) {
            dao.insertDiscovered(legacyUnread.filter(String::isNotBlank).map { AnnouncementReadState(it, false) })
            metadata.finishReadMigration()
        }
        cutoff.also { initializedCutoff = it }
    }

    suspend fun discover(announcements: List<SchoolAnnouncement>) {
        val cutoff = initialize()
        dao.insertDiscovered(announcements.distinctBy(SchoolAnnouncement::id)
            .filter { it.id.isNotBlank() && announcementStartsUnread(it.date, cutoff) }
            .map { AnnouncementReadState(it.id, false) })
        discoveryAvailable.value = true
    }

    // Public content remains available when reading-state storage is temporarily unavailable.
    suspend fun discoverForDisplay(announcements: List<SchoolAnnouncement>) {
        try {
            discover(announcements)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            discoveryAvailable.value = false
            errorMessage.value = "無法儲存公告閱讀狀態，請稍後重新整理。"
        }
    }

    suspend fun markRead(key: String) {
        require(key.isNotBlank())
        initialize()
        dao.markRead(AnnouncementReadState(key, true))
    }

    suspend fun markAllRead(snapshot: Set<String>) {
        initialize()
        dao.markSnapshotRead(snapshot)
    }

    companion object {
        @Volatile private var instance: AnnouncementReadRepository? = null

        fun get(context: Context): AnnouncementReadRepository = instance ?: synchronized(this) {
            instance ?: context.applicationContext.let { app ->
                val database = Room.databaseBuilder(app, AnnouncementReadDatabase::class.java, "announcement_read_state.db")
                    .build()
                AnnouncementReadRepository(database.readDao(), AnnouncementReminderRepository(app, migrateReading = false))
                    .also { instance = it }
            }
        }
    }
}

internal fun announcementStartsUnread(date: String, cutoff: LocalDate): Boolean {
    val published = runCatching {
        LocalDate.parse(date.trim().substringBefore(' ').substringBefore('T').replace('/', '-'))
    }.getOrNull()
    return published == null || !published.isBefore(cutoff)
}
