package com.clhs.score.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** A distinct silhouette for focal content; dense data keeps the regular theme shapes. */
object PocketVisual {
    val heroShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomEnd = 32.dp, bottomStart = 12.dp)
    val metricShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomEnd = 12.dp, bottomStart = 24.dp)
}
