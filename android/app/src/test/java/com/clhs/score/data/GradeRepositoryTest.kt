package com.clhs.score.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GradeRepositoryTest {
    @Test
    fun unknownLogoutOwnerClearsAllCaches() = runTest {
        val clearedStudents = mutableListOf<String>()
        var clearedAll = false

        clearLogoutCache(
            studentNo = null,
            clearStudent = { clearedStudents.add(it) },
            clearAll = { clearedAll = true },
        )

        assertEquals(emptyList<String>(), clearedStudents)
        assertEquals(true, clearedAll)
    }

    @Test
    fun knownLogoutOwnerOnlyClearsThatStudent() = runTest {
        val clearedStudents = mutableListOf<String>()
        var clearedAll = false

        clearLogoutCache(
            studentNo = "DEMO-001",
            clearStudent = { clearedStudents.add(it) },
            clearAll = { clearedAll = true },
        )

        assertEquals(listOf("DEMO-001"), clearedStudents)
        assertEquals(false, clearedAll)
    }
}
