package com.clhs.score.ui.navigation

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import com.clhs.score.data.FakeScheduleData
import com.clhs.score.data.ScheduleScope
import com.clhs.score.ui.schedule.ScheduleScreen
import com.clhs.score.ui.theme.ScoreTheme
import com.clhs.score.viewmodel.ScheduleUiState
import java.time.LocalDate

@Preview(name = "Schedule root", widthDp = 390, heightDp = 844, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Schedule root landscape", widthDp = 740, heightDp = 360)
@Preview(name = "Schedule root large text", widthDp = 390, heightDp = 844, fontScale = 1.6f)
@Composable
private fun ScheduleRootNavigationPreview() = ScheduleNavigationPreview(covered = false)

@Preview(name = "Schedule beneath personal", widthDp = 390, heightDp = 844, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ScheduleCoveredNavigationPreview() = ScheduleNavigationPreview(covered = true)

/** Show only the background entry so its geometry can be compared with the active root. */
@Composable
private fun ScheduleNavigationPreview(covered: Boolean) {
    val state = remember(covered) {
        AppNavigationState(
            startRoute = OverviewRoute,
            topLevelRoute = mutableStateOf<NavKey>(ScheduleRoute),
            backStacks = TopLevelDestination.entries.associate { destination ->
                (destination.route as NavKey) to NavBackStack<NavKey>(destination.route).apply {
                    if (covered && destination == TopLevelDestination.Schedule) add(PersonalRoute)
                }
            },
        )
    }
    ScoreTheme {
        AuthenticatedAppShell(state, remember(state) { AppNavigator(state) }) {
            val entries = state.toEntries { route ->
                NavEntry(route) {
                    if (route == ScheduleRoute) ScheduleScreen(
                        uiState = ScheduleUiState(
                            isInitialLoading = false,
                            report = FakeScheduleData.report("115_1", "230", ScheduleScope.CURRENT_WEEK, LocalDate.of(2026, 11, 9)),
                            selectedScope = ScheduleScope.CURRENT_WEEK,
                        ),
                        onRefresh = {}, onYearSelected = {}, onClassSelected = {}, onScopeSelected = {},
                        onConfirmSelection = {}, onClearSelection = {}, onNoticeShown = {},
                    )
                }
            }
            entries.first { it.metadata[RootSceneDestinationKey] == ScheduleRoute }.Content()
        }
    }
}
