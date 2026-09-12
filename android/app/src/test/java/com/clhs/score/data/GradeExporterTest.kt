package com.clhs.score.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GradeExporterTest {
    @Test
    fun csvIncludesBomAndEscapesFieldsWithRanksAndStandards() {
        val base = FakeData.latestReport()
        val subject = base.subjects.first().copy(
            subjectName = "國文,\n\"作文\"<br/>",
            scoreDisplay = "87.5",
            classAverageDisplay = "80",
            classRank = null,
            classRankCount = null,
            yearRank = 10,
            yearRankCount = 100,
        )
        val standard = base.standards.first().copy(
            subjectName = subject.subjectName,
            top = 90.0,
            front = 87.5,
            average = 80.0,
            back = 70.2,
            bottom = 60.0,
            standardDeviation = 5.5,
        )
        val report = base.copy(
            examSummary = requireNotNull(base.examSummary).copy(year = 114, termText = "上"),
            subjects = listOf(subject),
            standards = listOf(standard),
        )

        val csv = GradeExporter.buildCsvContent(
            listOf("期末,\"考\"\r\n特別" to report),
        )

        assertArrayEquals(
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()),
            csv.toByteArray(Charsets.UTF_8).copyOfRange(0, 3),
        )
        assertEquals(
            "\uFEFF學期,考試名稱,科目,分數,班平均,班排名,校/類排名,頂標,前標,均標,後標,底標,標準差\n" +
                "114上,\"期末,\"\"考\"\"\r\n特別\",\"國文,\n\"\"作文\"\"\",87.5,80,,10/100,90,87.5,80,70.2,60,5.5\n",
            csv,
        )
    }

    @Test
    fun csvNeutralizesSpreadsheetFormulaPrefixes() {
        val cases = mapOf(
            "=SUM(A1:A2)" to "'=SUM(A1:A2)",
            "+cmd" to "'+cmd",
            "-1" to "'-1",
            "@SUM(A1)" to "'@SUM(A1)",
            "\tformula" to "'\tformula",
            "\rformula" to "\"'\rformula\"",
            "  =SUM(A1)" to "'  =SUM(A1)",
            "plain" to "plain",
            "a,b" to "\"a,b\"",
            "a\"b" to "\"a\"\"b\"",
            "a\nb" to "\"a\nb\"",
        )

        cases.forEach { (input, expected) ->
            assertEquals(input, expected, GradeExporter.csvEscape(input))
        }
    }
}
