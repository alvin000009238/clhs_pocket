package com.clhs.score.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/** Feedback belongs to user callbacks, never recomposition or background work. */
@Composable
internal fun hapticClick(onClick: () -> Unit): () -> Unit {
    val haptics = LocalHapticFeedback.current
    return {
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        onClick()
    }
}

@Composable
internal fun hapticToggle(onChange: (Boolean) -> Unit): (Boolean) -> Unit {
    val haptics = LocalHapticFeedback.current
    return { checked ->
        haptics.performHapticFeedback(
            if (checked) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
        )
        onChange(checked)
    }
}
