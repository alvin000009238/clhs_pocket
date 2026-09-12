package com.clhs.score.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.clhs.score.data.ApkAsset
import com.clhs.score.data.UpdateResult
import com.clhs.score.viewmodel.UpdateDownloadError
import com.clhs.score.viewmodel.UpdateDownloadState
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.m3.Markdown
import java.io.File

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdateResultDialog(
    result: UpdateResult?,
    downloadState: UpdateDownloadState,
    onDownloadUpdate: (ApkAsset) -> Unit,
    onDownloadResultHandled: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (result == null) return
    val context = LocalContext.current
    when (result) {
        is UpdateResult.UpToDate -> {
            LaunchedEffect(Unit) {
                Toast.makeText(context, "目前已是最新版本", Toast.LENGTH_SHORT).show()
                onDismiss()
            }
        }
        is UpdateResult.Error -> {
            LaunchedEffect(result) {
                Toast.makeText(context, "檢查更新失敗：${result.message}", Toast.LENGTH_LONG).show()
                onDismiss()
            }
        }
        is UpdateResult.NewVersion -> {
            val isInstalling = downloadState is UpdateDownloadState.Downloading
            val downloadProgress = (downloadState as? UpdateDownloadState.Downloading)?.progress
            LaunchedEffect(downloadState) {
                when (downloadState) {
                    is UpdateDownloadState.Ready -> {
                        val opened = runCatching { openApkInstaller(context, downloadState.apk) }.isSuccess
                        onDownloadResultHandled()
                        if (opened) {
                            onDismiss()
                        } else {
                            Toast.makeText(context, "無法開啟安裝程式", Toast.LENGTH_LONG).show()
                        }
                    }
                    is UpdateDownloadState.Error -> {
                        val message = when (downloadState.reason) {
                            UpdateDownloadError.TOO_LARGE -> "更新檔過大，請前往 GitHub 下載"
                            UpdateDownloadError.CHECKSUM_MISMATCH -> "更新檔驗證失敗，請重新下載"
                            UpdateDownloadError.DOWNLOAD_FAILED -> "下載或安裝失敗"
                        }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        onDownloadResultHandled()
                    }
                    else -> Unit
                }
            }
            AlertDialog(
                onDismissRequest = { if (!isInstalling) onDismiss() },
                title = { Text("有新版本") },
                text = {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "v${result.versionName} 已可更新",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (isInstalling) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (downloadProgress == null) {
                                    CircularWavyProgressIndicator(modifier = Modifier.size(28.dp))
                                } else {
                                    CircularWavyProgressIndicator(
                                        progress = { downloadProgress },
                                        modifier = Modifier.size(28.dp),
                                    )
                                }
                                Text(
                                    text = downloadProgress?.let { "下載中 ${(it * 100).toInt()}%" } ?: "下載中...",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        if (result.releaseNotes.isNotBlank()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.large,
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                ),
                            ) {
                                Markdown(
                                    content = result.releaseNotes,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    colors = markdownColor(
                                        text = MaterialTheme.colorScheme.onSurface,
                                        codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        inlineCodeBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        dividerColor = MaterialTheme.colorScheme.outlineVariant,
                                        tableBackground = MaterialTheme.colorScheme.surfaceContainer,
                                    ),
                                    typography = markdownTypography(
                                        h1 = MaterialTheme.typography.titleLarge,
                                        h2 = MaterialTheme.typography.titleMedium,
                                        h3 = MaterialTheme.typography.titleSmall,
                                        h4 = MaterialTheme.typography.bodyLarge,
                                        h5 = MaterialTheme.typography.bodyLarge,
                                        h6 = MaterialTheme.typography.bodyMedium,
                                        text = MaterialTheme.typography.bodyMedium,
                                        code = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                        inlineCode = MaterialTheme.typography.bodyMedium.copy(
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                        quote = MaterialTheme.typography.bodyMedium.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontStyle = FontStyle.Italic,
                                        ),
                                        paragraph = MaterialTheme.typography.bodyMedium,
                                        ordered = MaterialTheme.typography.bodyMedium,
                                        bullet = MaterialTheme.typography.bodyMedium,
                                        list = MaterialTheme.typography.bodyMedium,
                                        table = MaterialTheme.typography.bodySmall,
                                    ),
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !isInstalling,
                        shapes = ButtonDefaults.shapes(),
                        onClick = {
                            val apkAsset = result.apkAsset
                            if (apkAsset == null) {
                                openUrl(context, result.htmlUrl)
                                onDismiss()
                            } else {
                                onDownloadUpdate(apkAsset)
                            }
                        },
                    ) {
                        Text(
                            when {
                                isInstalling -> "下載中..."
                                result.apkAsset != null -> "安裝 APK"
                                else -> "前往 GitHub"
                            },
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !isInstalling,
                        shapes = ButtonDefaults.shapes(),
                        onClick = onDismiss,
                    ) { Text("稍後") }
                },
            )
        }
    }
}

private fun openApkInstaller(context: Context, apk: File) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        apk,
    )
    context.startActivity(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
    )
}

private fun openUrl(context: Context, url: String) {
    try {
        val uri = url.toUri()
        if (uri.scheme !in listOf("http", "https")) error("unsupported update URL")
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (_: Exception) {
        Toast.makeText(context, "無法開啟連結", Toast.LENGTH_SHORT).show()
    }
}
