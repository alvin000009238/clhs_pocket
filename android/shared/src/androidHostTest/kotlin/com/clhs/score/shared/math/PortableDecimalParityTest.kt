package com.clhs.score.shared.math

import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

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
            val value = (random.nextDouble() * 220.0 - 110.0).let { if (it.rem(3.0) < 1) Math.round(it * 100) / 100.0 else it }
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

    private fun randomDecimal(random: Random): String {
        val scale = random.nextInt(0, 5)
        val whole = random.nextInt(0, 1000)
        val sign = if (random.nextInt(4) == 0) "-" else ""
        if (scale == 0) return "$sign$whole"
        val fraction = random.nextInt(0, 10_000).toString().padStart(4, '0').take(scale)
        return "$sign$whole.$fraction"
    }
}
