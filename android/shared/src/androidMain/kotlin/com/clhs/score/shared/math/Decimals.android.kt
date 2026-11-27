@file:Suppress("ACTUAL_WITHOUT_EXPECT", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package com.clhs.score.shared.math

import java.util.Locale

actual typealias RoundingMode = java.math.RoundingMode

actual typealias BigDecimal = java.math.BigDecimal

actual fun decimalValueOf(value: Double): BigDecimal = java.math.BigDecimal.valueOf(value)

actual fun BigDecimal.toDoubleValue(): Double = toDouble()

actual fun formatLocalizedFixed(value: Double, digits: Int): String = "%.${digits}f".format(value)

actual fun formatFixed(value: Double, digits: Int): String = String.format(Locale.US, "%.${digits}f", value)
