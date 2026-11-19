@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package com.clhs.score.shared.math

actual enum class RoundingMode { UP, DOWN, CEILING, FLOOR, HALF_UP, HALF_DOWN, HALF_EVEN }

actual typealias BigDecimal = PortableDecimal

actual fun decimalValueOf(value: Double): BigDecimal = PortableDecimal.valueOf(value)

actual fun BigDecimal.toDoubleValue(): Double = toDouble()

actual fun formatLocalizedFixed(value: Double, digits: Int): String = PortableDecimal.formatFixed(value, digits)

actual fun formatFixed(value: Double, digits: Int): String = PortableDecimal.formatFixed(value, digits)
