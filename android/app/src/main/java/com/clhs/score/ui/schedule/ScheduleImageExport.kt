package com.clhs.score.ui.schedule

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal suspend fun saveBitmapToGallery(context: Context, layer: GraphicsLayer): Result<String> =
    saveScheduleImage(context.contentResolver,
        "Schedule_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.png") {
        if (layer.size.width <= 0 || layer.size.height <= 0) throw IOException("課表尚未完成排版")
        layer.toImageBitmap().asAndroidBitmap()
    }

internal suspend fun saveScheduleImage(resolver: ContentResolver, filename: String,
    capture: suspend () -> Bitmap): Result<String> {
    var bitmap: Bitmap? = null
    return try {
        withContext(Dispatchers.Main) { bitmap = capture() }
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Schedules")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("無法建立圖片檔")
            try {
                val output = resolver.openOutputStream(uri) ?: throw IOException("無法開啟圖片檔")
                output.use {
                    if (!requireNotNull(bitmap).compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("圖片壓縮失敗")
                }
                currentCoroutineContext().ensureActive()
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                if (resolver.update(uri, values, null, null) != 1) throw IOException("無法發布圖片檔")
            } catch (error: Exception) {
                runCatching { resolver.delete(uri, null, null) }.exceptionOrNull()?.let(error::addSuppressed)
                throw error
            }
        }
        Result.success(filename)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Result.failure(error)
    } finally {
        bitmap?.recycle()
    }
}
