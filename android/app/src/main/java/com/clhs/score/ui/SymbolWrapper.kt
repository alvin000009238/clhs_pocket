package com.clhs.score.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clhs.score.R

private val MaterialSymbolsRoundedOutline =
    FontFamily(Font(R.font.material_symbols_rounded_outline_subset))
private val MaterialSymbolsRoundedFilled =
    FontFamily(Font(R.font.material_symbols_rounded_filled_subset))

@Composable
fun OutlinedRoundedSymbol(
    icon: String,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = Color.Unspecified,
    contentDescription: String? = null,
) {
    MaterialSymbol(
        icon = icon,
        modifier = modifier,
        size = size,
        tint = tint,
        fontFamily = MaterialSymbolsRoundedOutline,
        contentDescription = contentDescription,
    )
}

@Composable
fun FilledRoundedSymbol(
    icon: String,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = Color.Unspecified,
    contentDescription: String? = null,
) {
    MaterialSymbol(
        icon = icon,
        modifier = modifier,
        size = size,
        tint = tint,
        fontFamily = MaterialSymbolsRoundedFilled,
        contentDescription = contentDescription,
    )
}

@Composable
private fun MaterialSymbol(
    icon: String,
    modifier: Modifier,
    size: Dp,
    tint: Color,
    fontFamily: FontFamily,
    contentDescription: String?,
) {
    val finalTint = if (tint == Color.Unspecified) LocalContentColor.current else tint
    val fontSize = with(LocalDensity.current) { size.toSp() }
    val lineHeight = fontSize * 1.25f
    val textHeight = size * 1.25f
    val semanticModifier = Modifier.clearAndSetSemantics {
        if (contentDescription != null) {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    }

    Box(
        modifier = modifier
            .then(semanticModifier)
            .size(size),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = icon,
            modifier = Modifier.requiredHeight(textHeight),
            color = finalTint,
            fontFamily = fontFamily,
            fontSize = fontSize,
            lineHeight = lineHeight,
            textAlign = TextAlign.Center,
            style = TextStyle(
                fontWeight = FontWeight.Normal,
                fontSynthesis = FontSynthesis.None,
                letterSpacing = 0.sp,
                fontFeatureSettings = "liga",
                baselineShift = BaselineShift(-0.05f),
            ),
            maxLines = 1,
            softWrap = false,
        )
    }
}
