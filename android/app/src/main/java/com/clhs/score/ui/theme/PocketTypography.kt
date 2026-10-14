package com.clhs.score.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.clhs.score.R

private val HuninnFontFamily = FontFamily(Font(R.font.huninn_regular, FontWeight.Normal))

// Huninn has one authentic weight; hierarchy comes from roles, size and the text face.
private fun TextStyle.roundedHeading() = copy(
    fontFamily = HuninnFontFamily,
    fontWeight = FontWeight.Normal,
    fontSynthesis = FontSynthesis.None,
    letterSpacing = 0.sp,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal val PocketTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.roundedHeading(),
        displayMedium = base.displayMedium.roundedHeading(),
        displaySmall = base.displaySmall.roundedHeading(),
        headlineLarge = base.headlineLarge.roundedHeading(),
        headlineMedium = base.headlineMedium.roundedHeading(),
        headlineSmall = base.headlineSmall.roundedHeading(),
        titleLarge = base.titleLarge.roundedHeading(),
        displayLargeEmphasized = base.displayLargeEmphasized.roundedHeading(),
        displayMediumEmphasized = base.displayMediumEmphasized.roundedHeading(),
        displaySmallEmphasized = base.displaySmallEmphasized.roundedHeading(),
        headlineLargeEmphasized = base.headlineLargeEmphasized.roundedHeading(),
        headlineMediumEmphasized = base.headlineMediumEmphasized.roundedHeading(),
        headlineSmallEmphasized = base.headlineSmallEmphasized.roundedHeading(),
        titleLargeEmphasized = base.titleLargeEmphasized.roundedHeading(),
    )
}
