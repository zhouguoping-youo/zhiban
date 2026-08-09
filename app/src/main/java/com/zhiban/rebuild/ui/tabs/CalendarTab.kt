package com.zhiban.rebuild.ui.tabs

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.zhiban.rebuild.data.agent.ScheduleProjection
import com.zhiban.rebuild.data.calendar.SystemCalendarEvent
import com.zhiban.rebuild.data.calendar.SystemCalendarWriteIntent
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.data.notification.NotificationInsightAnalyzer
import com.zhiban.rebuild.data.notification.ScheduleInsight
import com.zhiban.rebuild.ui.components.ZhiBanHeaderIconAction
import com.zhiban.rebuild.ui.components.ZhiBanPrimaryTabHeader
import com.zhiban.rebuild.ui.components.ZhiBanTabBottomSpacer
import com.zhiban.rebuild.ui.components.ZhiBanTabHorizontalPadding
import com.zhiban.rebuild.ui.components.ZhiBanTabTopPadding
import com.zhiban.rebuild.ui.components.zhiBanCardSurface
import com.zhiban.rebuild.ui.theme.DangerRed
import com.zhiban.rebuild.ui.theme.DateFormats
import com.zhiban.rebuild.ui.theme.ZhiBanCard
import com.zhiban.rebuild.ui.theme.ZhiBanDivider
import com.zhiban.rebuild.ui.theme.ZhiBanIconContainer
import com.zhiban.rebuild.ui.theme.ZhiBanIconSize
import com.zhiban.rebuild.ui.theme.ZhiBanTerracotta
import com.zhiban.rebuild.ui.theme.ZhiBanTextPrimary
import com.zhiban.rebuild.ui.theme.ZhiBanTextSecondary
import com.zhiban.rebuild.ui.theme.ZhiBanWarmBackground
import com.zhiban.rebuild.ui.theme.ZhiBanWarmCanvas
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

internal val CalendarBackground: Color @Composable get() = MaterialTheme.colorScheme.background
internal val CalendarSurface: Color @Composable get() = MaterialTheme.colorScheme.surface
internal val CalendarInk: Color @Composable get() = MaterialTheme.colorScheme.onBackground
internal val CalendarMuted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
internal val CalendarSoft: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
internal val CalendarLine: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
internal val CalendarAccent: Color @Composable get() = MaterialTheme.colorScheme.primary
internal val CalendarDanger: Color @Composable get() = MaterialTheme.colorScheme.error

