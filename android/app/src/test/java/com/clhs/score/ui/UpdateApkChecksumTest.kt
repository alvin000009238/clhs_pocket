package com.clhs.score.ui

import com.clhs.score.data.ChecksumMismatchException
import com.clhs.score.data.UpdateApkTooLargeException
import com.clhs.score.data.writeVerifiedApk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class UpdateApkChecksumTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun checksumMismatchDeletesApkAndAllowsCleanRetry() = runTest {
        val destination = temporaryFolder.newFile("update.apk")
        val expectedSha256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"

        try {
            writeVerifiedApk(
                ByteArrayInputStream("hello!".toByteArray()),
                destination,
                expectedSha256,
            )
            throw AssertionError("Expected checksum mismatch")
        } catch (_: ChecksumMismatchException) {
            assertFalse(destination.exists())
        }

        val result = writeVerifiedApk(
            ByteArrayInputStream("hello".toByteArray()),
            destination,
            expectedSha256,
        )

        assertSame(destination, result)
        assertTrue(destination.exists())
        assertArrayEquals("hello".toByteArray(), destination.readBytes())
    }

    @Test
    fun oversizedApkIsRejectedAndDeletedBeforeChecksumVerification() = runTest {
        val destination = temporaryFolder.newFile("oversized-update.apk")

        try {
            writeVerifiedApk(
                input = ByteArrayInputStream("123456".toByteArray()),
                destination = destination,
                expectedSha256 = "0".repeat(64),
                maxBytes = 5,
            )
            throw AssertionError("Expected oversized APK rejection")
        } catch (_: UpdateApkTooLargeException) {
            assertFalse(destination.exists())
        }
    }
}
