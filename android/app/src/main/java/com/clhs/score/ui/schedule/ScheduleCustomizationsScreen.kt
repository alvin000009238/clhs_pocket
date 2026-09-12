package com.clhs.score.ui.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clhs.score.data.SCHEDULE_SUBJECT_OVERRIDE_MAX_LENGTH
import com.clhs.score.data.ScheduleItem
import com.clhs.score.data.ScheduleSubjectOverride
import com.clhs.score.data.normalizedOrNull
import com.clhs.score.ui.OutlinedRoundedSymbol
import com.clhs.score.ui.SubpageLayout
import com.clhs.score.viewmodel.ScheduleCustomizationsUiState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ScheduleCustomizationsScreen(
    state: ScheduleCustomizationsUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSaveOverride: (ScheduleSubjectOverride, () -> Unit) -> Unit,
    onRemoveOverride: (String, () -> Unit) -> Unit,
    onReplaceOverrides: (List<ScheduleSubjectOverride>, () -> Unit) -> Unit,
    onNoticeShown: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showMoreMenu by remember { mutableStateOf(false) }
    var showRestoreAllDialog by remember { mutableStateOf(false) }
    var editingOverride by remember { mutableStateOf<ScheduleSubjectOverride?>(null) }

    LaunchedEffect(state.noticeMessage) {
        state.noticeMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            onNoticeShown()
        }
    }

    SubpageLayout(
        title = "自訂科目",
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        actions = {
            if (state.overrides.isNotEmpty()) {
                IconButton(
                    onClick = { showMoreMenu = true },
                    enabled = !state.isSaving,
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    OutlinedRoundedSymbol("more_vert", contentDescription = "更多選項")
                }
                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("全部還原") },
                        onClick = {
                            showMoreMenu = false
                            showRestoreAllDialog = true
                        },
                    )
                }
            }
        },
    ) {
        when {
            state.isLoading -> LoadingCustomizationList()
            state.loadFailed -> CustomizationLoadError(onRetry)
            state.overrides.isEmpty() -> EmptyCustomizationList()
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.overrides, key = ScheduleSubjectOverride::originalSubjectName) { override ->
                    val appearsInCurrentSchedule = state.currentItems.any {
                        it.subjectName == override.originalSubjectName
                    }
                    CustomizationRow(
                        override = override,
                        appearsInCurrentSchedule = appearsInCurrentSchedule,
                        enabled = !state.isSaving,
                        onEdit = { editingOverride = override },
                        onRestore = {
                            onRemoveOverride(override.originalSubjectName) {
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = "已還原「${override.originalSubjectName}」",
                                        actionLabel = "復原",
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        onSaveOverride(override) {}
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    editingOverride?.let { override ->
        ModalBottomSheet(
            onDismissRequest = { if (!state.isSaving) editingOverride = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            dragHandle = { BottomSheetDefaults.DragHandle() },
        ) {
            ScheduleSubjectOverrideEditor(
                originalSubjectName = override.originalSubjectName,
                originalItems = state.currentItems.filter {
                    it.subjectName == override.originalSubjectName
                },
                existingOverride = override,
                isSaving = state.isSaving,
                onCancel = { editingOverride = null },
                onSave = { updated ->
                    onSaveOverride(updated) { editingOverride = null }
                },
            )
        }
    }

    if (showRestoreAllDialog) {
        AlertDialog(
            onDismissRequest = { if (!state.isSaving) showRestoreAllDialog = false },
            title = { Text("全部還原？") },
            text = { Text("所有科目將恢復顯示校方提供的名稱、教師與教室。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onReplaceOverrides(emptyList()) { showRestoreAllDialog = false }
                    },
                    enabled = !state.isSaving,
                ) {
                    Text("全部還原")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showRestoreAllDialog = false },
                    enabled = !state.isSaving,
                ) {
                    Text("取消")
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoadingCustomizationList() {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("載入自訂科目…")
                LoadingIndicator()
            }
        }
    }
}

@Composable
private fun EmptyCustomizationList() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "尚未自訂科目",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "請從課表點開科目詳情後設定。",
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CustomizationLoadError(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("無法載入自訂科目", style = MaterialTheme.typography.titleLarge)
        Button(
            onClick = onRetry,
            modifier = Modifier.padding(top = 16.dp),
            shapes = ButtonDefaults.shapes(),
        ) {
            Text("重試")
        }
    }
}

@Composable
private fun CustomizationRow(
    override: ScheduleSubjectOverride,
    appearsInCurrentSchedule: Boolean,
    enabled: Boolean,
    onEdit: () -> Unit,
    onRestore: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onEdit),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = override.customSubjectName ?: override.originalSubjectName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "原始名稱：${override.originalSubjectName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                listOfNotNull(
                    override.customTeacherName?.let { "教師：$it" },
                    override.customClassroom?.let { "教室：$it" },
                    if (appearsInCurrentSchedule) null else "目前課表未出現",
                ).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(onClick = onRestore, enabled = enabled) {
                Text("還原")
            }
        }
    }
}

