package com.clhs.score.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clhs.score.data.GradeStandard
import com.clhs.score.data.SubjectAnalysis
import com.clhs.score.data.deltaText
import com.clhs.score.data.scoreDistributions
import com.clhs.score.data.shortenSubjectName
import com.clhs.score.ui.theme.ScoreTheme
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SubjectCard(
    analysis: SubjectAnalysis,
    expanded: Boolean,
    bringIntoViewOnExpand: Boolean = false,
    onBringIntoViewHandled: () -> Unit = {},
    onToggle: () -> Unit,
) {
    val subject = analysis.subject
    val diffColor = diffColor(subject.diffValue)
    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    LaunchedEffect(expanded, bringIntoViewOnExpand) {
        if (expanded && bringIntoViewOnExpand) {
            withFrameNanos { }
            bringIntoViewRequester.bringIntoView()
            delay(320.milliseconds)
            bringIntoViewRequester.bringIntoView()
            onBringIntoViewHandled()
        }
    }

    Card(
        onClick = onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .padding(bottom = 16.dp)
            .semantics { stateDescription = if (expanded) "已展開" else "已收合" },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = shortenSubjectName(subject.subjectName),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "平均 ${"%.1f".format(subject.classAverageValue)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    )
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = subject.scoreDisplay.ifBlank { "%.1f".format(subject.scoreValue) },
                        color = scoreColor(subject.scoreValue),
                        style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${diffArrow(subject.diffValue)} ${signedValue(kotlin.math.abs(subject.diffValue))}",
                        color = diffColor,
                        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
                val buildRankString: (Int?, Int?, String) -> AnnotatedString = { rank, count, scope ->
                    val rankStr = formatRank(rank?.toDouble(), count, true)
                    val pctStr = subjectPercentLabel(rank, count, scope)
                    buildAnnotatedString {
                        append(rankStr)
                        if (rankStr != "--" && pctStr != "--") {
                            withStyle(SpanStyle(color = onSurfaceVariant, fontSize = 12.sp)) {
                                append(" • $pctStr")
                            }
                        }
                    }
                }

                InfoChip(
                    modifier = Modifier.weight(1f),
                    label = "班排",
                    value = buildRankString(subject.classRank, subject.classRankCount, "班級"),
                )

                if (subject.yearRank != null) {
                    InfoChip(
                        modifier = Modifier.weight(1f),
                        label = "校排",
                        value = buildRankString(subject.yearRank, subject.yearRankCount, "年級"),
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(
                    animationSpec = tween(
                        durationMillis = 140,
                        delayMillis = 40,
                        easing = LinearOutSlowInEasing,
                    ),
                ) + expandVertically(
                    expandFrom = Alignment.Top,
                    clip = false,
                    animationSpec = tween(
                        durationMillis = 300,
                        easing = FastOutSlowInEasing,
                    ),
                ),
                exit = fadeOut(
                    animationSpec = tween(
                        durationMillis = 90,
                        easing = FastOutSlowInEasing,
                    ),
                ) + shrinkVertically(
                    shrinkTowards = Alignment.Top,
                    clip = false,
                    animationSpec = tween(
                        durationMillis = 220,
                        easing = FastOutSlowInEasing,
                    ),
                ),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    analysis.standard?.let { standard ->
                        StandardPositionBar(
                            score = subject.scoreValue,
                            standard = standard,
                        )
                        DistributionBar(
                            score = subject.scoreValue,
                            standard = standard,
                        )
                    } ?: EmptySubjectDetail()

                    DetailRow(value = analysis.comparison?.let { deltaText("較上一考", it.scoreDelta, "分") } ?: "尚無上一考可比較")
                }
            }

            // Arrow at the bottom center of the card
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                OutlinedRoundedSymbol(
                    icon = if (expanded) "keyboard_arrow_up" else "keyboard_arrow_down",
                    contentDescription = if (expanded) "收合" else "展開",
                    size = 16.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun InfoChip(
    modifier: Modifier,
    label: String,
    value: String,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    InfoChip(
        modifier = modifier,
        label = label,
        value = AnnotatedString(value),
        containerColor = containerColor,
        labelColor = labelColor,
        valueColor = valueColor,
    )
}

@Composable
internal fun InfoChip(
    modifier: Modifier,
    label: String,
    value: AnnotatedString,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        modifier = modifier
            .background(containerColor, MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = labelColor)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = valueColor,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun DistributionBar(score: Double, standard: GradeStandard) {
    val distributions = scoreDistributions(score, standard)
    val displayDistributions = distributions.asReversed()
    val total = distributions.sumOf { it.count }.coerceAtLeast(1)
    val mine = distributions.firstOrNull { it.isMine }
    val weights = displayDistributions.map { (it.count.toFloat() / total).coerceAtLeast(0.02f) }
    val mineIndex = displayDistributions.indexOfFirst { it.isMine }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                modifier = Modifier.weight(1f),
                text = "分數分布",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = mine?.let { "我的級距 ${it.label}" } ?: "我的級距 --",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp),
        ) {
            val gap = 2.dp
            val markerSize = 8.dp
            Row(
                modifier = Modifier.fillMaxWidth().height(24.dp),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                displayDistributions.forEachIndexed { index, item ->
                    Box(
                        modifier = Modifier
                            .weight(weights[index])
                            .height(24.dp)
                            .background(distributionColor(item.label), RoundedCornerShape(5.dp)),
                    )
                }
            }
            if (mineIndex >= 0) {
                val weightTotal = weights.sum().coerceAtLeast(0.001f)
                val gapWidth = gap * (displayDistributions.size - 1).coerceAtLeast(0).toFloat()
                val weightedWidth = (maxWidth - gapWidth).coerceAtLeast(0.dp)
                val beforeWeight = weights.take(mineIndex).sum()
                val markerCenter = gap * mineIndex.toFloat() +
                    weightedWidth * ((beforeWeight + weights[mineIndex] / 2f) / weightTotal)
                val markerX = (markerCenter - markerSize / 2f)
                    .coerceIn(0.dp, (maxWidth - markerSize).coerceAtLeast(0.dp))
                Box(
                    modifier = Modifier
                        .offset(x = markerX)
                        .width(markerSize)
                        .height(markerSize)
                        .align(Alignment.CenterStart)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(999.dp))
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(999.dp)
                        ),
                )
            }
        }
        displayDistributions.forEach { item ->
            DistributionRow(item = item, total = total)
        }
    }
}

