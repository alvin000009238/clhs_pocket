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
}
