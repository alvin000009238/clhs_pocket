package com.clhs.score.shared.math

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PortableDecimalTest {
    @Test
    fun arithmeticKeepsExactScale() {
        val sum = PortableDecimal("87.5").add(PortableDecimal("0.25"))
        assertEquals("87.75", sum.toPlainString())
        assertEquals(2, sum.scale())
        assertEquals("-0.05", PortableDecimal("0.20").subtract(PortableDecimal("0.25")).toPlainString())
        assertEquals("350.0", PortableDecimal("87.5").multiply(PortableDecimal(4)).toPlainString())
        assertEquals(4, PortableDecimal("12.34").precision())
        assertEquals(1, PortableDecimal("0").precision())
    }

    @Test
    fun divisionAndRescalingRoundLikeBigDecimal() {
        val third = PortableDecimal(100).divide(PortableDecimal(3), 12, RoundingMode.HALF_UP)
        assertEquals("33.333333333333", third.toPlainString())
        assertEquals("66.67", PortableDecimal(200).divide(PortableDecimal(3), 2, RoundingMode.HALF_UP).toPlainString())
        assertEquals("66.67", PortableDecimal("133.321").divide(PortableDecimal(2), 2, RoundingMode.CEILING).toPlainString())
        assertEquals("-66.66", PortableDecimal("-133.321").divide(PortableDecimal(2), 2, RoundingMode.CEILING).toPlainString())
        assertEquals("2.5", PortableDecimal("2.45").setScale(1, RoundingMode.HALF_UP).toPlainString())
        assertEquals("2.4", PortableDecimal("2.45").setScale(1, RoundingMode.HALF_EVEN).toPlainString())
        assertEquals("2.4", PortableDecimal("2.45").setScale(1, RoundingMode.HALF_DOWN).toPlainString())
        assertEquals("-2.5", PortableDecimal("-2.45").setScale(1, RoundingMode.HALF_UP).toPlainString())
        assertEquals("2.450", PortableDecimal("2.45").setScale(3, RoundingMode.HALF_UP).toPlainString())
        assertEquals("0.005", PortableDecimal("0.5").movePointLeft(2).toPlainString())
        assertEquals("87.5", PortableDecimal("87.5000").stripTrailingZeros().toPlainString())
        assertEquals("100", PortableDecimal("100.00").stripTrailingZeros().toPlainString())
    }

    @Test
    fun comparisonIgnoresScaleButEqualityDoesNot() {
        assertEquals(0, PortableDecimal("2.0").compareTo(PortableDecimal("2.00")))
        assertNotEquals(PortableDecimal("2.0"), PortableDecimal("2.00"))
        assertTrue(PortableDecimal("99.95") < PortableDecimal(100))
        assertEquals("3", PortableDecimal(3).max(PortableDecimal("2.9")).toPlainString())
    }

    @Test
    fun doubleConversionUsesShortestDecimalForm() {
        assertEquals("0.1", PortableDecimal.valueOf(0.1).toPlainString())
        assertEquals("0.000010", PortableDecimal.valueOf(1.0E-5).toPlainString())
        assertEquals("12300000", PortableDecimal.valueOf(1.23E7).setScale(0, RoundingMode.HALF_UP).toPlainString())
        assertEquals("72.3", PortableDecimal.formatFixed(72.25, 1))
        assertEquals("-0.0", PortableDecimal.formatFixed(-0.04, 1))
        assertEquals("88", PortableDecimal.formatFixed(87.5, 0))
        assertEquals("NaN", PortableDecimal.formatFixed(Double.NaN, 1))
    }

    @Test
    fun invalidOrOversizedValuesFailInsteadOfLosingPrecision() {
        assertFailsWith<NumberFormatException> { PortableDecimal("12a") }
        assertFailsWith<NumberFormatException> { PortableDecimal("") }
        assertFailsWith<ArithmeticException> { PortableDecimal("99999999999999999999") }
        assertFailsWith<ArithmeticException> { PortableDecimal(1).divide(PortableDecimal(0), 2, RoundingMode.HALF_UP) }
        assertFailsWith<ArithmeticException> {
            PortableDecimal("9000000000000000000").multiply(PortableDecimal(10))
        }
    }

    @Test
    fun realScoreDifferencesConvertAndFormatWithoutOverflow() {
        val near = 66.67 - 66.667
        assertEquals("0.0030000000000001137", PortableDecimal.valueOf(near).toPlainString())
        assertEquals("0.00300000", PortableDecimal.valueOf(near).setScale(8, RoundingMode.HALF_UP).toPlainString())
        assertEquals("0.00", PortableDecimal.formatFixed(near, 2))
        val tiny = 0.1 + 0.2 - 0.3
        assertEquals("0.00000000", PortableDecimal.valueOf(tiny).setScale(8, RoundingMode.HALF_UP).toPlainString())
        assertEquals("0.0", PortableDecimal.formatFixed(tiny, 1))
        assertEquals("-0.0", PortableDecimal.formatFixed(-tiny, 1))
        assertEquals("0.0", PortableDecimal.formatFixed(Double.MIN_VALUE, 1))
        assertEquals("100000000000000000000.00", PortableDecimal.formatFixed(1.0E20, 2))
        assertEquals("10.00", PortableDecimal.formatFixed(9.999, 2))
        assertEquals("0.01", PortableDecimal.formatFixed(0.005, 2))
    }

    @Test
    fun negativeZeroKeepsItsSignWhenFormatted() {
        assertEquals("-0.0", PortableDecimal.formatFixed(-0.0, 1))
        assertEquals("-0", PortableDecimal.formatFixed(-0.0, 0))
        assertEquals("0.0", PortableDecimal.formatFixed(0.0, 1))
    }

    @Test
    fun comparisonAcrossLargeScaleDifferencesDoesNotOverflow() {
        val tiny = PortableDecimal("0.000000000000000001")
        assertTrue(PortableDecimal(10) > tiny)
        assertTrue(tiny < PortableDecimal(10))
        assertTrue(PortableDecimal(-10) < tiny)
        assertTrue(PortableDecimal("-0.000000000000000001") > PortableDecimal(-10))
        assertTrue(PortableDecimal("9223372036854775807") > PortableDecimal("0.9223372036854775807"))
        assertTrue(PortableDecimal("-922337203685477580.7") < PortableDecimal("-0.000000000000000000001"))
        assertEquals(0, PortableDecimal("0E-20").compareTo(PortableDecimal(0)))
        assertEquals(0, PortableDecimal("1.000000000000000000").compareTo(PortableDecimal(1)))
        assertEquals(1, PortableDecimal("1.000000000000000001").compareTo(PortableDecimal(1)))
    }

    @Test
    fun largeScalesRoundExactlyAndStayBounded() {
        val zero = PortableDecimal("0E-20")
        assertEquals("0.00000000000000000000", zero.toPlainString())
        assertEquals("0.00", zero.setScale(2, RoundingMode.HALF_UP).toPlainString())
        assertEquals("1", PortableDecimal(1).add(zero.setScale(0, RoundingMode.DOWN)).toPlainString())
        assertEquals("0.1", PortableDecimal("0.050000000000000001").setScale(1, RoundingMode.HALF_DOWN).toPlainString())
        assertEquals("0.0", PortableDecimal("0.05").setScale(1, RoundingMode.HALF_DOWN).toPlainString())
        assertEquals("0", PortableDecimal("0.4999999999999999999").setScale(0, RoundingMode.HALF_UP).toPlainString())
        assertEquals("1", PortableDecimal("0.0000000000000000000001").setScale(0, RoundingMode.UP).toPlainString())
        assertEquals("-1", PortableDecimal("-0.0000000000000000000001").setScale(0, RoundingMode.FLOOR).toPlainString())
        val product = PortableDecimal("0.0000000001").multiply(PortableDecimal("0.0000000001"))
        assertEquals(20, product.scale())
        assertEquals("0.000", product.setScale(3, RoundingMode.HALF_UP).toPlainString())
        assertFailsWith<ArithmeticException> { PortableDecimal(1).movePointLeft(Int.MAX_VALUE) }
        assertFailsWith<ArithmeticException> { PortableDecimal("1E-20000") }
    }

    @Test
    fun divisionHandlesExtremeDividendsAndScaledDivisors() {
        assertEquals("1.00000000", PortableDecimal(3).divide(PortableDecimal("3.00000000"), 8, RoundingMode.HALF_UP).toPlainString())
        assertEquals(
            "0.33333333",
            PortableDecimal(1).divide(PortableDecimal("3.000000000000000000"), 8, RoundingMode.HALF_UP).toPlainString(),
        )
        val min = PortableDecimal("-9223372036854775808")
        assertFailsWith<ArithmeticException> { min.divide(PortableDecimal(-1), 0, RoundingMode.DOWN) }
        assertEquals("-9223372036854775808", min.divide(PortableDecimal(1), 0, RoundingMode.DOWN).toPlainString())
        assertEquals(
            "1.0",
            PortableDecimal("9000000000000000000").divide(PortableDecimal("9000000000000000000"), 1, RoundingMode.DOWN).toPlainString(),
        )
    }
}
