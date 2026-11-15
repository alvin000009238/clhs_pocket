package com.clhs.score.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.MeshGradientPainter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

@Composable
internal fun Modifier.pocketMeshBackground(base: Color = MaterialTheme.colorScheme.surfaceContainerLow): Modifier {
    val colors = MaterialTheme.colorScheme
    val sky = lerp(base, colors.primaryContainer, 0.45f)
    val glow = lerp(base, colors.tertiaryContainer, 0.3f)
    val painter = remember(base, sky, glow) {
        MeshGradientPainter(1, 1) {
            setVertex(0, 0, Offset(0f, 0f), sky)
            setVertex(0, 1, Offset(1f, 0f), base)
            setVertex(1, 0, Offset(0f, 1f), base)
            setVertex(1, 1, Offset(1f, 1f), glow)
        }
    }
    return paint(painter, sizeToIntrinsics = false)
}
