package com.clhs.score.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.clhs.score.domain.overview.defaultOverviewWidgets
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverviewLayoutOrderTransactionTest {
    @Test fun failedReplaceRollsBackRowsAndOrderVersionThenSuccessfulSaveUpgradesTogether() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, OverviewLayoutDatabase::class.java).build()
        try {
            val dao = database.layoutDao()
            val widgets = defaultOverviewWidgets()
            val entities = widgets.mapIndexed { rank, widget ->
                OverviewWidgetEntity(widget.id, widget.type, rank, widget.size.columns, widget.size.rows,
                    widget.enabled, widget.configVersion, widget.configuration, widget.ownRow)
            }
            dao.initialize(entities, importedLegacy = true)
            assertEquals(1, dao.metadata()!!.formatVersion)
            assertEquals(entities.map { OverviewVersionedWidget(it, 1) }, dao.observeVersioned().first())
            assertTrue(runCatching { dao.replace(listOf(entities.first(), entities.first())) }.isFailure)
            assertEquals(entities, dao.observe().first())
            assertEquals(1, dao.metadata()!!.formatVersion)
            val repository = OverviewLayoutRepository(dao, SettingsRepository(context))
            repository.save(widgets.reversed())
            val result = repository.observeLayout().first()
            assertEquals(widgets.reversed(), result.widgets)
            assertFalse(result.legacyOrder)
            assertTrue(dao.observeVersioned().first().all { it.layoutVersion == 2 })
            assertTrue(dao.metadata()!!.importedLegacy)
        } finally {
            database.close()
        }
    }
}
