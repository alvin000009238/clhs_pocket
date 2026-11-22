@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package com.clhs.score.shared.math

/** `java.math.RoundingMode` on Android, so existing Android callers keep their exact types. */
expect enum class RoundingMode { UP, DOWN, CEILING, FLOOR, HALF_UP, HALF_DOWN, HALF_EVEN }

/** `java.math.BigDecimal` on Android; [PortableDecimal] on platforms without it. */
expect class BigDecimal : Comparable<BigDecimal> {
    constructor(value: Int)
    constructor(value: String)

    fun scale(): Int
    fun precision(): Int
    fun add(other: BigDecimal): BigDecimal
    fun subtract(other: BigDecimal): BigDecimal
    fun multiply(other: BigDecimal): BigDecimal
    fun divide(divisor: BigDecimal, scale: Int, roundingMode: RoundingMode): BigDecimal
    fun setScale(newScale: Int, roundingMode: RoundingMode): BigDecimal
    fun movePointLeft(n: Int): BigDecimal
    fun max(other: BigDecimal): BigDecimal
    fun stripTrailingZeros(): BigDecimal
    fun toPlainString(): String
    override fun compareTo(other: BigDecimal): Int
}

operator fun BigDecimal.plus(other: BigDecimal): BigDecimal = add(other)
operator fun BigDecimal.minus(other: BigDecimal): BigDecimal = subtract(other)
operator fun BigDecimal.times(other: BigDecimal): BigDecimal = multiply(other)

val DECIMAL_ZERO: BigDecimal get() = BigDecimal(0)

/** Same contract as `BigDecimal.valueOf(double)`. */
expect fun decimalValueOf(value: Double): BigDecimal

expect fun BigDecimal.toDoubleValue(): Double

/** Parses a plain non-negative decimal such as `87.5`; callers validate the shape first. */
fun String.toPlainDecimalOrNull(): BigDecimal? = try {
    BigDecimal(this)
} catch (_: NumberFormatException) {
    null
} catch (_: ArithmeticException) {
    null
}

/** `"%.nf".format(value)` in the user's locale on Android. */
expect fun formatLocalizedFixed(value: Double, digits: Int): String

/** `String.format(Locale.US, "%.nf", value)`. */
expect fun formatFixed(value: Double, digits: Int): String
