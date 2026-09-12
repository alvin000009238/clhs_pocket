package com.clhs.score.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clhs.score.R
import com.clhs.score.analytics.UsageMetric
import com.clhs.score.analytics.UsageStatistics
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val MIT_LICENSE_TEXT = """
Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
""".trimIndent()

private val BSD_2_CLAUSE_LICENSE_TEXT = """
Copyright (c) 2013-2024, Michael Angstadt
All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
""".trimIndent()

private val APACHE_LICENSE_TEXT = """
                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction, and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all other entities that control, are controlled by, or are under common control with that entity. For the purposes of this definition, "control" means (i) the power, direct or indirect, to cause the direction or management of such entity, whether by contract or otherwise, or (ii) ownership of fifty percent (50%) or more of the outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications, including but not limited to software source code, documentation source, and configuration files.

      "Object" form shall mean any form resulting from mechanical transformation or translation of a Source form, including but not limited to compiled object code, generated documentation, and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or Object form, made available under the License, as indicated by a copyright notice that is included in or attached to the work (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object form, that is based on (or derived from) the Work and for which the editorial revisions, annotations, elaborations, or other modifications represent, as a whole, an original work of authorship. For the purposes of this License, Derivative Works shall not include works that remain separable from, or merely link (or bind by name) to the interfaces of, the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including the original version of the Work and any modifications or additions to that Work or Derivative Works thereof, that is intentionally submitted to the Licensor for inclusion in the Work by the copyright owner or by an individual or Legal Entity authorized to submit on behalf of the copyright owner. For the purposes of this definition, "submitted" means any form of electronic, verbal, or written communication sent to the Licensor or its representatives, including but not limited to communication on electronic mailing lists, source code control systems, and issue tracking systems that are managed by, or on behalf of, the Licensor for the purpose of discussing and improving the Work, but excluding communication that is conspicuously marked or otherwise designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity on behalf of whom a Contribution has been received by the Licensor and subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of this License, each Contributor hereby grants to You a perpetual, worldwide, non-exclusive, no-charge, royalty-free, irrevocable copyright license to reproduce, prepare Derivative Works of, publicly display, publicly perform, sublicense, and distribute the Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of this License, each Contributor hereby grants to You a perpetual, worldwide, non-exclusive, no-charge, royalty-free, irrevocable (except as stated in this section) patent license to make, have made, use, offer to sell, sell, import, and otherwise transfer the Work, where such license applies only to those patent claims licensable by such Contributor that are necessarily infringed by their Contribution(s) alone or by combination of their Contribution(s) with the Work to which such Contribution(s) was submitted. If You institute patent litigation against any entity (including a cross-claim or counterclaim in a lawsuit) alleging that the Work or a Contribution incorporated within the Work constitutes direct or contributory patent infringement, then any patent licenses granted to You under this License for that Work shall terminate as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the Work or Derivative Works thereof in any medium, with or without modifications, and in Source or Object form, provided that You meet the following conditions:

      (a) You must give any other recipients of the Work or Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works that You distribute, all copyright, patent, trademark, and attribution notices from the Source form of the Work, excluding those notices that do not pertain to any part of the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its distribution, then any Derivative Works that You distribute must include a readable copy of the attribution notices contained within such NOTICE file, excluding any notices that do not pertain to any part of the Derivative Works, in at least one of the following places: within a NOTICE text file distributed as part of the Derivative Works; within the Source form or documentation, if provided along with the Derivative Works; or, within a display generated by the Derivative Works, if and wherever such third-party notices normally appear. The contents of the NOTICE file are for informational purposes only and do not modify the License. You may add Your own attribution notices within Derivative Works that You distribute, alongside or as an addendum to the NOTICE text from the Work, provided that such additional attribution notices cannot be construed as modifying the License.

      You may add Your own copyright statement to Your modifications and may provide additional or different license terms and conditions for use, reproduction, or distribution of Your modifications, or for any such Derivative Works as a whole, provided Your use, reproduction, and distribution of the Work otherwise complies with the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise, any Contribution intentionally submitted for inclusion in the Work by You to the Licensor shall be under the terms and conditions of this License, without any additional terms or conditions. Notwithstanding the above, nothing herein shall supersede or modify the terms of any separate license agreement you may have executed with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade names, trademarks, service marks, or product names of the Licensor, except as required for reasonable and customary use in describing the origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or agreed to in writing, Licensor provides the Work (and each Contributor provides its Contributions) on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied, including, without limitation, any warranties or conditions of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A PARTICULAR PURPOSE. You are solely responsible for determining the appropriateness of using or redistributing the Work and assume any risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory, whether in tort (including negligence), contract, or otherwise, unless required by applicable law (such as deliberate and grossly negligent acts) or agreed to in writing, shall any Contributor be liable to You for damages, including any direct, indirect, special, incidental, or consequential damages of any character arising as a result of this License or out of the use or inability to use the Work (including but not limited to damages for loss of goodwill, work stoppage, computer failure or malfunction, or any and all other commercial damages or losses), even if such Contributor has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing the Work or Derivative Works thereof, You may choose to offer, and charge a fee for, acceptance of support, warranty, indemnity, or other liability obligations and/or rights consistent with this License. However, in accepting such obligations, You may act only on Your own behalf and on Your sole responsibility, not on behalf of any other Contributor, and only if You agree to indemnify, defend, and hold each Contributor harmless for any liability incurred by, or claims asserted against, such Contributor by reason of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS
""".trimIndent()

