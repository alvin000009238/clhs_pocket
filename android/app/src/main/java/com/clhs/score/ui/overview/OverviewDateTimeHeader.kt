package com.clhs.score.ui.overview

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val headerTimeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.TAIWAN)
private val headerDateFormat = DateTimeFormatter.ofPattern("M/d", Locale.TAIWAN)

/** One surface morphs from the first content card into a floating clock; it never owns the viewport. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun OverviewDateTimeHeader(
    now: LocalDateTime, scrollOffset: Float, expandedWidth: Dp, expandedLeft: Dp,
    expandedTop: Dp, expandedHeight: Dp, dockTop: Dp, dockWidth: Dp,
    modifier: Modifier = Modifier,
    forceCompact: Boolean = false,
) {
    val density = LocalDensity.current
    val compactStyle = if (dockWidth < 128.dp) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium
    val compactLineHeight = with(density) { compactStyle.lineHeight.toDp() }
    val compactHeight = (compactLineHeight + 16.dp).coerceAtLeast(48.dp)
    // Dock when the two bottom edges meet, so shrinking does not reveal an empty card-sized slot.
    val travel = (expandedTop + expandedHeight - dockTop - compactHeight).coerceAtLeast(0.dp)
    var docked by remember { mutableStateOf(with(density) { scrollOffset > 0f && scrollOffset >= travel.toPx() }) }
    LaunchedEffect(scrollOffset, density, travel) {
        with(density) {
            if (scrollOffset > 0f && scrollOffset >= travel.toPx()) docked = true
            else if (scrollOffset <= (travel - 16.dp).coerceAtLeast(0.dp).toPx()) docked = false
        }
    }
    // A bouncy spring crosses the clamped endpoint, then widens the pill again on its return swing.
    val animated by animateFloatAsState(if (docked || forceCompact) 1f else 0f,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "date-card-morph")
    val fraction = if (forceCompact) 1f else animated.coerceIn(0f, 1f)
    val headerScroll = with(density) { scrollOffset.toDp() }.coerceIn(0.dp, travel)
    val height = lerp(expandedHeight, compactHeight, fraction)
    val width = lerp(expandedWidth, dockWidth, fraction)
    val time = now.format(headerTimeFormat)
    val date = "${now.monthValue} 月 ${now.dayOfMonth} 日"
    val weekday = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.TAIWAN)
    val textMeasurer = rememberTextMeasurer()
    val compactPrefix = "${now.format(headerDateFormat)}  "
    val compactText = compactPrefix + time
    val (fittedStyle, compactLayout) = remember(textMeasurer, compactText, compactStyle, dockWidth, density) {
        var style = compactStyle.copy(fontFeatureSettings = "tnum")
        var layout = textMeasurer.measure(compactText, style, maxLines = 1, softWrap = false)
        val available = with(density) { (dockWidth - 24.dp).toPx() }
        while (layout.size.width > available && style.fontSize > 10.sp) {
            style = style.copy(fontSize = (style.fontSize.value - 1f).coerceAtLeast(10f).sp)
            layout = textMeasurer.measure(compactText, style, maxLines = 1, softWrap = false)
        }
        style to layout
    }
    val timeStyle = lerp(MaterialTheme.typography.displaySmallEmphasized, fittedStyle, fraction)
        .copy(fontFeatureSettings = "tnum")
    val timeLayout = textMeasurer.measure(time, timeStyle, maxLines = 1, softWrap = false)
    val expandedTime = textMeasurer.measure(time, MaterialTheme.typography.displaySmallEmphasized
        .copy(fontFeatureSettings = "tnum"), maxLines = 1, softWrap = false)
    val compactLeft = with(density) { (dockWidth.toPx() - compactLayout.size.width).toDp() / 2 }
    val prefixWidth = with(density) { compactLayout.getHorizontalPosition(compactPrefix.length, true).toDp() }
    val timeWidth = with(density) { expandedTime.size.width.toDp() }
    Surface(
        modifier = Modifier.offset(lerp(expandedLeft, 16.dp, fraction),
            lerp(expandedTop - headerScroll, dockTop, fraction))
            .size(width, height).then(modifier).clearAndSetSemantics { contentDescription = "$date，$weekday，$time" },
        shape = RoundedCornerShape(lerp(28.dp, height / 2, fraction)),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 3.dp * fraction,
    ) {
        Box {
            // Keep one clock throughout the morph; swapping faded text changes its position mid-flight.
            Text(time,
                modifier = Modifier.offset(lerp(20.dp, compactLeft + prefixWidth, fraction),
                    (height - with(density) { timeLayout.size.height.toDp() }) / 2)
                    .requiredSize(with(density) { timeLayout.size.width.toDp() },
                        with(density) { timeLayout.size.height.toDp() }),
                style = timeStyle, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            Column(Modifier.offset { IntOffset((timeWidth + 40.dp).roundToPx(),
                ((expandedHeight - 46.dp * density.fontScale) / 2).roundToPx()) }
                .width((expandedWidth - timeWidth - 60.dp).coerceAtLeast(1.dp)).alpha((1f - fraction * 2).coerceAtLeast(0f)),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(date, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text(weekday, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Text(compactPrefix,
                modifier = Modifier.offset(compactLeft,
                    (height - with(density) { compactLayout.size.height.toDp() }) / 2)
                    .requiredSize(prefixWidth, with(density) { compactLayout.size.height.toDp() })
                    .alpha(((fraction - .6f) / .4f).coerceIn(0f, 1f)),
                style = fittedStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