@Composable
fun CalendarTab(
    modifier: Modifier = Modifier,
    isDataEmpty: Boolean = false,
    focusDateEpochMs: Long? = null,
    viewModel: CalendarAgentViewModel = hiltViewModel(),
) {
    if (isDataEmpty) {
        MainTabEmptyPage("calendar", modifier)
        return
    }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var monthExpanded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ScheduleProjection?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ScheduleProjection?>(null) }
    val schedules by viewModel.schedules.collectAsState()
    val messageScheduleCandidates by viewModel.messageScheduleCandidates.collectAsState()
    val importState by viewModel.importState.collectAsState()
    val context = LocalContext.current
    var showImportDialog by remember { mutableStateOf(false) }
    var showPermissionExplanation by remember { mutableStateOf(false) }
    var notificationsAllowed by remember {
        mutableStateOf(
            android.os.Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var reminderPermissionMessage by remember { mutableStateOf(false) }
    var suggestionMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusDateEpochMs) {
        focusDateEpochMs?.let { epochMs ->
            val focusedDate = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
            selectedDate = focusedDate
            viewModel.selectDay(focusedDate)
        }
    }
    val calendarPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                showImportDialog = true
                viewModel.loadSystemCalendar()
            } else {
                showPermissionExplanation = true
            }
        }
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationsAllowed = granted
            if (!granted) reminderPermissionMessage = true
        }

    Box(modifier.fillMaxSize().background(CalendarBackground)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = ZhiBanTabHorizontalPadding,
                end = ZhiBanTabHorizontalPadding,
                top = ZhiBanTabTopPadding,
                bottom = ZhiBanTabBottomSpacer,
            ),
        ) {
            item {
                CalendarHeader(
                    date = selectedDate,
                    monthExpanded = monthExpanded,
                    onToday = {
                        selectedDate = LocalDate.now()
                        viewModel.selectDay(selectedDate)
                    },
                    onMonth = { monthExpanded = !monthExpanded },
                    onImport = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            showImportDialog = true
                            viewModel.loadSystemCalendar()
                        } else {
                            calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                        }
                    },
                    onAdd = {
                        editing = null
                        showEditor = true
                    },
                )
                Spacer(Modifier.height(18.dp))
            }
            if (messageScheduleCandidates.isNotEmpty()) {
                item {
                    MessageScheduleSuggestions(
                        candidates = messageScheduleCandidates,
                        onConfirm = { candidate ->
                            suggestionMessage = null
                            viewModel.confirmMessageSchedule(candidate, notificationsAllowed) { result ->
                                when (result) {
                                    is CalendarAgentViewModel.SaveResult.Saved -> {
                                        ScheduleInsight.from(candidate)?.let {
                                            selectedDate = Instant.ofEpochMilli(it.startAtEpochMs)
                                                .atZone(ZoneId.systemDefault())
                                                .toLocalDate()
                                            viewModel.selectDay(selectedDate)
                                        }
                                        if (result.notificationPermissionNeeded) reminderPermissionMessage = true
                                    }

                                    is CalendarAgentViewModel.SaveResult.Conflict -> suggestionMessage = result.message

                                    is CalendarAgentViewModel.SaveResult.Failed -> suggestionMessage = result.message
                                }
                            }
                        },
                        onDismiss = viewModel::dismissMessageCandidate,
                    )
                    suggestionMessage?.let {
                        Text(
                            it,
                            modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
                            color = CalendarDanger,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                }
            }
            if (monthExpanded) {
                item {
                    MonthGrid(selectedDate) {
                        selectedDate = it
                        viewModel.selectDay(it)
                    }
                }
            } else {
                item {
                    WeekStrip(selectedDate) {
                        selectedDate = it
                        viewModel.selectDay(it)
                    }
                }
            }
            item {
                Spacer(Modifier.height(24.dp))
                Text(
                    dayHeading(selectedDate),
                    style = MaterialTheme.typography.titleMedium,
                    color = CalendarInk,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
            }
            if (schedules.isEmpty()) {
                item {
                    EmptyDay(onAdd = {
                        editing = null
                        showEditor = true
                    })
                }
            } else {
                items(schedules.size, key = { schedules[it].id }) { index ->
                    ScheduleRow(
                        schedule = schedules[index],
                        onClick = {
                            editing = schedules[index]
                            showEditor = true
                        },
                        onDelete = { deleting = schedules[index] },
                    )
                    if (index != schedules.lastIndex) {
                        Box(Modifier.fillMaxWidth().padding(start = 70.dp).height(1.dp).background(CalendarLine))
                    }
                }
            }
            item {
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showEditor) {
        ScheduleEditorDialog(
            selectedDate = selectedDate,
            schedule = editing,
            onDismiss = { showEditor = false },
            onSaveToSystem = schedule@{
                val value = editing ?: return@schedule
                runCatching { context.startActivity(SystemCalendarWriteIntent.create(value)) }
            },
            notificationsAllowed = notificationsAllowed,
            onRequestNotificationPermission = {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onSaveWithReminder = { id, title, date, time, duration, note, reminder, allowConflict, onResult ->
                val epoch = LocalDateTime.of(date, time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                viewModel.save(
                    id,
                    title,
                    epoch,
                    duration,
                    note,
                    reminder,
                    allowConflict,
                    notificationsAllowed,
                ) { result ->
                    onResult(result)
                    if (result is CalendarAgentViewModel.SaveResult.Saved) {
                        selectedDate = date
                        viewModel.selectDay(date)
                        showEditor = false
                        if (result.notificationPermissionNeeded) reminderPermissionMessage = true
                    }
                }
            },
        )
    }
    deleting?.let { schedule ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除日程？") },
            text = { Text("“${schedule.title}”将从日历中移除。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(schedule.id) { deleting = null }
                }) { Text("删除", color = CalendarDanger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消", color = CalendarInk) } },
            containerColor = CalendarSurface,
        )
    }
    if (showImportDialog) {
        SystemCalendarImportDialog(
            state = importState,
            onDismiss = {
                showImportDialog = false
                viewModel.clearImportState()
            },
            onImport = viewModel::importSystemCalendar,
        )
    }
    if (showPermissionExplanation) {
        AlertDialog(
            onDismissRequest = { showPermissionExplanation = false },
            title = { Text("需要日历权限") },
            text = { Text("知伴只读取你确认导入的日程，不会修改手机系统日历。你可以在系统设置中随时关闭权限。") },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionExplanation = false
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        },
                    )
                }) { Text("打开系统设置", color = CalendarInk) }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionExplanation = false }) { Text("暂不导入", color = CalendarMuted) }
            },
            containerColor = CalendarSurface,
        )
    }
    if (reminderPermissionMessage) {
        AlertDialog(
            onDismissRequest = { reminderPermissionMessage = false },
            title = { Text("提醒已保存") },
            text = { Text("还需要开启通知权限，知伴才能在日程开始前提醒你。") },
            confirmButton = {
                TextButton(onClick = {
                    reminderPermissionMessage = false
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }) { Text("开启通知", color = CalendarInk) }
            },
            dismissButton = {
                TextButton(onClick = { reminderPermissionMessage = false }) { Text("稍后再说", color = CalendarMuted) }
            },
            containerColor = CalendarSurface,
        )
    }
}

