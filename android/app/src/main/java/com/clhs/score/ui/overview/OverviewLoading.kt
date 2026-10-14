package com.clhs.score.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.clhs.score.ui.rememberLoadingShimmerProgress
import com.clhs.score.ui.theme.PocketVisual

@Composable
internal fun WeatherLoadingSkeleton(
    compact: Boolean, expanded: Boolean, modifier: Modifier = Modifier,
    showForecast: Boolean = false,
) {
    val progress = rememberLoadingShimmerProgress("overview-weather-shimmer")
    Column(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "天氣載入中" },
        verticalArrangement = if (expanded) Arrangement.SpaceBetween else Arrangement.spacedBy(8.dp)) {
        ShimmerBlock(progress, Modifier.fillMaxWidth(0.4f).height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShimmerBlock(progress, Modifier.size(if (compact) 24.dp else 32.dp))
            ShimmerBlock(progress, Modifier.width(if (compact) 60.dp else 84.dp).height(if (compact) 30.dp else 44.dp))
            if (!compact) ShimmerBlock(progress, Modifier.weight(1f).height(18.dp))
        }
        ShimmerBlock(progress, Modifier.fillMaxWidth(0.7f).height(12.dp))
        if (showForecast) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShimmerBlock(progress, Modifier.fillMaxWidth(0.65f).height(12.dp))
                    ShimmerBlock(progress, Modifier.fillMaxWidth().height(18.dp))
                    ShimmerBlock(progress, Modifier.fillMaxWidth(0.45f).height(12.dp))
                }
            }
        }
    }
}

@Composable
internal fun YouBikeLoadingSkeleton(expanded: Boolean) {
    val progress = rememberLoadingShimmerProgress("overview-youbike-shimmer")
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "YouBike 數量載入中" },
        verticalArrangement = Arrangement.spacedBy(if (expanded) 16.dp else 6.dp)) {
        repeat(if (expanded) 2 else 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(2) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ShimmerBlock(progress, Modifier.fillMaxWidth(0.7f).height(if (expanded) 36.dp else 24.dp))
                        ShimmerBlock(progress, Modifier.fillMaxWidth(0.6f).height(12.dp))
                    }
                }
            }
        }
        ShimmerBlock(progress, Modifier.fillMaxWidth(0.8f).height(12.dp))
    }
}

@Composable
internal fun CountdownLoadingSkeleton(modifier: Modifier, showDate: Boolean) {
    val progress = rememberLoadingShimmerProgress("overview-countdown-shimmer")
    Surface(modifier.fillMaxSize().clearAndSetSemantics { contentDescription = "倒數行程載入中" },
        shape = PocketVisual.metricShape, color = MaterialTheme.colorScheme.tertiaryContainer) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            ShimmerBlock(progress, Modifier.fillMaxWidth(0.55f).height(30.dp))
            ShimmerBlock(progress, Modifier.fillMaxWidth(0.75f).height(16.dp))
            if (showDate) ShimmerBlock(progress, Modifier.fillMaxWidth(0.4f).height(12.dp))
        }
    }
}

@Composable
internal fun OverviewHeroSkeleton() {
    val progress = rememberLoadingShimmerProgress("overview-course-shimmer")
    Surface(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "課表摘要載入中" },
        shape = PocketVisual.heroShape, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            ShimmerBlock(progress, Modifier.fillMaxWidth(0.45f).height(16.dp))
            ShimmerBlock(progress, Modifier.fillMaxWidth(0.6f).height(36.dp))
            ShimmerBlock(progress, Modifier.fillMaxWidth(0.75f).height(16.dp))
        }
    }
}

@Composable
internal fun OverviewListSkeleton(rowCount: Int, label: String) {
    val progress = rememberLoadingShimmerProgress("overview-list-shimmer")
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(rowCount) { index ->
            Surface(shape = RoundedCornerShape(
                topStart = if (index == 0) 24.dp else 4.dp, topEnd = if (index == 0) 24.dp else 4.dp,
                bottomStart = if (index == rowCount - 1) 24.dp else 4.dp, bottomEnd = if (index == rowCount - 1) 24.dp else 4.dp,
            ), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().heightIn(min = 88.dp * fontScale).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ShimmerBlock(progress, Modifier.width(52.dp).height(48.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ShimmerBlock(progress, Modifier.fillMaxWidth(0.9f).height(18.dp))
                        ShimmerBlock(progress, Modifier.fillMaxWidth(0.6f).height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ShimmerBlock(progress: State<Float>, modifier: Modifier) {
    val base = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)
    Spacer(modifier.clip(MaterialTheme.shapes.small).background(base).drawBehind {
        val band = size.width * 0.55f
        val start = (size.width + band) * progress.value - band
        drawRect(Brush.linearGradient(listOf(highlight.copy(alpha = 0f), highlight, highlight.copy(alpha = 0f)),
            start = Offset(start, 0f), end = Offset(start + band, size.height)))
    })
}
