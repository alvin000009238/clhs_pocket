package com.clhs.score.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.clhs.score.data.WeatherLocation
import com.clhs.score.data.WeatherSource
import com.clhs.score.data.YouBikeStation
import com.clhs.score.domain.overview.OverviewLayoutJson
import com.clhs.score.domain.overview.AnnouncementPriority
import com.clhs.score.domain.overview.OverviewState
import com.clhs.score.domain.overview.OverviewWidgetConfig
import com.clhs.score.domain.overview.OverviewWidgetDefinitions
import com.clhs.score.domain.overview.OverviewWidgetInstance
import com.clhs.score.ui.OutlinedRoundedSymbol
import com.clhs.score.ui.ProfileCwaApiKeyDialog
import com.clhs.score.ui.hapticClick
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OverviewWidgetConfigSheet(
    widget: OverviewWidgetInstance,
    state: OverviewState,
    onSearchLocations: suspend (String) -> List<WeatherLocation>,
    onSearchStations: suspend (String) -> List<YouBikeStation>,
    onConfirm: (OverviewWidgetInstance) -> Unit,
    onDismiss: () -> Unit,
    cwaKeyConfigured: Boolean = false,
    cwaKeySaving: Boolean = false,
    cwaKeyMessage: String? = null,
    onSaveCwaApiKey: (String) -> Unit = {},
    onClearCwaApiKey: () -> Unit = {},
    onDismissCwaKeyMessage: () -> Unit = {},
) {
    var showCwaKeyDialog by remember { mutableStateOf(false) }
    val initial = remember(widget.id) { if (widget.configVersion == 1) runCatching {
        OverviewLayoutJson.decodeFromString<OverviewWidgetConfig>(widget.configuration)
    }.getOrNull() else null }
    var locationJson by rememberSaveable(widget.id) { mutableStateOf(
        (initial as? OverviewWidgetConfig.Weather)?.location?.let { OverviewLayoutJson.encodeToString(it) }.orEmpty(),
    ) }
    var stationJson by rememberSaveable(widget.id) { mutableStateOf(
        (widget.config() as? OverviewWidgetConfig.YouBike)?.station?.let { OverviewLayoutJson.encodeToString(it) }.orEmpty(),
    ) }
    var sourceName by rememberSaveable(widget.id) { mutableStateOf(
        (initial as? OverviewWidgetConfig.Weather)?.source?.name ?: WeatherSource.OPEN_METEO.name,
    ) }
    var query by rememberSaveable(widget.id) { mutableStateOf("") }
    var retry by rememberSaveable(widget.id) { mutableIntStateOf(0) }
    var customCountdown by rememberSaveable(widget.id) { mutableStateOf(initial is OverviewWidgetConfig.CustomCountdown) }
    var eventId by rememberSaveable(widget.id) { mutableStateOf((initial as? OverviewWidgetConfig.SchoolEvent)?.eventId.orEmpty()) }
    var eventTitle by rememberSaveable(widget.id) { mutableStateOf((initial as? OverviewWidgetConfig.SchoolEvent)?.title.orEmpty()) }
    var title by rememberSaveable(widget.id) { mutableStateOf((initial as? OverviewWidgetConfig.CustomCountdown)?.title.orEmpty()) }
    var dateText by rememberSaveable(widget.id) { mutableStateOf((initial as? OverviewWidgetConfig.CustomCountdown)?.date.orEmpty()) }
    var timeText by rememberSaveable(widget.id) { mutableStateOf((initial as? OverviewWidgetConfig.CustomCountdown)?.time.orEmpty()) }
    var useTime by rememberSaveable(widget.id) { mutableStateOf((initial as? OverviewWidgetConfig.CustomCountdown)?.time != null) }
    var limitText by rememberSaveable(widget.id) { mutableStateOf(((initial as? OverviewWidgetConfig.ListItems)?.limit ?: 3).toString()) }
    var priorityJson by rememberSaveable(widget.id) { mutableStateOf(OverviewLayoutJson.encodeToString(
        (initial as? OverviewWidgetConfig.ListItems)?.announcementPriority ?: AnnouncementPriority.entries,
    )) }
    val priorities = remember(priorityJson) { OverviewLayoutJson.decodeFromString<List<AnnouncementPriority>>(priorityJson) }
    var hideLongEvents by rememberSaveable(widget.id) { mutableStateOf((initial as? OverviewWidgetConfig.ListItems)?.maxEventDurationDays != null) }
    var durationText by rememberSaveable(widget.id) { mutableStateOf(
        ((initial as? OverviewWidgetConfig.ListItems)?.maxEventDurationDays ?: 7).toString(),
    ) }
    var showDate by rememberSaveable(widget.id) { mutableStateOf(false) }
    var showTime by rememberSaveable(widget.id) { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var searchFailed by remember { mutableStateOf(false) }
    var locations by remember { mutableStateOf<List<WeatherLocation>>(emptyList()) }
    var stations by remember { mutableStateOf<List<YouBikeStation>>(emptyList()) }
    val locationSearch by rememberUpdatedState(onSearchLocations)
    val stationSearch by rememberUpdatedState(onSearchStations)
    val location = remember(locationJson) { runCatching { OverviewLayoutJson.decodeFromString<WeatherLocation>(locationJson) }.getOrNull() }
    val station = remember(stationJson) { runCatching { OverviewLayoutJson.decodeFromString<YouBikeStation>(stationJson) }.getOrNull() }
    val source = WeatherSource.valueOf(sourceName)
    val date = runCatching { LocalDate.parse(dateText) }.getOrNull()
    val time = runCatching { LocalTime.parse(timeText) }.getOrNull()
    val limit = limitText.toIntOrNull()?.takeIf { it in 1..20 }
    val durationDays = durationText.toIntOrNull()?.takeIf { it > 0 }

    LaunchedEffect(widget.id, query, retry) {
        locations = emptyList()
        stations = emptyList()
        searchFailed = false
        searching = false
        if (query.isBlank() || widget.type !in setOf("weather", "youbike")) return@LaunchedEffect
        searching = true
        try {
            delay(350)
            if (widget.type == "weather") {
                val result = locationSearch(query.trim())
                currentCoroutineContext().ensureActive()
                locations = result
            } else {
                val result = stationSearch(query.trim())
                currentCoroutineContext().ensureActive()
                stations = result
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            searchFailed = true
        } finally {
            searching = false
        }
    }

    val configuration: OverviewWidgetConfig? = when (widget.type) {
        "weather" -> location?.takeIf { it.countryCode == "TW" && (source != WeatherSource.CWA || it.supportsCwa) }
            ?.let { OverviewWidgetConfig.Weather(it, source) }
        "youbike" -> station?.let { OverviewWidgetConfig.YouBike(it) }
        "countdown" -> if (customCountdown) {
            if (title.isNotBlank() && date != null && (!useTime || time != null))
                OverviewWidgetConfig.CustomCountdown(title.trim(), date.toString(), if (useTime) time.toString() else null)
            else null
        } else eventId.takeIf(String::isNotBlank)?.let { OverviewWidgetConfig.SchoolEvent(it, eventTitle) }
        "calendar" -> limit?.takeIf { !hideLongEvents || durationDays != null }?.let {
            OverviewWidgetConfig.ListItems(it, maxEventDurationDays = durationDays.takeIf { hideLongEvents })
        }
        "announcements" -> limit?.let { OverviewWidgetConfig.ListItems(it, announcementPriority = priorities) }
        "schedule", "alerts", "date_time" -> OverviewWidgetConfig.None
        else -> null
    }

    if (showCwaKeyDialog) ProfileCwaApiKeyDialog(
        saving = cwaKeySaving, configured = cwaKeyConfigured, message = cwaKeyMessage,
        onConfirm = onSaveCwaApiKey,
        onRemove = onClearCwaApiKey,
        onDismiss = {
            if (!cwaKeySaving) {
                showCwaKeyDialog = false
                onDismissCwaKeyMessage()
            }
        },
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(OverviewWidgetDefinitions[widget.type]?.title ?: "元件設定",
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (widget.type) {
                    "weather", "youbike" -> {
                        SearchField(query, { query = it }, if (widget.type == "weather") "搜尋台灣縣市、鄉鎮" else "搜尋站名、行政區或地址")
                        if (widget.type == "weather") {
                            location?.let { Text(if (it.countryCode != "TW") "請重新選擇台灣地點" else "已選擇：${listOf(it.county, it.name).filter(String::isNotBlank).distinct().joinToString(" ")}",
                                style = MaterialTheme.typography.bodyMedium) }
                        } else station?.let { Text("已選擇：${it.name}", style = MaterialTheme.typography.bodyMedium) }
                        when {
                            searching -> CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = "正在搜尋" })
                            searchFailed -> {
                                Text("搜尋失敗，請重試", color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = hapticClick { retry++ }, modifier = Modifier.heightIn(min = 48.dp),
                                    shapes = ButtonDefaults.shapes()) { Text("重試") }
                            }
                            query.isNotBlank() && locations.isEmpty() && stations.isEmpty() ->
                                Text("找不到符合的結果", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (widget.type == "weather") {
                            locations.take(50).forEach { result ->
                                ChoiceRow(result.name, listOf(result.county, result.countryCode).filter(String::isNotBlank).joinToString(" · "),
                                    selected = location?.id == result.id, onSelect = { locationJson = OverviewLayoutJson.encodeToString(result) })
                            }
                            Text("資料來源", style = MaterialTheme.typography.titleSmall)
                            ChoiceRow("Open-Meteo", selected = source == WeatherSource.OPEN_METEO, onSelect = { sourceName = WeatherSource.OPEN_METEO.name })
                            ChoiceRow("中央氣象署", selected = source == WeatherSource.CWA, enabled = location?.supportsCwa == true,
                                onSelect = { sourceName = WeatherSource.CWA.name }, trailingContent = {
                                    IconButton(onClick = hapticClick {
                                        onDismissCwaKeyMessage()
                                        showCwaKeyDialog = true
                                    }, modifier = Modifier.size(48.dp), shapes = IconButtonDefaults.shapes()) {
                                        OutlinedRoundedSymbol("key", contentDescription = "設定中央氣象署授權碼")
                                    }
                                })
                            if (source == WeatherSource.CWA && !cwaKeyConfigured) Text("尚未設定授權碼，請點右側按鈕設定。", style = MaterialTheme.typography.bodySmall)
                            Text(
                                if (source == WeatherSource.CWA) "資料來源／交通部中央氣象署；依政府資料開放授權條款提供。"
                                else "資料來源／Open-Meteo.com；依 CC BY 4.0 提供。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else stations.take(50).forEach { result ->
                            ChoiceRow(result.name, listOf(result.district, result.address).filter(String::isNotBlank).joinToString(" · "),
                                selected = station?.id == result.id, onSelect = { stationJson = OverviewLayoutJson.encodeToString(result) })
                        }
                        if (locations.size > 50 || stations.size > 50)
                            Text("顯示前 50 筆，請輸入更完整的地點或站名", style = MaterialTheme.typography.bodySmall)
                    }
                    "countdown" -> {
                        ChoiceRow("學校行程", selected = !customCountdown, onSelect = { customCountdown = false })
                        ChoiceRow("自訂日期", selected = customCountdown, onSelect = { customCountdown = true })
                        if (customCountdown) {
                            OutlinedTextField(title, { title = it }, modifier = Modifier.fillMaxWidth(), label = { Text("名稱") }, singleLine = true)
                            TextButton(onClick = hapticClick { showDate = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                shapes = ButtonDefaults.shapes()) {
                                OutlinedRoundedSymbol("event")
                                Text(date?.toString() ?: "選擇日期", Modifier.padding(start = 8.dp))
                            }
                            ChoiceRow("全天", selected = !useTime, onSelect = { useTime = false })
                            ChoiceRow("指定時間", selected = useTime, onSelect = { useTime = true })
                            if (useTime) TextButton(onClick = hapticClick { showTime = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                shapes = ButtonDefaults.shapes()) {
                                OutlinedRoundedSymbol("schedule")
                                Text(time?.toString() ?: "選擇時間", Modifier.padding(start = 8.dp))
                            }
                            if (date != null && (if (useTime && time != null) !state.now.isBefore(date.atTime(time)) else date.isBefore(state.now.toLocalDate())))
                                Text("已到期，元件仍會保留", style = MaterialTheme.typography.bodySmall)
                        } else {
                            SearchField(query, { query = it }, "搜尋學校行程")
                            val events = state.calendarEvents.filter { (state.now.isBefore(it.endExclusive) || it.id == eventId) &&
                                (query.isBlank() || it.title.contains(query.trim(), ignoreCase = true)) }.sortedBy { it.start }
                            if (eventId.isNotBlank() && state.calendarEvents.none { it.id == eventId })
                                Text("原行程已不在資料中，可保留設定或重新選擇", style = MaterialTheme.typography.bodySmall)
                            if (state.calendarLoading) Text("正在載入行程…", style = MaterialTheme.typography.bodyMedium)
                            if (state.calendarFailed) Text("行程更新失敗，顯示現有資料", color = MaterialTheme.colorScheme.error)
                            if (events.isEmpty() && !state.calendarLoading) Text("找不到符合的行程", style = MaterialTheme.typography.bodyMedium)
                            events.forEach { event ->
                                ChoiceRow(event.title, event.start.toLocalDate().toString(), selected = event.id == eventId,
                                    onSelect = { eventId = event.id; eventTitle = event.title })
                            }
                        }
                    }
                    "calendar", "announcements" -> {
                        OutlinedTextField(limitText, { limitText = it }, modifier = Modifier.fillMaxWidth(),
                            label = { Text("最多顯示筆數") }, supportingText = { Text("1–20 筆") },
                            isError = limit == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                        if (widget.type == "announcements") {
                            Text("優先順序", style = MaterialTheme.typography.titleMedium)
                            Text("拖曳調整順序，越上方越優先", style = MaterialTheme.typography.bodySmall)
                            AnnouncementPriorityEditor(priorities) {
                                priorityJson = OverviewLayoutJson.encodeToString(it)
                            }
                        } else {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("隱藏長行程", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                Switch(checked = hideLongEvents, onCheckedChange = { hideLongEvents = it },
                                    modifier = Modifier.semantics { contentDescription = "隱藏長行程" })
                            }
                            if (hideLongEvents) OutlinedTextField(durationText, { durationText = it }, modifier = Modifier.fillMaxWidth(),
                                label = { Text("超過幾天不顯示") }, supportingText = { Text("依完整行程長度判斷；相同天數仍顯示") },
                                isError = durationDays == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                        }
                    }
                    "schedule", "alerts", "date_time" -> Text("此元件不需要其他設定", style = MaterialTheme.typography.bodyMedium)
                    else -> Text("此版本不支援這個元件的設定", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp), shapes = ButtonDefaults.shapes()) { Text("取消") }
                Button(enabled = configuration != null, onClick = hapticClick { configuration?.let { onConfirm(widget.withConfig(it)) } },
                    modifier = Modifier.heightIn(min = 48.dp), shapes = ButtonDefaults.shapes()) { Text("確定") }
            }
        }
    }

    if (showDate) {
        val initialDate = date ?: state.now.toLocalDate()
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            yearRange = minOf(1900, initialDate.year)..maxOf(2100, initialDate.year),
        )
        DatePickerDialog(onDismissRequest = { showDate = false }, confirmButton = {
            TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                picker.selectedDateMillis?.let { dateText = Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate().toString() }
                showDate = false
            }, modifier = Modifier.heightIn(min = 48.dp), shapes = ButtonDefaults.shapes()) { Text("確定") }
        }, dismissButton = {
            TextButton(onClick = { showDate = false }, modifier = Modifier.heightIn(min = 48.dp), shapes = ButtonDefaults.shapes()) { Text("取消") }
        }) { DatePicker(picker) }
    }
    if (showTime) {
        val picker = rememberTimePickerState(initialHour = time?.hour ?: 12, initialMinute = time?.minute ?: 0, is24Hour = true)
        AlertDialog(onDismissRequest = { showTime = false }, title = { Text("倒數時間") }, text = { TimePicker(picker) },
            confirmButton = { TextButton(onClick = {
                timeText = LocalTime.of(picker.hour, picker.minute).toString()
                showTime = false
            }, modifier = Modifier.heightIn(min = 48.dp), shapes = ButtonDefaults.shapes()) { Text("確定") } },
            dismissButton = { TextButton(onClick = { showTime = false }, modifier = Modifier.heightIn(min = 48.dp),
                shapes = ButtonDefaults.shapes()) { Text("取消") } })
    }
}

@Composable
private fun ChoiceRow(title: String, supporting: String = "", selected: Boolean, enabled: Boolean = true,
    onSelect: () -> Unit, trailingContent: @Composable () -> Unit = {}) {
    Surface(color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = selected, enabled = enabled,
            role = Role.RadioButton, onClick = hapticClick(onSelect)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                if (supporting.isNotBlank()) Text(supporting, style = MaterialTheme.typography.bodySmall)
            }
            trailingContent()
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(value, onValueChange, modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(label) }, leadingIcon = { OutlinedRoundedSymbol("search") }, trailingIcon = {
            if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(48.dp),
                shapes = IconButtonDefaults.shapes()) { OutlinedRoundedSymbol("close", contentDescription = "清除搜尋") }
        })
}
