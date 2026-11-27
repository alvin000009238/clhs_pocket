package com.clhs.score.shared.math

import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The iOS decimal must agree with the `java.math.BigDecimal` that Android keeps using. */
class PortableDecimalParityTest {
    private val modes = listOf(
        RoundingMode.UP, RoundingMode.DOWN, RoundingMode.CEILING, RoundingMode.FLOOR,
        RoundingMode.HALF_UP, RoundingMode.HALF_DOWN, RoundingMode.HALF_EVEN,
    )

    private fun portableMode(mode: RoundingMode) = mode

    @Test
    fun randomScoreArithmeticMatchesJavaBigDecimal() {
        val random = Random(20260101)
        repeat(20_000) {
            val a = randomDecimal(random)
            val b = randomDecimal(random)
            val ja = java.math.BigDecimal(a)
            val jb = java.math.BigDecimal(b)
            val pa = PortableDecimal(a)
            val pb = PortableDecimal(b)
            assertEquals(ja.add(jb).toPlainString(), pa.add(pb).toPlainString())
            assertEquals(ja.subtract(jb).toPlainString(), pa.subtract(pb).toPlainString())
            assertEquals(ja.multiply(jb).toPlainString(), pa.multiply(pb).toPlainString())
            assertEquals(ja.compareTo(jb), pa.compareTo(pb))
            assertEquals(ja.precision(), pa.precision(), a)
            assertEquals(ja.scale(), pa.scale())
            assertEquals(ja.stripTrailingZeros().toPlainString(), pa.stripTrailingZeros().toPlainString())
            val mode = modes[random.nextInt(modes.size)]
            val scale = random.nextInt(0, 5)
            assertEquals(ja.setScale(scale, mode).toPlainString(), pa.setScale(scale, portableMode(mode)).toPlainString())
            val credits = random.nextInt(1, 257)
            val divisionScale = random.nextInt(0, 13)
            assertEquals(
                ja.divide(java.math.BigDecimal(credits), divisionScale, mode).toPlainString(),
                pa.divide(PortableDecimal(credits), divisionScale, portableMode(mode)).toPlainString(),
                "$a / $credits @ $divisionScale $mode",
            )
        }
    }

    @Test
    fun doubleFormattingMatchesJavaFormatter() {
        val random = Random(7)
        repeat(20_000) {
            val raw = random.nextDouble() * 220.0 - 110.0
            val value = when (random.nextInt(3)) {
                0 -> Math.round(raw * 100) / 100.0
                1 -> raw
                // Differences of nearby two- and three-decimal scores, e.g. 66.67 - 66.667.
                else -> Math.round(raw * 100) / 100.0 - Math.round(raw * 1000) / 1000.0
            }
            for (digits in 0..2) {
                assertEquals(
                    String.format(Locale.US, "%.${digits}f", value),
                    PortableDecimal.formatFixed(value, digits),
                    "$value @ $digits",
                )
            }
            assertEquals(
                java.math.BigDecimal.valueOf(value).setScale(8, java.math.RoundingMode.HALF_UP).toPlainString(),
                PortableDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP).toPlainString(),
            )
        }
    }

    @Test
    fun extremeDoublesFormatLikeJavaFormatter() {
        val values = listOf(
            0.1 + 0.2 - 0.3, -(0.1 + 0.2 - 0.3), 66.67 - 66.667, 75.125 - 75.124, -0.0, 0.0,
            Double.MIN_VALUE, -Double.MIN_VALUE, 1.0E-300, 0.005, 0.015, 0.045, 9.995, 1.0E20, -1.0E20,
            1.0E300, Double.MAX_VALUE, 12345.6789, -0.0049999,
        )
        for (value in values) {
            for (digits in 0..8) {
                assertEquals(
                    String.format(Locale.US, "%.${digits}f", value),
                    PortableDecimal.formatFixed(value, digits),
                    "$value @ $digits",
                )
            }
        }
    }

    @Test
    fun crossScaleComparisonAndDivisionMatchJavaBigDecimal() {
        val values = listOf(
            "10", "-10", "0", "0E-20", "0.000000000000000001", "-0.000000000000000001", "9223372036854775807",
            "-9223372036854775808", "0.9223372036854775807", "1.000000000000000001", "3.00000000", "0.05",
        )
        for (a in values) {
            for (b in values) {
                val ja = java.math.BigDecimal(a)
                val jb = java.math.BigDecimal(b)
                assertEquals(ja.compareTo(jb), PortableDecimal(a).compareTo(PortableDecimal(b)), "$a <=> $b")
                if (jb.signum() == 0) continue
                for (mode in modes) {
                    val expected = runCatching { ja.divide(jb, 8, mode).toPlainString() }.getOrNull()
                    val actual = runCatching { PortableDecimal(a).divide(PortableDecimal(b), 8, mode).toPlainString() }
                    // Results whose unscaled value exceeds Long fail on the portable side instead of losing precision.
                    if (expected != null && java.math.BigDecimal(expected).unscaledValue().bitLength() < 64) {
                        assertEquals(expected, actual.getOrThrow(), "$a / $b $mode")
                    } else {
                        assertTrue(actual.exceptionOrNull() is ArithmeticException, "$a / $b $mode")
                    }
                }
            }
        }
    }

    private fun randomDecimal(random: Random): String {
        val scale = random.nextInt(0, 5)
        val whole = random.nextInt(0, 1000)
        val sign = if (random.nextInt(4) == 0) "-" else ""
        if (scale == 0) return "$sign$whole"
        val fraction = random.nextInt(0, 10_000).toString().padStart(4, '0').take(scale)
        return "$sign$whole.$fraction"
    }
}