@Composable
internal fun ScheduleSubjectOverrideEditor(
    originalSubjectName: String,
    originalItems: List<ScheduleItem>,
    existingOverride: ScheduleSubjectOverride?,
    isEditing: Boolean = true,
    isSaving: Boolean,
    onEdit: () -> Unit = {},
    onCancel: () -> Unit,
    onSave: (ScheduleSubjectOverride) -> Unit,
) {
    val originalTeacherName = originalItems.firstOrNull()?.teacherName.scheduleFieldValue()
    val originalClassroom = originalItems.firstOrNull()?.classroom.scheduleFieldValue()
    var subjectName by remember(existingOverride, isEditing, originalSubjectName) {
        mutableStateOf(existingOverride?.customSubjectName ?: originalSubjectName)
    }
    var teacherName by remember(existingOverride, isEditing, originalTeacherName) {
        mutableStateOf(existingOverride?.customTeacherName ?: originalTeacherName)
    }
    var classroom by remember(existingOverride, isEditing, originalClassroom) {
        mutableStateOf(existingOverride?.customClassroom ?: originalClassroom)
    }
    val draft = scheduleSubjectOverrideDraft(
        originalSubjectName = originalSubjectName,
        originalTeacherName = originalTeacherName,
        originalClassroom = originalClassroom,
        subjectName = subjectName,
        teacherName = teacherName,
        classroom = classroom,
    )
    val hasTooLongValue = listOf(draft.customSubjectName, draft.customTeacherName, draft.customClassroom)
        .any { it != null && it.length > SCHEDULE_SUBJECT_OVERRIDE_MAX_LENGTH }
    val normalizedDraft = if (hasTooLongValue) null else draft.normalizedOrNull()
    val canSave = !isSaving && !hasTooLongValue && normalizedDraft != existingOverride?.normalizedOrNull()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        EditableScheduleText(
            value = subjectName,
            onValueChange = { subjectName = it },
            originalValue = originalSubjectName,
            accessibilityLabel = "科目名稱",
            isEditing = isEditing,
            modifier = Modifier.fillMaxWidth(),
            editableModifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(min = 160.dp, max = 320.dp)
                .fillMaxWidth(),
            textStyle = MaterialTheme.typography.titleLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
            imeAction = ImeAction.Next,
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (shouldShowScheduleDetailField(teacherName, isEditing)) {
                EditableScheduleDetailRow(
                    icon = "person",
                    label = "教師",
                    value = teacherName,
                    onValueChange = { teacherName = it },
                    originalValue = originalTeacherName,
                    accessibilityLabel = "教師",
                    isEditing = isEditing,
                    imeAction = ImeAction.Next,
                )
            }
            if (shouldShowScheduleDetailField(classroom, isEditing)) {
                EditableScheduleDetailRow(
                    icon = "meeting_room",
                    label = "教室",
                    value = classroom,
                    onValueChange = { classroom = it },
                    originalValue = originalClassroom,
                    accessibilityLabel = "教室",
                    isEditing = isEditing,
                    imeAction = ImeAction.Done,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            if (isEditing) {
                TextButton(onClick = onCancel, enabled = !isSaving) {
                    Text("取消")
                }
                Button(
                    onClick = { onSave(draft) },
                    enabled = canSave,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(if (isSaving) "儲存中…" else "儲存")
                }
            } else {
                FilledTonalButton(
                    onClick = onEdit,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text("自訂")
                }
            }
        }
    }
}

@Composable
private fun EditableScheduleDetailRow(
    icon: String,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    originalValue: String,
    accessibilityLabel: String,
    isEditing: Boolean,
    imeAction: ImeAction,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedRoundedSymbol(
            icon = icon,
            tint = MaterialTheme.colorScheme.primary,
            size = 24.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            EditableScheduleText(
                value = value,
                onValueChange = onValueChange,
                originalValue = originalValue,
                accessibilityLabel = accessibilityLabel,
                isEditing = isEditing,
                modifier = Modifier.fillMaxWidth(),
                editableModifier = Modifier
                    .widthIn(min = 120.dp, max = 280.dp)
                    .fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                ),
                imeAction = imeAction,
            )
        }
    }
}

