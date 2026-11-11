package com.clhs.score.ui.overview

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.clhs.score.data.FakeScheduleData
import com.clhs.score.data.ScheduleScope
import com.clhs.score.domain.overview.scheduleOverviewHero
import com.clhs.score.ui.theme.ScoreTheme
import java.time.LocalDateTime

@Preview(name = "精簡課程", widthDp = 390, heightDp = 500)
@Preview(name = "精簡課程深色", widthDp = 390, heightDp = 500, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "精簡課程大字", widthDp = 320, heightDp = 900, fontScale = 2f)
@Preview(name = "精簡課程寬版", widthDp = 700, heightDp = 500)
@Composable
private fun CompactCoursePreview() = CoursePreviewAt(LocalDateTime.of(2026, 11, 11, 15, 6))

@Preview(name = "下課下一節新增", widthDp = 390, heightDp = 500)
@Composable
private fun UpcomingChangedCoursePreview() = CoursePreviewAt(LocalDateTime.of(2026, 11, 11, 16, 0))

@Preview(name = "當日停課", widthDp = 390, heightDp = 500)
@Composable
private fun CancelledCoursePreview() = CoursePreviewAt(LocalDateTime.of(2026, 11, 9, 9, 22))

@Composable
private fun CoursePreviewAt(now: LocalDateTime) {
    val report = FakeScheduleData.report("115_1", "230", ScheduleScope.CURRENT_WEEK, now.toLocalDate())
    ScoreTheme {
        Surface {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                HeroCard(scheduleOverviewHero(report, now), now, true, false, null, {})
            }
        }
    }
}
