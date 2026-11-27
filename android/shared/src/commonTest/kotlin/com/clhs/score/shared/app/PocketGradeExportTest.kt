package com.clhs.score.shared.app

import com.clhs.score.data.FakeData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PocketGradeExportTest {
    @Test
    fun everySubjectOfEveryChosenExamBecomesARowUnderAndroidsHeader() {
        val keys = listOf(PocketExamKey.of("114_1", "114_1_E1"), PocketExamKey.of("114_1", "114_1_E4"))
        val lines = PocketGradeExport.csv(keys).removePrefix("\uFEFF").trim().lines()
        assertEquals("學期,考試名稱,科目,分數,班平均,班排名,校/類排名,頂標,前標,均標,後標,底標,標準差", lines.first())
        val subjects = FakeData.reportFor("114_1", "114_1_E1").subjects.size + FakeData.reportFor("114_1", "114_1_E4").subjects.size
        assertEquals(subjects, lines.size - 1)
        assertTrue(lines[1].split(",")[1] == "第一次段考", lines[1])
        assertTrue(lines.last().split(",")[1] == "期末考", lines.last())
        assertTrue(PocketGradeExport.csv(keys).startsWith("\uFEFF"))
    }

    @Test
    fun formulaLookingAndQuotedCellsAreDefused() {
        assertEquals("'=1+1", PocketGradeExport.escape("=1+1"))
        assertEquals("\"a,b\"", PocketGradeExport.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", PocketGradeExport.escape("say \"hi\""))
        assertEquals("'-3", PocketGradeExport.escape("-3"))
    }
}
