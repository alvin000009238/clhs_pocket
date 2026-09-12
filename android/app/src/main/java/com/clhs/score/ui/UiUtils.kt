package com.clhs.score.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import kotlin.math.floor

fun formatRank(rank: Double?, count: Int?, showCount: Boolean): String {
    if (rank == null) return "--"
    val rankText = floor(rank).toInt().toString()
    return if (showCount && count != null && count > 0) "$rankText/$count" else rankText
}

fun formatRank(rank: Int?, count: Int?, emptyValue: String = "--"): String {
    if (rank == null) return emptyValue
    if (count == null || count <= 0) return "$rank"
    return "$rank / $count"
}

fun subjectPercentLabel(rank: Int?, count: Int?, scope: String = "班級"): String {
    if (rank == null || count == null || count <= 0) return "--"
    val percent = ((rank.toDouble() / count) * 100.0).toInt().coerceIn(1, 100)
    return "${scope}前 $percent%"
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AccountIconButton(
    onClick: () -> Unit,
    showUpdateBadge: Boolean = false,
) {
    IconButton(onClick = onClick, shapes = IconButtonDefaults.shapes()) {
        Box {
            OutlinedRoundedSymbol(
                icon = "account_circle",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                contentDescription = "個人",
            )
            if (showUpdateBadge) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape),
                )
            }
        }
    }
}

fun distributionColor(label: String): Color = when (label) {
    "90-100" -> Color(0xFF4F8F63)
    "80-89" -> Color(0xFF4B8F86)
    "70-79" -> Color(0xFF5A789A)
    "60-69" -> Color(0xFFC47A2C)
    "50-59" -> Color(0xFFC26A45)
    else -> Color(0xFFB75E62)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RootTopAppBar(
    title: String,
    subtitle: String? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    )
}
