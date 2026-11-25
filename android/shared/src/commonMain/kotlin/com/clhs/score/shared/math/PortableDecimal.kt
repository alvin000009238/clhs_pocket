package com.clhs.score.shared.math

import kotlin.math.abs
import kotlin.math.sign

/**
 * Exact fixed-point decimal for platforms without `java.math.BigDecimal`.
 *
 * Mirrors the `BigDecimal` operations used by shared grade logic. The unscaled value is a `Long` and
 * the scale is any value in `0..MAX_SCALE`, so every finite `Double` whose magnitude fits a `Long`
 * converts exactly. A result whose unscaled value does not fit fails with [ArithmeticException]
 * instead of silently losing precision; comparison, rescaling and [formatFixed] never overflow.
 */
class PortableDecimal private constructor(
    private val unscaled: Long,
    private val scaleValue: Int,
) : Comparable<PortableDecimal> {
    constructor(value: Int) : this(value.toLong(), 0)

    constructor(value: String) : this(parse(value))

    private constructor(parsed: PortableDecimal) : this(parsed.unscaled, parsed.scaleValue)

    init {
        if (scaleValue !in 0..MAX_SCALE) throw ArithmeticException("Scale out of range")
    }

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
        PortableDecimal(multiplyExact(unscaled, other.unscaled), checkedScale(scaleValue.toLong() + other.scaleValue))

    fun divide(divisor: PortableDecimal, scale: Int, roundingMode: RoundingMode): PortableDecimal {
        if (divisor.unscaled == 0L) throw ArithmeticException("Division by zero")
        if (scale < 0) throw ArithmeticException("Negative scale")
        val negative = (unscaled < 0) != (divisor.unscaled < 0)
        val numerator = magnitude(unscaled)
        val denominator = magnitude(divisor.unscaled)
        // Result unscaled value = numerator * 10^shift / denominator.
        val shift = scale.toLong() - scaleValue + divisor.scaleValue
        val quotient = if (shift >= 0) {
            scaledQuotient(numerator, denominator, shift)
        } else {
            reducedQuotient(numerator, denominator, -shift)
        }
        return PortableDecimal(quotient.round(negative, roundingMode), checkedScale(scale.toLong()))
    }

    fun setScale(newScale: Int, roundingMode: RoundingMode): PortableDecimal {
        if (newScale < 0) throw ArithmeticException("Negative scale")
        return when {
            newScale == scaleValue -> this
            newScale > scaleValue -> PortableDecimal(unscaledAt(checkedScale(newScale.toLong())), newScale)
            else -> PortableDecimal(
                reducedQuotient(magnitude(unscaled), 1UL, (scaleValue - newScale).toLong())
                    .round(unscaled < 0, roundingMode),
                newScale,
            )
        }
    }

    fun movePointLeft(n: Int): PortableDecimal {
        if (n < 0) throw ArithmeticException("Negative shift")
        return PortableDecimal(unscaled, checkedScale(scaleValue.toLong() + n))
    }

    fun max(other: PortableDecimal): PortableDecimal = if (this >= other) this else other

    /** Unlike `BigDecimal`, the scale never drops below zero; plain strings are identical. */
    fun stripTrailingZeros(): PortableDecimal {
        if (unscaled == 0L) return PortableDecimal(0, 0)
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
        val sign = unscaled.sign
        val otherSign = other.unscaled.sign
        if (sign != otherSign || sign == 0) return sign.compareTo(otherSign)
        if (scaleValue == other.scaleValue) return unscaled.compareTo(other.unscaled)
        val magnitudeOrder = if (scaleValue < other.scaleValue) {
            compareRescaled(magnitude(unscaled), other.scaleValue - scaleValue, magnitude(other.unscaled))
        } else {
            -compareRescaled(magnitude(other.unscaled), scaleValue - other.scaleValue, magnitude(unscaled))
        }
        return if (sign > 0) magnitudeOrder else -magnitudeOrder
    }

    /** Like `BigDecimal.equals`, 2.0 and 2.00 are different values; use [compareTo] for magnitude. */
    override fun equals(other: Any?): Boolean =
        other is PortableDecimal && unscaled == other.unscaled && scaleValue == other.scaleValue

    override fun hashCode(): Int = 31 * unscaled.hashCode() + scaleValue

    override fun toString(): String = toPlainString()

    private fun unscaledAt(scale: Int): Long {
        if (unscaled == 0L) return 0
        return multiplyExact(unscaled, pow10(scale - scaleValue))
    }

    /** Truncated quotient magnitude plus what rounding needs from the discarded fraction. */
    private class Quotient(val magnitude: ULong, val half: Int, val inexact: Boolean) {
        fun round(negative: Boolean, mode: RoundingMode): Long {
            val awayFromZero = inexact && when (mode) {
                RoundingMode.UP -> true
                RoundingMode.DOWN -> false
                RoundingMode.CEILING -> !negative
                RoundingMode.FLOOR -> negative
                RoundingMode.HALF_UP -> half >= 0
                RoundingMode.HALF_DOWN -> half > 0
                RoundingMode.HALF_EVEN -> half > 0 || (half == 0 && magnitude % 2UL != 0UL)
                else -> throw ArithmeticException("Rounding necessary")
            }
            val result = if (awayFromZero) magnitude + 1UL else magnitude
            if (result > if (negative) LONG_MIN_MAGNITUDE else Long.MAX_VALUE.toULong()) {
                throw ArithmeticException("Decimal overflow")
            }
            return if (negative) (0UL - result).toLong() else result.toLong()
        }
    }

    companion object {
        /** Largest scale accepted; bounds [toPlainString] while covering every `Double` and their products. */
        const val MAX_SCALE = 10_000

        private const val MAX_POWER = 18
        private const val MAX_UNSIGNED_POWER = 19
        private val LONG_MIN_MAGNITUDE: ULong = 1UL shl 63

        private fun checkedScale(scale: Long): Int {
            if (scale !in 0..MAX_SCALE.toLong()) throw ArithmeticException("Scale out of range")
            return scale.toInt()
        }

        private fun pow10(exponent: Int): Long {
            if (exponent !in 0..MAX_POWER) throw ArithmeticException("Decimal overflow")
            var result = 1L
            repeat(exponent) { result *= 10 }
            return result
        }

        private fun unsignedPow10(exponent: Int): ULong {
            var result = 1UL
            repeat(exponent) { result *= 10UL }
            return result
        }

        /** `|value|`, exact for `Long.MIN_VALUE`. */
        private fun magnitude(value: Long): ULong = if (value < 0) 0UL - value.toULong() else value.toULong()

        /** Compares `coarse * 10^shift` with `fine` for non-zero magnitudes without overflowing. */
        private fun compareRescaled(coarse: ULong, shift: Int, fine: ULong): Int {
            if (shift > MAX_UNSIGNED_POWER) return 1
            val factor = unsignedPow10(shift)
            return if (coarse > ULong.MAX_VALUE / factor) 1 else (coarse * factor).compareTo(fine)
        }

        private fun halfOrder(remainder: ULong, divisor: ULong): Int = remainder.compareTo(divisor - remainder)

        /** `numerator * 10^shift / denominator`, by long division so only the quotient must fit. */
        private fun scaledQuotient(numerator: ULong, denominator: ULong, shift: Long): Quotient {
            var quotient = numerator / denominator
            var remainder = numerator % denominator
            var digit = 0L
            while (digit < shift && (quotient != 0UL || remainder != 0UL)) {
                if (quotient > LONG_MIN_MAGNITUDE / 10UL) throw ArithmeticException("Decimal overflow")
                // remainder * 10 by repeated modular addition; both terms are below 2^63, so nothing wraps.
                var next = 0UL
                var nextDigit = 0UL
                repeat(10) {
                    next += remainder
                    if (next >= denominator) {
                        next -= denominator
                        nextDigit++
                    }
                }
                quotient = quotient * 10UL + nextDigit
                remainder = next
                digit++
            }
            return Quotient(quotient, halfOrder(remainder, denominator), remainder != 0UL)
        }

        /** `numerator / (denominator * 10^shift)` for `shift >= 1`, keeping the exact rounding direction. */
        private fun reducedQuotient(numerator: ULong, denominator: ULong, shift: Long): Quotient {
            val first = numerator / denominator
            val firstRemainder = numerator % denominator
            val inexactFirst = firstRemainder != 0UL
            // 10^20 / 2 exceeds every ULong, so the discarded fraction is below one half.
            if (shift > MAX_UNSIGNED_POWER) return Quotient(0UL, -1, first != 0UL || inexactFirst)
            val factor = unsignedPow10(shift.toInt())
            val rest = first % factor
            val half = factor / 2UL
            val order = when {
                rest < half -> -1
                rest > half -> 1
                inexactFirst -> 1
                else -> 0
            }
            return Quotient(first / factor, order, rest != 0UL || inexactFirst)
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
            // Accumulate negatively so Long.MIN_VALUE parses.
            var value = 0L
            for (digit in digits) {
                value = addExact(multiplyExact(value, 10), -(digit - '0').toLong())
            }
            if (!negative) value = multiplyExact(value, -1)
            val scale = fraction.length.toLong() - exponent
            return when {
                scale >= 0 -> PortableDecimal(value, checkedScale(scale))
                value == 0L -> PortableDecimal(0, 0)
                -scale > MAX_POWER -> throw ArithmeticException("Decimal overflow")
                else -> PortableDecimal(multiplyExact(value, pow10((-scale).toInt())), 0)
            }
        }

        /** Exact decimal of `value.toString()`, like `BigDecimal.valueOf(double)`; magnitudes beyond `Long` overflow. */
        fun valueOf(value: Double): PortableDecimal {
            if (value.isNaN() || value.isInfinite()) throw NumberFormatException("Infinite or NaN")
            return PortableDecimal(value.toString())
        }

        /**
         * Java `%.nf` semantics: HALF_UP on the shortest decimal form of the double, keeping the sign
         * of negative values (including `-0.0`) that round to zero. Works on the digit string, so
         * every finite double formats without overflow.
         */
        fun formatFixed(value: Double, digits: Int): String {
            require(digits >= 0) { "Negative precision" }
            if (value.isNaN()) return "NaN"
            if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
            val text = abs(value).toString()
            val exponentIndex = text.indexOfFirst { it == 'e' || it == 'E' }
            val mantissa = if (exponentIndex >= 0) text.substring(0, exponentIndex) else text
            val exponent = if (exponentIndex >= 0) text.substring(exponentIndex + 1).toInt() else 0
            val point = mantissa.indexOf('.').let { if (it < 0) mantissa.length else it }
            val significand = mantissa.replace(".", "")
            // Number of significand digits kept: those before the point plus `digits` after it.
            val keep = point + exponent + digits
            val rounded = when {
                keep < 0 -> ""
                else -> {
                    val head = significand.take(keep).padEnd(keep, '0')
                    if (keep < significand.length && significand[keep] >= '5') incrementDigits(head) else head
                }
            }
            val body = rounded.trimStart('0').padStart(digits + 1, '0')
            return buildString {
                if (value.toBits() < 0) append('-')
                append(body, 0, body.length - digits)
                if (digits > 0) append('.').append(body, body.length - digits, body.length)
            }
        }

        private fun incrementDigits(digits: String): String {
            val chars = digits.toCharArray()
            var index = chars.lastIndex
            while (index >= 0) {
                if (chars[index] == '9') {
                    chars[index] = '0'
                    index--
                } else {
                    chars[index] = chars[index] + 1
                    return chars.concatToString()
                }
            }
            return "1" + chars.concatToString()
        }
    }
}
