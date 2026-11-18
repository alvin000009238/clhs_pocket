package com.clhs.score.ui.schedule

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.*
import org.junit.Test

class ScheduleImageExportTest {
    private fun provider(): ImageProvider {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return ImageProvider(File.createTempFile("schedule-image-", ".png", context.cacheDir)).apply {
            attachInfo(context, ProviderInfo().apply { authority = "media" })
        }
    }

    @Test fun captureCancellationPropagatesWithoutCreatingImage() = runBlocking {
        val provider = provider()
        try {
            val cancellation = CancellationException("capture cancelled")
            val failure = runCatching { saveScheduleImage(ContentResolver.wrap(provider), "test.png") { throw cancellation } }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertFalse(provider.pending)
            assertFalse(provider.published)
        } finally { provider.file.delete() }
    }

    @Test fun cancelledWriteDiscardsPendingImageAndRecyclesBitmap() = runBlocking {
        val provider = provider()
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            supervisorScope {
                val export = async(start = CoroutineStart.LAZY) { saveScheduleImage(ContentResolver.wrap(provider), "test.png") { bitmap } }
                provider.onOpen = { export.cancel() }
                export.start()
                assertTrue(runCatching { export.await() }.exceptionOrNull() is CancellationException)
            }
            assertFalse(provider.pending)
            assertTrue(provider.discarded)
            assertFalse(provider.published)
            assertTrue(bitmap.isRecycled)
        } finally { provider.file.delete(); if (!bitmap.isRecycled) bitmap.recycle() }
    }

    @Test fun failedPublicationDoesNotReportSuccessAndDiscardsPendingImage() = runBlocking {
        val provider = provider().apply { failPublication = true }
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            val result = saveScheduleImage(ContentResolver.wrap(provider), "test.png") { bitmap }
            assertTrue(result.isFailure)
            assertTrue(provider.discarded)
            assertFalse(provider.pending)
            assertFalse(provider.published)
            assertTrue(bitmap.isRecycled)
        } finally { provider.file.delete(); if (!bitmap.isRecycled) bitmap.recycle() }
    }

    @Test fun successWritesPngThenPublishesAndRecyclesBitmap() = runBlocking {
        val provider = provider()
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            assertEquals("test.png", saveScheduleImage(ContentResolver.wrap(provider), "test.png") { bitmap }.getOrThrow())
            assertTrue(provider.file.length() > 0)
            assertTrue(provider.published)
            assertFalse(provider.pending)
            assertFalse(provider.discarded)
            assertTrue(bitmap.isRecycled)
        } finally { provider.file.delete(); if (!bitmap.isRecycled) bitmap.recycle() }
    }

    private class ImageProvider(val file: File) : ContentProvider() {
        var onOpen: () -> Unit = {}
        var failPublication = false
        var pending = false
        var published = false
        var discarded = false
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/png"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            check(values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 1)
            pending = true
            return Uri.parse("content://media/external/images/media/1")
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            onOpen()
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            if (failPublication) return 0
            check(values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 0)
            pending = false
            published = true
            return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            pending = false
            discarded = true
            file.delete()
            return 1
        }
    }
}