@Composable
private fun DistributionRow(item: com.clhs.score.data.ScoreDistribution, total: Int) {
    val percentFraction = (item.count.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    val percent = percentFraction * 100f
    val barShape = RoundedCornerShape(999.dp)
    val barColor = distributionColor(item.label)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            modifier = Modifier.width(58.dp),
            text = item.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(18.dp)
                .clip(barShape)
                .background(barColor.copy(alpha = 0.14f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(percentFraction)
                    .height(18.dp)
                    .background(barColor),
            )
            Text(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 10.dp),
                text = "${"%.0f".format(percent)}%",
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            modifier = Modifier.width(48.dp),
            text = "${item.count}人",
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (item.isMine) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
internal fun StandardPositionBar(score: Double, standard: GradeStandard) {
    val marks = listOfNotNull(
        standard.bottom?.let { StandardMarkSpec("底", it) },
        standard.back?.let { StandardMarkSpec("後", it) },
        standard.average?.let { StandardMarkSpec("均", it) },
        standard.front?.let { StandardMarkSpec("前", it) },
        standard.top?.let { StandardMarkSpec("頂", it) },
    )
    val low = marks.minOfOrNull { it.value } ?: 0.0
    val high = marks.maxOfOrNull { it.value }?.coerceAtLeast(low + 1.0) ?: 100.0
    val fraction = ((score - low) / (high - low)).coerceIn(0.0, 1.0).toFloat()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("班級五標", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(fraction.coerceAtLeast(0.001f))
                    .height(6.dp)
                    .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(999.dp)),
            )
            Box(
                modifier = Modifier
                    .width(12.dp)
                    .height(20.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(999.dp)),
            )
            Box(
                modifier = Modifier
                    .weight((1f - fraction).coerceAtLeast(0.001f))
                    .height(6.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(999.dp)),
            )
        }
        ProportionalStandardMarks(marks = marks, low = low, high = high)
    }
}

private data class StandardMarkSpec(
    val label: String,
    val value: Double,
)

@Composable
private fun ProportionalStandardMarks(marks: List<StandardMarkSpec>, low: Double, high: Double) {
    val range = (high - low).takeIf { it > 0.0 } ?: 1.0
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            marks.forEach { mark ->
                StandardMark(modifier = Modifier, label = mark.label, value = mark.value)
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { measurable ->
            measurable.measure(constraints.copy(minWidth = 0))
        }
        val width = constraints.maxWidth
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                val mark = marks[index]
                val fraction = ((mark.value - low) / range).coerceIn(0.0, 1.0)
                val x = (fraction * width - placeable.width / 2.0)
                    .roundToInt()
                    .coerceIn(0, (width - placeable.width).coerceAtLeast(0))
                placeable.placeRelative(x, 0)
            }
        }
    }
}

@Composable
private fun StandardMark(modifier: Modifier, label: String, value: Double?) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value.formatCompactScore(),
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun DetailRow(value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            modifier = Modifier.weight(1f),
            text = "上次成績",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = value,
            color = valueColor,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun EmptySubjectDetail() {
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .padding(12.dp),
        text = "尚無五標與分布資料",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun signedValue(value: Double): String = "%.1f".format(value)

private fun diffArrow(diff: Double): String = when {
    diff > 0.05 -> "⬆"
    diff < -0.05 -> "⬇"
    else -> "→"
}

@Composable
private fun diffColor(diff: Double): Color = when {
    diff > 0.05 -> ScoreTheme.semanticColors.positive
    diff < -0.05 -> ScoreTheme.semanticColors.negative
    else -> ScoreTheme.semanticColors.neutral
}

@Composable
private fun scoreColor(score: Double): Color = when {
    score >= 80.0 -> ScoreTheme.semanticColors.positive
    score >= 60.0 -> ScoreTheme.semanticColors.warning
    else -> ScoreTheme.semanticColors.negative
}

private fun Double?.formatCompactScore(): String = this?.let { "%.0f".format(it) } ?: "--"