@Composable
private fun EditableScheduleText(
    value: String,
    onValueChange: (String) -> Unit,
    originalValue: String,
    accessibilityLabel: String,
    isEditing: Boolean,
    modifier: Modifier,
    editableModifier: Modifier,
    textStyle: TextStyle,
    imeAction: ImeAction,
) {
    val focusManager = LocalFocusManager.current
    var isFocused by remember { mutableStateOf(false) }
    val tooLong = value.customizedFrom(originalValue)
        ?.let { it.length > SCHEDULE_SUBJECT_OVERRIDE_MAX_LENGTH }
        ?: false
    val indicatorColor = when {
        tooLong -> MaterialTheme.colorScheme.error
        isFocused -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    }
    val indicatorWidth = if (tooLong || isFocused) 2.dp else 1.dp
    Column(modifier = modifier) {
        if (isEditing) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = editableModifier
                    .heightIn(min = 48.dp)
                    .onFocusChanged { isFocused = it.isFocused }
                    .drawBehind {
                        val strokeWidth = indicatorWidth.toPx()
                        val y = size.height - strokeWidth / 2
                        drawLine(
                            color = indicatorColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = strokeWidth,
                        )
                    }
                    .padding(bottom = 4.dp)
                    .semantics { contentDescription = accessibilityLabel },
                textStyle = textStyle,
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = imeAction),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                    onDone = { focusManager.clearFocus() },
                ),
            )
        } else {
            Text(
                text = value,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                style = textStyle,
            )
        }
        if (tooLong) {
            Text(
                text = "最多 $SCHEDULE_SUBJECT_OVERRIDE_MAX_LENGTH 字",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

internal fun shouldShowScheduleDetailField(value: String, isEditing: Boolean): Boolean =
    isEditing || value.isNotBlank()

internal fun scheduleSubjectOverrideDraft(
    originalSubjectName: String,
    originalTeacherName: String,
    originalClassroom: String,
    subjectName: String,
    teacherName: String,
    classroom: String,
) = ScheduleSubjectOverride(
    originalSubjectName = originalSubjectName,
    customSubjectName = subjectName.customizedFrom(originalSubjectName),
    customTeacherName = teacherName.customizedFrom(originalTeacherName),
    customClassroom = classroom.customizedFrom(originalClassroom),
)

private fun String.customizedFrom(original: String): String? =
    trim().takeIf { it.isNotEmpty() && it != original.trim() }

private fun String?.scheduleFieldValue(): String =
    orEmpty().takeUnless { it == "null" }.orEmpty()
