package com.clhs.score.data

import android.content.Context
import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import com.clhs.score.domain.overview.OverviewWidgetDefinitions
import com.clhs.score.domain.overview.OverviewWidgetInstance
import com.clhs.score.domain.overview.OverviewWidgetSize
import com.clhs.score.domain.overview.defaultOverviewWidgets
import com.clhs.score.domain.overview.importOverviewWidgets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "overview_layout_metadata")
data class OverviewLayoutMetadata(@PrimaryKey val id: Int = 1, val formatVersion: Int = 1, val importedLegacy: Boolean = false)

data class OverviewLayoutSnapshot(val widgets: List<OverviewWidgetInstance>, val legacyOrder: Boolean)

@Entity(tableName = "overview_widget_instances")
data class OverviewWidgetEntity(@PrimaryKey val id: String, val type: String, val rank: Int,
    val columns: Int, val rows: Int, val enabled: Boolean, val configVersion: Int, val configuration: String,
    @ColumnInfo(defaultValue = "0") val ownRow: Boolean = false) {
    fun instance() = OverviewWidgetInstance(id, type, OverviewWidgetSize(columns, rows), enabled, configVersion, configuration, ownRow)
}

data class OverviewVersionedWidget(@Embedded val widget: OverviewWidgetEntity,
    @ColumnInfo(name = "layout_format_version") val layoutVersion: Int)

@Dao
abstract class OverviewLayoutDao {
    @Query("SELECT * FROM overview_layout_metadata WHERE id = 1") abstract suspend fun metadata(): OverviewLayoutMetadata?
    @Query("SELECT * FROM overview_widget_instances ORDER BY rank, id") abstract fun observe(): Flow<List<OverviewWidgetEntity>>
    @Query("SELECT w.*, m.formatVersion AS layout_format_version FROM overview_widget_instances w " +
        "JOIN overview_layout_metadata m ON m.id = 1 ORDER BY w.rank, w.id")
    abstract fun observeVersioned(): Flow<List<OverviewVersionedWidget>>
    @Query("UPDATE overview_layout_metadata SET formatVersion = 1 WHERE id = 1") abstract suspend fun markLegacy()
    @Transaction
    open suspend fun restore(widgets: List<OverviewWidgetEntity>, legacyOrder: Boolean) {
        replace(widgets)
        if (legacyOrder) markLegacy()
    }
    @Insert abstract suspend fun insertMetadata(metadata: OverviewLayoutMetadata)
    @Insert abstract suspend fun insertWidgets(widgets: List<OverviewWidgetEntity>)
    @Query("DELETE FROM overview_widget_instances") abstract suspend fun deleteWidgets()
    @Query("UPDATE overview_layout_metadata SET formatVersion = 2 WHERE id = 1") abstract suspend fun markOrdered()

    @Transaction
    open suspend fun initialize(widgets: List<OverviewWidgetEntity>, importedLegacy: Boolean) {
        val existing = metadata()
        if (existing != null) { check(existing.formatVersion in 1..2); return }
        insertWidgets(widgets)
        insertMetadata(OverviewLayoutMetadata(formatVersion = if (importedLegacy) 1 else 2, importedLegacy = importedLegacy))
    }

    @Transaction
    open suspend fun replace(widgets: List<OverviewWidgetEntity>) {
        check(metadata()?.formatVersion in 1..2)
        deleteWidgets()
        insertWidgets(widgets)
        markOrdered()
    }
}

// v2.0.0 stored Overview preferences in DataStore; this is the first released Room schema.
@Database(entities = [OverviewLayoutMetadata::class, OverviewWidgetEntity::class], version = 1, exportSchema = true)
abstract class OverviewLayoutDatabase : RoomDatabase() { abstract fun layoutDao(): OverviewLayoutDao }

class OverviewLayoutRepository internal constructor(private val dao: OverviewLayoutDao, private val settings: SettingsRepository,
    private val legacyEvents: suspend () -> List<SchoolCalendarEvent> = { emptyList() }) {
    fun observe(): Flow<List<OverviewWidgetInstance>> = observeLayout().map { it.widgets }

    fun observeLayout(): Flow<OverviewLayoutSnapshot> = flow {
        // Root startup has already run SettingsRepository's onboarding migration.
        if (dao.metadata() == null) {
            val snapshot = settings.loadOverviewMigration()
            val widgets = if (snapshot.second) importOverviewWidgets(snapshot.first, legacyEvents())
                else defaultOverviewWidgets()
            dao.initialize(widgets.entities(), snapshot.second)
        }
        check(dao.metadata()?.formatVersion in 1..2)
        emitAll(dao.observeVersioned().map { rows ->
            // Nonempty layouts and their order version come from one database snapshot.
            val version = rows.firstOrNull()?.layoutVersion ?: dao.metadata()?.formatVersion
            check(version in 1..2)
            OverviewLayoutSnapshot(rows.map { it.widget.instance() }, legacyOrder = version == 1)
        })
    }

    internal suspend fun restore(snapshot: OverviewLayoutSnapshot) {
        validate(snapshot.widgets)
        dao.restore(snapshot.widgets.entities(), snapshot.legacyOrder)
    }

    suspend fun save(widgets: List<OverviewWidgetInstance>) {
        validate(widgets)
        dao.replace(widgets.entities())
    }

    private fun validate(widgets: List<OverviewWidgetInstance>) {
        require(widgets.map { it.id }.distinct().size == widgets.size && widgets.all { it.id.isNotBlank() })
        widgets.filter { it.config() != null }.forEach {
            require(it.size in requireNotNull(OverviewWidgetDefinitions[it.type]).sizes)
        }
        val singletons = widgets.filter { OverviewWidgetDefinitions[it.type]?.multiple == false }.groupBy { it.type }
        require(singletons.values.all { it.size == 1 })
    }

    companion object {
        @Volatile private var instance: OverviewLayoutRepository? = null
        fun get(context: Context): OverviewLayoutRepository = instance ?: synchronized(this) {
            instance ?: context.applicationContext.let { app ->
                val database = Room.databaseBuilder(app, OverviewLayoutDatabase::class.java, "overview_layout.db").build()
                OverviewLayoutRepository(database.layoutDao(), SettingsRepository(app)) {
                    NetworkSchoolCalendarRepository(app.cacheDir).loadCached()?.feed?.events.orEmpty()
                }.also { instance = it }
            }
        }
    }
}

private fun List<OverviewWidgetInstance>.entities(): List<OverviewWidgetEntity> = mapIndexed { rank, it ->
    OverviewWidgetEntity(it.id, it.type, rank, it.size.columns, it.size.rows, it.enabled, it.configVersion, it.configuration, it.ownRow)
}
