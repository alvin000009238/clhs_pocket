package com.clhs.score.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import com.clhs.score.data.FakeData
import com.clhs.score.ui.announcements.SchoolAnnouncementsScreen
import com.clhs.score.ui.theme.ScoreTheme
import com.clhs.score.viewmodel.SchoolAnnouncementsViewModel
import org.junit.Rule
import org.junit.Test

class SchoolAnnouncementsScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun clearingCollapsedSearchRestoresNonmatchingAnnouncementsInShortWindow() {
        val store = ViewModelStore()
        lateinit var viewModel: SchoolAnnouncementsViewModel
        val samples = FakeData.announcementPage.announcements
        val announcements = List(20) { index ->
            val sample = samples[index % samples.size]
            sample.copy(id = "${sample.id}-$index", title = "${sample.title}（${index + 1}）")
        }
        val keyword = announcements.first().title
        try {
            composeRule.runOnUiThread {
                viewModel = SchoolAnnouncementsViewModel(
                    loadCached = { null },
                    loadPage = { _, query, _, _ ->
                        FakeData.announcementPage.copy(
                            announcements = announcements.filter { it.title.contains(query) },
                            totalPages = 1,
                        )
                    },
                )
                store.put("announcements", viewModel)
                viewModel.search(keyword)
            }
            composeRule.setContent {
                val state by viewModel.uiState.collectAsState()
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    ScoreTheme {
                        Box(Modifier.size(320.dp, 400.dp)) {
                            SchoolAnnouncementsScreen(
                                uiState = state,
                                onBack = {}, onRefresh = viewModel::refresh, onLoadMore = viewModel::loadMore,
                                onSearch = viewModel::search, onClearSearch = { viewModel.clearSearch() },
                                onOpenAnnouncement = {}, onOpenSavedAnnouncement = {}, onOpenOfficialWebsite = {},
                                onOpenAnnouncementReminder = {}, onNoticeShown = viewModel::consumeNotice,
                            )
                        }
                    }
                }
            }

            composeRule.onNodeWithText(announcements.first().title).assertIsDisplayed()
            composeRule.onNodeWithText(announcements.last().title).assertDoesNotExist()
            composeRule.onNodeWithContentDescription("清除搜尋：$keyword")
                .assertIsDisplayed()
                .performClick()

            composeRule.waitUntil(5_000) {
                viewModel.uiState.value.searchQuery.isEmpty() &&
                    viewModel.uiState.value.announcements.size == announcements.size
            }
            composeRule.onNodeWithContentDescription("清除搜尋：$keyword").assertDoesNotExist()
            composeRule.onNodeWithText(announcements.last().title).assertDoesNotExist()
            composeRule.onNode(hasScrollToIndexAction())
                .performScrollToNode(hasText(announcements.last().title))
            composeRule.onNodeWithText(announcements.last().title).assertIsDisplayed()
        } finally {
            composeRule.runOnUiThread { store.clear() }
        }
    }
}