@Composable
private fun MessageScheduleSuggestions(
    candidates: List<NotificationCandidateEntity>,
    onConfirm: (NotificationCandidateEntity) -> Unit,
    onDismiss: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .zhiBanCardSurface(CalendarSurface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(CalendarSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.EventAvailable,
                    null,
                    tint = CalendarInk,
                    modifier = Modifier.size(ZhiBanIconSize.Field),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("消息里的日程", color = CalendarInk, fontWeight = FontWeight.SemiBold)
                Text("知伴发现了明确的日期和时间", color = CalendarMuted, style = MaterialTheme.typography.bodySmall)
            }
            if (candidates.size > 1) {
                Text("${candidates.size} 条", color = CalendarMuted, style = MaterialTheme.typography.labelMedium)
            }
        }
        candidates.take(3).forEachIndexed { index, candidate ->
            val insight = ScheduleInsight.from(candidate) ?: return@forEachIndexed
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(CalendarLine))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    normalizeScheduleSuggestionTitle(insight.title),
                    color = CalendarInk,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                )
                Text(
                    "${candidate.appLabel}${candidate.senderName?.let { " · $it" }.orEmpty()} · ${
                        DateFormats.MonthDayTime
                            .format(Instant.ofEpochMilli(insight.startAtEpochMs).atZone(ZoneId.systemDefault()))
                    }",
                    color = CalendarMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { onDismiss(candidate.candidateId) },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("忽略", color = CalendarMuted) }
                    TextButton(
                        onClick = { onConfirm(candidate) },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("加入日程", color = CalendarInk, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

private fun normalizeScheduleSuggestionTitle(rawTitle: String): String = NotificationInsightAnalyzer.sanitizeScheduleTitle(rawTitle, null).ifBlank { rawTitle }

@Composable
internal fun CalendarHeader(date: LocalDate, monthExpanded: Boolean, onToday: () -> Unit, onMonth: () -> Unit, onImport: () -> Unit, onAdd: () -> Unit) {
    ZhiBanPrimaryTabHeader(
        title = "日历",
        subtitle = date.format(DateTimeFormatter.ofPattern("yyyy年 M月")),
    ) {
        ZhiBanHeaderIconAction(
            icon = Icons.Outlined.Today,
            contentDescription = "回到今天",
            onClick = onToday,
            tint = CalendarInk,
        )
        ZhiBanHeaderIconAction(
            icon = Icons.Outlined.EventAvailable,
            contentDescription = "从手机日历导入",
            onClick = onImport,
            tint = CalendarInk,
        )
        ZhiBanHeaderIconAction(
            icon = Icons.Outlined.CalendarMonth,
            contentDescription = if (monthExpanded) "收起全日历" else "查看全日历",
            onClick = onMonth,
            tint = CalendarInk,
        )
        ZhiBanHeaderIconAction(
            icon = Icons.Outlined.Add,
            contentDescription = "新建日程",
            onClick = onAdd,
            tint = CalendarInk,
        )
    }
}

@Composable
private fun SystemCalendarImportDialog(state: CalendarAgentViewModel.ImportState, onDismiss: () -> Unit, onImport: (Set<String>) -> Unit) {
    val sources = remember(state.events) { groupSystemCalendarEvents(state.events) }
    var selectedSources: Set<String> by remember(sources) {
        mutableStateOf(sources.mapTo(linkedSetOf(), SystemCalendarSource::key))
    }
    val selectedEvents = remember(sources, selectedSources) {
        sources.filter { it.key in selectedSources }.flatMap(SystemCalendarSource::events)
    }
    val maxDialogHeight = (LocalConfiguration.current.screenHeightDp.dp - 64.dp)
        .coerceIn(320.dp, 640.dp)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .heightIn(max = maxDialogHeight)
                .clip(RoundedCornerShape(28.dp))
                .background(CalendarSurface)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "导入手机日历",
                        color = CalendarInk,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("最近 30 天至未来 90 天", color = CalendarMuted, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, "关闭") }
            }
            when {
                state.isLoading -> {
                    Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), color = CalendarInk, strokeWidth = 2.dp)
                    }
                }

                state.resultMessage != null -> {
                    Text(
                        state.resultMessage,
                        Modifier.fillMaxWidth().padding(top = 44.dp, bottom = 8.dp),
                        color = CalendarInk,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Text(
                        "已复制到知伴；以后手机日历中的修改不会自动同步。",
                        Modifier.fillMaxWidth().padding(bottom = 36.dp),
                        color = CalendarMuted,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Button(
                        onClick = onDismiss,
                        Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CalendarInk),
                        shape = RoundedCornerShape(25.dp),
                    ) { Text("完成") }
                }

                state.events.isEmpty() -> {
                    Text(
                        state.error ?: "这个时间范围内没有可导入的系统日程",
                        Modifier.fillMaxWidth().padding(vertical = 54.dp),
                        color = if (state.error == null) CalendarMuted else CalendarDanger,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text("完成", color = CalendarInk)
                    }
                }

                else -> {
                    Text(
                        "选择要导入的日历",
                        Modifier.padding(top = 18.dp),
                        color = CalendarInk,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "日程会一次性复制到知伴，不会修改手机日历，也不会自动同步后续变化。",
                        Modifier.padding(top = 4.dp, bottom = 10.dp),
                        color = CalendarMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (sources.size > 1) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    selectedSources = if (selectedSources.size == sources.size) {
                                        linkedSetOf()
                                    } else {
                                        sources.mapTo(linkedSetOf(), SystemCalendarSource::key)
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 0.dp),
                            ) {
                                Text(
                                    if (selectedSources.size == sources.size) "取消全选" else "选择全部",
                                    color = CalendarInk,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                "已选 ${selectedEvents.size} 条",
                                color = CalendarMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 300.dp),
                    ) {
                        items(sources.size, key = { sources[it].key }) { index ->
                            val source = sources[index]
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    selectedSources = selectedSources.toMutableSet().apply {
                                        if (!add(source.key)) remove(source.key)
                                    }
                                }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = source.key in selectedSources, onCheckedChange = null)
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        source.name,
                                        color = CalendarInk,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                    )
                                    Text(
                                        "${source.events.size} 条日程",
                                        color = CalendarMuted,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                    state.error?.let { Text(it, color = CalendarDanger, style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { onImport(selectedEvents.mapTo(linkedSetOf(), SystemCalendarEvent::sourceId)) },
                        Modifier.fillMaxWidth().height(50.dp),
                        enabled = selectedEvents.isNotEmpty() && !state.isImporting,
                        colors = ButtonDefaults.buttonColors(containerColor = CalendarInk),
                        shape = RoundedCornerShape(25.dp),
                    ) {
                        if (state.isImporting) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("导入 ${selectedEvents.size} 条日程")
                        }
                    }
                }
            }
        }
    }
}

