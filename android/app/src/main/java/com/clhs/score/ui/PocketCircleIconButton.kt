package com.clhs.score.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun PocketCircleIconButton(
    icon: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(48.dp),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        OutlinedRoundedSymbol(icon, tint = MaterialTheme.colorScheme.onSurfaceVariant,
            contentDescription = contentDescription)
    }
}

@Composable
internal fun PocketBackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    PocketCircleIconButton("arrow_back", "返回", onBack, modifier)
}
