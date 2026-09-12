package com.clhs.score.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toDrawable
import com.clhs.score.R
import com.clhs.score.data.ThemeMode

data class ScoreSemanticColors(
    val positive: Color,
    val negative: Color,
    val warning: Color,
    val neutral: Color
)

internal val LightSemanticColors = ScoreSemanticColors(
    positive = Color(0xFF126738),
    negative = Color(0xFFBA1A1A),
    warning = Color(0xFF946C00),
    neutral = Color(0xFF6B7280)
)

internal val DarkSemanticColors = ScoreSemanticColors(
    positive = Color(0xFF81C995),
    negative = Color(0xFFFFB4AB),
    warning = Color(0xFFFDE293),
    neutral = Color(0xFF9CA3AF)
)

internal val LocalScoreSemanticColors = staticCompositionLocalOf { LightSemanticColors }

object ScoreTheme {
    val semanticColors: ScoreSemanticColors
        @Composable
        @ReadOnlyComposable
        get() = LocalScoreSemanticColors.current
}

internal val ScoreShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
    largeIncreased = RoundedCornerShape(32.dp),
)

internal val OutfitFontFamily = FontFamily(
    Font(R.font.outfit_bold_subset, FontWeight.Bold),
)

internal val LightColors = lightColorScheme(
    primary = Color(0xFF36618E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF194975),
    secondary = Color(0xFF535F70),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E3F7),
    onSecondaryContainer = Color(0xFF3B4858),
    tertiary = Color(0xFF6B5778),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF2DAFF),
    onTertiaryContainer = Color(0xFF523F5F),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFF8F9FF),
    surface = Color(0xFFF8F9FF),
    surfaceDim = Color(0xFFD8DAE0),
    surfaceBright = Color(0xFFF8F9FF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F3FA),
    surfaceContainer = Color(0xFFECEEF4),
    surfaceContainerHigh = Color(0xFFE6E8EE),
    surfaceContainerHighest = Color(0xFFE1E2E8),
    surfaceVariant = Color(0xFFDFE2EB),
    onBackground = Color(0xFF191C20),
    onSurface = Color(0xFF191C20),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC3C7CF),
    inverseSurface = Color(0xFF2E3135),
    inverseOnSurface = Color(0xFFEFF0F7),
    inversePrimary = Color(0xFFA0CAFD),
    scrim = Color.Black,
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFA0CAFD),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF194975),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF253140),
    secondaryContainer = Color(0xFF3B4858),
    onSecondaryContainer = Color(0xFFD7E3F7),
    tertiary = Color(0xFFD6BEE4),
    onTertiary = Color(0xFF3B2948),
    tertiaryContainer = Color(0xFF523F5F),
    onTertiaryContainer = Color(0xFFF2DAFF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111418),
    surface = Color(0xFF111418),
    surfaceDim = Color(0xFF111418),
    surfaceBright = Color(0xFF36393E),
    surfaceContainerLowest = Color(0xFF0B0E13),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF272A2F),
    surfaceContainerHighest = Color(0xFF32353A),
    surfaceVariant = Color(0xFF43474E),
    onBackground = Color(0xFFE1E2E8),
    onSurface = Color(0xFFE1E2E8),
    onSurfaceVariant = Color(0xFFC3C7CF),
    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E),
    inverseSurface = Color(0xFFE1E2E8),
    inverseOnSurface = Color(0xFF2E3135),
    inversePrimary = Color(0xFF36618E),
    scrim = Color.Black,
)

internal val AmoledDarkColors = DarkColors.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceBright = Color(0xFF252525),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF050505),
    surfaceContainer = Color(0xFF0A0A0A),
    surfaceContainerHigh = Color(0xFF141414),
    surfaceContainerHighest = Color(0xFF1E1E1E),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ScoreTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    amoledBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val useDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (useDark) {
                val base = dynamicDarkColorScheme(context)
                if (amoledBlack) {
                    base.copy(
                        background = Color.Black,
                        surface = Color.Black,
                        surfaceDim = Color.Black,
                        surfaceBright = Color(0xFF252525),
                        surfaceContainerLowest = Color.Black,
                        surfaceContainerLow = Color(0xFF050505),
                        surfaceContainer = Color(0xFF0A0A0A),
                        surfaceContainerHigh = Color(0xFF141414),
                        surfaceContainerHighest = Color(0xFF1E1E1E),
                    )
                } else base
            } else {
                dynamicLightColorScheme(context)
            }
        }
        useDark && amoledBlack -> AmoledDarkColors
        useDark -> DarkColors
        else -> LightColors
    }

    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val window = (view.context as android.app.Activity).window
            window.setBackgroundDrawable(colorScheme.background.toArgb().toDrawable())
            androidx.core.view.WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !useDark
            androidx.core.view.WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !useDark
        }
    }

    val semanticColors = if (useDark) DarkSemanticColors else LightSemanticColors

    androidx.compose.runtime.CompositionLocalProvider(
        LocalScoreSemanticColors provides semanticColors
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            shapes = ScoreShapes,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = colorScheme.background,
                content = content,
            )
        }
    }
}