internal data class SystemCalendarSource(val key: String, val name: String, val events: List<SystemCalendarEvent>)

internal fun groupSystemCalendarEvents(events: List<SystemCalendarEvent>): List<SystemCalendarSource> = events.groupBy { event ->
    event.calendarId?.let { "calendar:$it" }
        ?: "name:${event.calendarName?.trim()?.takeIf(String::isNotEmpty) ?: "未分类"}"
}.map { (key, values) ->
    SystemCalendarSource(
        key = key,
        name = values.firstNotNullOfOrNull { event ->
            event.calendarName?.trim()?.takeIf(String::isNotEmpty)
        } ?: "其他日历",
        events = values,
    )
}

@Composable
private fun WeekStrip(selected: LocalDate, onSelect: (LocalDate) -> Unit) {
    val monday = selected.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    Row(
        Modifier.fillMaxWidth().semantics { contentDescription = "本周日历" },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        (0L..6L).forEach { offset ->
            val date = monday.plusDays(offset)
            val active = date == selected
            Column(
                Modifier.weight(1f).defaultMinSize(minHeight = 48.dp).clip(RoundedCornerShape(18.dp))
                    .semantics { contentDescription = "选择 $date" }
                    .selectable(selected = active, role = Role.Button) { onSelect(date) }
                    .padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.CHINA),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) CalendarInk else CalendarMuted,
                )
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier.size(
                        34.dp,
                    ).clip(CircleShape).background(if (active) CalendarAccent else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        date.dayOfMonth.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (active) Color.White else CalendarInk,
                        fontWeight = if (active || date == LocalDate.now()) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
internal fun MonthGrid(selected: LocalDate, onSelect: (LocalDate) -> Unit) {
    val first = selected.withDayOfMonth(1)
    val gridStart = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    Column(
        Modifier.fillMaxWidth().semantics { contentDescription = "全月日历" }
            .zhiBanCardSurface(CalendarSurface).padding(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { day ->
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Text(day, color = CalendarMuted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        repeat(6) { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                repeat(7) { day ->
                    val date = gridStart.plusDays((week * 7 + day).toLong())
                    Box(
                        Modifier.weight(1f).height(48.dp).clip(CircleShape)
                            .background(if (date == selected) CalendarAccent else Color.Transparent)
                            .semantics { contentDescription = "选择 $date" }
                            .selectable(selected = date == selected, role = Role.Button) { onSelect(date) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            date.dayOfMonth.toString(),
                            color = when {
                                date == selected -> Color.White
                                date.month != selected.month -> Color(0xFFB8B8B8)
                                else -> CalendarInk
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleRow(schedule: ScheduleProjection, onClick: () -> Unit, onDelete: () -> Unit) {
    val start = Instant.ofEpochMilli(schedule.startAtEpochMs).atZone(ZoneId.systemDefault()).toLocalTime()
    val end = start.plusMinutes(schedule.durationMinutes.toLong())
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 15.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.width(62.dp)) {
            Text(
                start.format(DateFormats.Time),
                color = CalendarInk,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                end.format(DateFormats.Time),
                color = CalendarMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Box(Modifier.padding(top = 7.dp).size(7.dp).clip(CircleShape).background(CalendarAccent))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                schedule.title,
                color = CalendarInk,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            schedule.note?.takeIf(String::isNotBlank)?.let {
                Spacer(Modifier.height(3.dp))
                Text(it, color = CalendarMuted, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            Text(
                buildString {
                    append("${schedule.durationMinutes} 分钟")
                    schedule.reminderMinutesBefore?.let {
                        append(" · ")
                        append(
                            when (it) {
                                1_440 -> "提前 1 天提醒"
                                60 -> "提前 1 小时提醒"
                                else -> "提前 $it 分钟提醒"
                            },
                        )
                    }
                },
                color = CalendarMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(ZhiBanIconContainer.TouchTarget)) {
            Icon(
                Icons.Rounded.DeleteOutline,
                "删除${schedule.title}",
                tint = CalendarMuted,
                modifier = Modifier.size(ZhiBanIconSize.Action),
            )
        }
    }
}

@Composable
internal fun EmptyDay(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().zhiBanCardSurface(CalendarSurface).padding(horizontal = 22.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(CalendarSoft), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Outlined.CalendarMonth,
                null,
                tint = CalendarInk,
                modifier = Modifier.size(ZhiBanIconSize.Leading),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "这一天还没有安排",
            color = CalendarInk,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))
        Text("添加日程，到时提醒你", color = CalendarMuted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onAdd,
            colors = ButtonDefaults.buttonColors(containerColor = CalendarAccent),
            shape = RoundedCornerShape(50),
        ) {
            Icon(Icons.Outlined.Add, null, modifier = Modifier.size(ZhiBanIconSize.Inline))
            Spacer(Modifier.width(6.dp))
            Text("添加日程")
        }
    }
}

private fun dayHeading(date: LocalDate): String = when (date) {
    LocalDate.now() -> "今天"
    LocalDate.now().plusDays(1) -> "明天"
    LocalDate.now().minusDays(1) -> "昨天"
    else -> date.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA))
}
