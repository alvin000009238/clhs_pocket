package com.clhs.score.shared.math

/**
 * Exact fixed-point decimal for platforms without `java.math.BigDecimal`.
 *
 * Mirrors the `BigDecimal` operations used by shared grade logic. Values are held in a `Long`,
 * which covers bounded score arithmetic; anything larger fails with [ArithmeticException]
 * instead of silently losing precision.
 */
class PortableDecimal private constructor(
    private val unscaled: Long,
    private val scaleValue: Int,
) : Comparable<PortableDecimal> {
    constructor(value: Int) : this(value.toLong(), 0)

    constructor(value: String) : this(parse(value))

    private constructor(parsed: PortableDecimal) : this(parsed.unscaled, parsed.scaleValue)

    fun scale(): Int = scaleValue

    fun precision(): Int {
        var digits = 1
        var rest = if (unscaled < 0) -(unscaled / 10) else unscaled / 10
        while (rest != 0L) {
            digits++
            rest /= 10
        }
        return digits
    }

    fun add(other: PortableDecimal): PortableDecimal {
        val scale = maxOf(scaleValue, other.scaleValue)
        return PortableDecimal(addExact(unscaledAt(scale), other.unscaledAt(scale)), scale)
    }

    fun subtract(other: PortableDecimal): PortableDecimal = add(other.negate())

    fun negate(): PortableDecimal = PortableDecimal(multiplyExact(unscaled, -1), scaleValue)

    fun multiply(other: PortableDecimal): PortableDecimal =
        PortableDecimal(multiplyExact(unscaled, other.unscaled), scaleValue + other.scaleValue)

    fun divide(divisor: PortableDecimal, scale: Int, roundingMode: RoundingMode): PortableDecimal {
        if (divisor.unscaled == 0L) throw ArithmeticException("Division by zero")
        if (scale < 0) throw ArithmeticException("Negative scale")
        val shift = scale - scaleValue + divisor.scaleValue
        val numerator = if (shift >= 0) multiplyExact(unscaled, pow10(shift)) else unscaled
        val denominator = if (shift >= 0) divisor.unscaled else multiplyExact(divisor.unscaled, pow10(-shift))
        return PortableDecimal(divideRounded(numerator, denominator, roundingMode), scale)
    }

    fun setScale(newScale: Int, roundingMode: RoundingMode): PortableDecimal {
        if (newScale < 0) throw ArithmeticException("Negative scale")
        return when {
            newScale == scaleValue -> this
            newScale > scaleValue -> PortableDecimal(unscaledAt(newScale), newScale)
            else -> PortableDecimal(divideRounded(unscaled, pow10(scaleValue - newScale), roundingMode), newScale)
        }
    }

    fun movePointLeft(n: Int): PortableDecimal {
        if (n < 0) throw ArithmeticException("Negative shift")
        return PortableDecimal(unscaled, scaleValue + n)
    }

    fun max(other: PortableDecimal): PortableDecimal = if (this >= other) this else other

    /** Unlike `BigDecimal`, the scale never drops below zero; plain strings are identical. */
    fun stripTrailingZeros(): PortableDecimal {
        var value = unscaled
        var scale = scaleValue
        while (scale > 0 && value % 10 == 0L) {
            value /= 10
            scale--
        }
        return PortableDecimal(value, scale)
    }

    fun toPlainString(): String {
        val negative = unscaled < 0
        val digits = (if (negative) unscaled.toString().substring(1) else unscaled.toString())
            .padStart(scaleValue + 1, '0')
        val whole = digits.substring(0, digits.length - scaleValue)
        val fraction = digits.substring(digits.length - scaleValue)
        return buildString {
            if (negative) append('-')
            append(whole)
            if (scaleValue > 0) append('.').append(fraction)
        }
    }

    fun toDouble(): Double = toPlainString().toDouble()

    override fun compareTo(other: PortableDecimal): Int {
        val scale = maxOf(scaleValue, other.scaleValue)
        return unscaledAt(scale).compareTo(other.unscaledAt(scale))
    }

    /** Like `BigDecimal.equals`, 2.0 and 2.00 are different values; use [compareTo] for magnitude. */
    override fun equals(other: Any?): Boolean =
        other is PortableDecimal && unscaled == other.unscaled && scaleValue == other.scaleValue

    override fun hashCode(): Int = 31 * unscaled.hashCode() + scaleValue

    override fun toString(): String = toPlainString()

    private fun unscaledAt(scale: Int): Long = multiplyExact(unscaled, pow10(scale - scaleValue))

    companion object {
        private const val MAX_POWER = 18

        private fun pow10(exponent: Int): Long {
            if (exponent !in 0..MAX_POWER) throw ArithmeticException("Decimal overflow")
            var result = 1L
            repeat(exponent) { result *= 10 }
            return result
        }

        private fun addExact(a: Long, b: Long): Long {
            val result = a + b
            if (((a xor result) and (b xor result)) < 0) throw ArithmeticException("Decimal overflow")
            return result
        }

        private fun multiplyExact(a: Long, b: Long): Long {
            if (a == 0L || b == 0L) return 0
            val result = a * b
            if (result / b != a || (a == Long.MIN_VALUE && b == -1L) || (b == Long.MIN_VALUE && a == -1L)) {
                throw ArithmeticException("Decimal overflow")
            }
            return result
        }

        private fun divideRounded(numerator: Long, denominator: Long, mode: RoundingMode): Long {
            val quotient = numerator / denominator
            val remainder = numerator % denominator
            if (remainder == 0L) return quotient
            val negative = (numerator < 0) != (denominator < 0)
            val absRemainder = if (remainder < 0) -remainder else remainder
            val absDenominator = if (denominator < 0) -denominator else denominator
            // Compare 2 * remainder with the divisor without overflowing.
            val half = absRemainder.compareTo(absDenominator - absRemainder)
            val awayFromZero = when (mode) {
                RoundingMode.UP -> true
                RoundingMode.DOWN -> false
                RoundingMode.CEILING -> !negative
                RoundingMode.FLOOR -> negative
                RoundingMode.HALF_UP -> half >= 0
                RoundingMode.HALF_DOWN -> half > 0
                RoundingMode.HALF_EVEN -> half > 0 || (half == 0 && quotient % 2 != 0L)
                else -> throw ArithmeticException("Rounding necessary")
            }
            return if (!awayFromZero) quotient else addExact(quotient, if (negative) -1 else 1)
        }

        /** Accepts the plain and exponent forms produced by `Double.toString()`. */
        private fun parse(text: String): PortableDecimal {
            val exponentIndex = text.indexOfFirst { it == 'e' || it == 'E' }
            val mantissa = if (exponentIndex >= 0) text.substring(0, exponentIndex) else text
            val exponent = if (exponentIndex >= 0) {
                text.substring(exponentIndex + 1).toIntOrNull() ?: throw NumberFormatException(text)
            } else {
                0
            }
            val negative = mantissa.startsWith('-')
            val unsigned = if (negative || mantissa.startsWith('+')) mantissa.substring(1) else mantissa
            val point = unsigned.indexOf('.')
            val whole = if (point >= 0) unsigned.substring(0, point) else unsigned
            val fraction = if (point >= 0) unsigned.substring(point + 1) else ""
            val digits = whole + fraction
            if (digits.isEmpty() || digits.any { it !in '0'..'9' }) throw NumberFormatException(text)
            var value = 0L
            for (digit in digits) {
                value = addExact(multiplyExact(value, 10), (digit - '0').toLong())
            }
            if (negative) value = -value
            val scale = fraction.length - exponent
            return if (scale >= 0) {
                if (scale > MAX_POWER && value != 0L) throw ArithmeticException("Decimal overflow")
                PortableDecimal(value, scale)
            } else {
                PortableDecimal(multiplyExact(value, pow10(-scale)), 0)
            }
        }

        fun valueOf(value: Double): PortableDecimal {
            if (value.isNaN() || value.isInfinite()) throw NumberFormatException("Infinite or NaN")
            return PortableDecimal(value.toString())
        }

        /** Java `%.nf` semantics: HALF_UP on the shortest decimal form of the double. */
        fun formatFixed(value: Double, digits: Int): String {
            if (value.isNaN()) return "NaN"
            if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
            val text = valueOf(value).setScale(digits, RoundingMode.HALF_UP).toPlainString()
            return if (value < 0 && !text.startsWith('-')) "-$text" else text
        }
    }
}