private val APACHE_COMPONENTS = listOf(
    "AndroidX Activity Compose",
    "AndroidX Biometric",
    "AndroidX Compose Foundation",
    "AndroidX Compose Material 3",
    "AndroidX Compose Material 3 Adaptive Navigation Suite",
    "AndroidX Compose Runtime",
    "AndroidX Compose UI",
    "AndroidX Compose UI Graphics",
    "AndroidX Compose UI Tooling Preview",
    "AndroidX Core Splashscreen",
    "AndroidX DataStore Proto",
    "AndroidX DataStore Preferences",
    "AndroidX Glance AppWidget",
    "AndroidX Glance Material 3",
    "AndroidX Lifecycle Runtime Compose",
    "AndroidX Lifecycle ViewModel Compose",
    "AndroidX Navigation 3",
    "AndroidX WorkManager Runtime KTX",
    "Apache Commons Codec subset (bundled with biweekly)",
    "Coil Compose",
    "Coil Network OkHttp",
    "Firebase Cloud Messaging",
    "Firebase Remote Config",
    "Google RFC 2445 recurrence utility (bundled with biweekly)",
    "Kotlin Standard Library",
    "kotlinx.coroutines Android",
    "kotlinx.serialization JSON",
    "Material Symbols Rounded",
    "Multiplatform Markdown Renderer",
    "OkHttp",
    "Protocol Buffers Java Lite",
)

internal data class LicenseEntry(
    val componentName: String,
    val licenseName: String,
    val licenseText: String,
)

