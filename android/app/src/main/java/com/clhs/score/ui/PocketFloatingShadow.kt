package com.clhs.score.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

@Composable
internal fun Modifier.pocketFloatingShadow(shape: Shape, fraction: Float = 1f): Modifier = dropShadow(
    shape = shape,
    shadow = Shadow(
        radius = 8.dp,
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.16f * fraction.coerceIn(0f, 1f)),
        offset = DpOffset(0.dp, 2.dp),
    ),
)
