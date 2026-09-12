package com.clhs.score.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clhs.score.data.GradeAnalysis
import com.clhs.score.data.GradeReport
import com.clhs.score.data.SubjectScore
import com.clhs.score.data.gradeLevel
import com.clhs.score.data.shortenSubjectName
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin


@Composable
fun AnalysisSection(report: GradeReport, analysis: GradeAnalysis) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "分析模組",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        ChartCard(
            title = "雷達分析",
        ) {
            RadarScoreChart(subjects = report.subjects)
        }
        ChartCard(
            title = "成績比較",
        ) {
            BarScoreChart(subjects = report.subjects)
        }
        StandardsTable(analysis = analysis)
    }
}

@Composable
private fun ChartCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
            Spacer(modifier = Modifier.height(8.dp))
            Legend()
        }
    }
}

@Composable
private fun Legend() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendItem(color = MaterialTheme.colorScheme.primary, label = "我的成績")
        LegendItem(color = MaterialTheme.colorScheme.outline, label = "班級平均")
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, RoundedCornerShape(4.dp)),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun RadarScoreChart(subjects: List<SubjectScore>) {
    if (subjects.size < 3) {
        EmptyChartMessage("至少需要三個科目才能繪製雷達圖")
        return
    }
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)
    val myScoreColor = MaterialTheme.colorScheme.primary
    val avgScoreColor = MaterialTheme.colorScheme.outline
    val dotInnerColor = MaterialTheme.colorScheme.surface
    val labels = remember(subjects) { subjects.map { chartSubjectLabel(it.subjectName) } }
    val chartSummary = remember(subjects, labels) {
        subjects.zip(labels).joinToString(
            prefix = "雷達圖。各科我的成績與班級平均：",
            separator = "；",
        ) { (subject, label) ->
            "$label ${subject.scoreValue.formatScore()}，班平均 ${subject.classAverageValue.formatScore()}"
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
            .semantics { contentDescription = chartSummary },
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = min(size.width, size.height) * 0.28f
        val count = subjects.size
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor.toArgb()
            textAlign = Paint.Align.CENTER
            textSize = 12.dp.toPx()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        for (ring in 1..3) {
            val ringRadius = radius * ring / 3f
            drawPath(
                path = radarPath(center, ringRadius, count) { 1f },
                color = gridColor,
                style = Stroke(width = 1.dp.toPx()),
            )
        }
        for (i in 0 until count) {
            val point = radarPoint(center, radius, i, count, 1f)
            drawLine(gridColor, center, point, strokeWidth = 1.dp.toPx())
            val labelPoint = radarPoint(center, radius + 42.dp.toPx(), i, count, 1f)
            drawContext.canvas.nativeCanvas.drawText(labels[i], labelPoint.x, labelPoint.y + 4.dp.toPx(), textPaint)
        }

        val avgPath = radarPath(center, radius, count) { index -> (subjects[index].classAverageValue / 100.0).toFloat() }
        drawPath(avgPath, avgScoreColor.copy(alpha = 0.08f))
        drawPath(avgPath, avgScoreColor.copy(alpha = 0.60f), style = Stroke(width = 1.5.dp.toPx()))

        val myPath = radarPath(center, radius, count) { index -> (subjects[index].scoreValue / 100.0).toFloat() }
        drawPath(myPath, myScoreColor.copy(alpha = 0.28f))
        drawPath(myPath, myScoreColor, style = Stroke(width = 3.5.dp.toPx()))
        subjects.forEachIndexed { index, subject ->
            val point = radarPoint(center, radius, index, count, (subject.scoreValue / 100.0).toFloat())
            drawCircle(myScoreColor, radius = 4.dp.toPx(), center = point)
            drawCircle(dotInnerColor, radius = 2.dp.toPx(), center = point)
        }
    }
}

@Composable
fun BarScoreChart(subjects: List<SubjectScore>) {
    if (subjects.isEmpty()) {
        EmptyChartMessage("沒有可繪製的科目成績")
        return
    }
    val focusSubject = remember(subjects) { subjects.maxByOrNull { abs(it.diffValue) }?.subjectName }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        subjects.forEach { subject ->
            HorizontalSubjectBar(
                subject = subject,
                focused = subject.subjectName == focusSubject,
            )
        }
    }
}

@Composable
private fun HorizontalSubjectBar(subject: SubjectScore, focused: Boolean) {
    val container = if (focused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val labelColor = if (focused) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(container, MaterialTheme.shapes.medium)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                modifier = Modifier.weight(1f),
                text = chartSubjectLabel(subject.subjectName, maxChars = 8),
                style = MaterialTheme.typography.titleMedium,
                color = labelColor,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${"%.0f".format(subject.scoreValue)} / 均 ${"%.0f".format(subject.classAverageValue)}",
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ScoreBarLine(label = "我", score = subject.scoreValue, color = MaterialTheme.colorScheme.primary, trackAlpha = 0.18f)
        ScoreBarLine(label = "均", score = subject.classAverageValue, color = MaterialTheme.colorScheme.outline, trackAlpha = 0.10f)
    }
}

@Composable
private fun ScoreBarLine(label: String, score: Double, color: Color, trackAlpha: Float) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            modifier = Modifier.width(22.dp),
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(10.dp)
                .background(color.copy(alpha = trackAlpha), CircleShape),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((score / 100.0).toFloat().coerceIn(0f, 1f))
                    .height(10.dp)
                    .background(color, CircleShape),
            )
        }
    }
}

@Composable
private fun StandardsTable(analysis: GradeAnalysis) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "五標分析",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            analysis.subjects.forEach { subjectAnalysis ->
                val subject = subjectAnalysis.subject
                val standard = subjectAnalysis.standard ?: return@forEach
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(shortenSubjectName(subject.subjectName), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                subjectAnalysis.standardDistance ?: gradeLevel(subject.scoreValue, standard),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = subject.scoreValue.formatScore(),
                            style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    StandardPositionBar(score = subject.scoreValue, standard = standard)
                    Text(
                        text = "${subjectPercentLabel(subject.classRank, subject.classRankCount)} ・ 班排 ${formatRank(subject.classRank, subject.classRankCount)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyChartMessage(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun radarPath(
    center: Offset,
    radius: Float,
    count: Int,
    valueAt: (Int) -> Float,
): Path {
    val path = Path()
    for (index in 0 until count) {
        val point = radarPoint(center, radius, index, count, valueAt(index).coerceIn(0f, 1.2f))
        if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    path.close()
    return path
}

private fun radarPoint(center: Offset, radius: Float, index: Int, count: Int, scale: Float): Offset {
    val angle = Math.toRadians(-90.0 + 360.0 * index / count)
    return Offset(
        x = center.x + cos(angle).toFloat() * radius * scale,
        y = center.y + sin(angle).toFloat() * radius * scale,
    )
}

private fun Double?.formatScore(): String = this?.let { "%.2f".format(it) } ?: "--"


private fun chartSubjectLabel(subjectName: String, maxChars: Int = 5): String {
    val cleaned = shortenSubjectName(subjectName)
        .replace("普通型", "")
        .replace("進階", "")
        .replace("（", "(")
        .substringBefore("(")
        .trim()
    return if (cleaned.length <= maxChars) cleaned else cleaned.take(maxChars)
}