internal fun buildThirdPartyLicenses(outfitLicenseText: String): List<LicenseEntry> =
    (APACHE_COMPONENTS.map {
        LicenseEntry(it, "Apache License 2.0", APACHE_LICENSE_TEXT)
    } + listOf(
        LicenseEntry("biweekly", "BSD 2-Clause License", BSD_2_CLAUSE_LICENSE_TEXT),
        LicenseEntry("jsoup", "MIT License", MIT_LICENSE_TEXT),
        LicenseEntry("Outfit", "SIL Open Font License 1.1", outfitLicenseText),
        LicenseEntry("Vinnie", "MIT License", MIT_LICENSE_TEXT),
    )).sortedBy { it.componentName.lowercase() }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UsageStatisticsScreen(
    statistics: UsageStatistics,
    onBack: () -> Unit,
) {
    val trackingPeriod = statistics.startedAtMillis?.let { millis ->
        val date = Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
        "自 $date 起"
    } ?: "尚未開始記錄"

    SubpageLayout(
        onBack = onBack,
        title = "使用統計",
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 8.dp, end = 16.dp)
                .navigationBarsPadding(),
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "$trackingPeriod\n以下資料只儲存在這台裝置。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(24.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                usageStatisticRows.forEachIndexed { index, (metric, label) ->
                    UsageStatisticRow(
                        label = label,
                        count = statistics.count(metric),
                        index = index,
                        itemCount = usageStatisticRows.size,
                    )
                }
            }
        }
    }
}
private val usageStatisticRows = listOf(
    UsageMetric.APP_OPEN to "應用程式開啟",
    UsageMetric.OVERVIEW_OPEN to "總覽畫面開啟",
    UsageMetric.SCHEDULE_OPEN to "課表畫面開啟",
    UsageMetric.GRADES_OPEN to "成績畫面開啟",
    UsageMetric.CAMPUS_OPEN to "校園畫面開啟",
    UsageMetric.GRADE_QUERY to "讀取成績成功",
    UsageMetric.SCHEDULE_CUSTOMIZATIONS_OPEN to "課表自訂頁開啟",
    UsageMetric.SCHOOL_CALENDAR_OPEN to "行事曆開啟",
    UsageMetric.SCHOOL_ANNOUNCEMENTS_OPEN to "學校公告頁開啟",
    UsageMetric.SCHOOL_ANNOUNCEMENT_DETAIL_OPEN to "公告詳情頁開啟",
    UsageMetric.ANNOUNCEMENT_REMINDER_OPEN to "公告更新提醒設定頁開啟",
    UsageMetric.PERSONAL_OPEN to "個人頁開啟",
    UsageMetric.SUBJECT_TREND_OPEN to "成績折線圖開啟",
    UsageMetric.SCORE_SIMULATOR_OPEN to "成績模擬器開啟",
    UsageMetric.GRADE_EXPORT to "成績匯出成功",
    UsageMetric.GRADE_REMINDER_START to "段考更新提醒啟用成功",
    UsageMetric.ANNOUNCEMENT_REMINDER_START to "公告更新提醒啟用成功",
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UsageStatisticRow(
    label: String,
    count: Long,
    index: Int,
    itemCount: Int,
) {
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index = index, count = itemCount),
        colors = ListItemDefaults.segmentedColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        trailingContent = {
            Text(
                text = "$count 次",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = label)
    }
}

internal fun isAllowedFeedbackFormUrl(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true) ||
        uri.userInfo != null ||
        (uri.port != -1 && uri.port != 443)
    ) {
        return false
    }
    return when (uri.host?.lowercase()) {
        "forms.gle" -> !uri.path.isNullOrBlank() && uri.path != "/"
        "docs.google.com" -> uri.path?.startsWith("/forms/") == true
        else -> false
    }
}

@Composable
fun OpenSourceLicensesScreen(
    onBack: () -> Unit,
) {
    val resources = LocalResources.current
    val outfitLicenseText = remember(resources) {
        resources.openRawResource(R.raw.outfit_ofl)
            .bufferedReader()
            .use { it.readText() }
    }
    val thirdPartyLicenses = remember(outfitLicenseText) {
        buildThirdPartyLicenses(outfitLicenseText)
    }

    SubpageLayout(
        onBack = onBack,
        title = "開放原始碼授權",
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 8.dp, end = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            OutlinedRoundedSymbol(
                                icon = "license",
                                tint = MaterialTheme.colorScheme.primary,
                                size = 20.dp,
                                contentDescription = null,
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "MIT License",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Copyright (c) 2026 alvin000009238",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )

                    Text(
                        text = MIT_LICENSE_TEXT,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    )
                }
            }

            SectionHeader("第三方元件（${thirdPartyLicenses.size}）")

            thirdPartyLicenses.forEach { license ->
                ThirdPartyLicenseCard(
                    componentName = license.componentName,
                    licenseName = license.licenseName,
                    licenseText = license.licenseText,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ThirdPartyLicenseCard(
    componentName: String,
    licenseName: String,
    licenseText: String,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        onClick = { expanded = !expanded },
    ) {
        Column(
            modifier = Modifier
                .animateContentSize(
                    animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = componentName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = licenseName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedRoundedSymbol(
                    icon = if (expanded) "keyboard_arrow_up" else "keyboard_arrow_down",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 22.dp,
                    contentDescription = if (expanded) "收合" else "展開",
                )
            }
            if (expanded) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
                Text(
                    text = licenseText,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                )
            }
        }
    }
}

@Composable
internal fun LogoutConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("登出") },
        text = { Text("確定要登出嗎？") },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("登出") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shapes = ButtonDefaults.shapes(),
            ) { Text("取消") }
        },
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
    )
}
