package com.zhiban.rebuild.ui.tabs

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zhiban.rebuild.data.agent.ScheduleProjection
import com.zhiban.rebuild.ui.theme.DateFormats
import com.zhiban.rebuild.ui.theme.ZhiBanIconContainer
import com.zhiban.rebuild.ui.theme.ZhiBanIconSize
import com.zhiban.rebuild.ui.theme.ZhiBanRadius
import com.zhiban.rebuild.ui.theme.ZhiBanSize
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun ScheduleEditorDialog(
    selectedDate: LocalDate,
    schedule: ScheduleProjection?,
    onDismiss: () -> Unit,
    onSaveToSystem: () -> Unit,
    notificationsAllowed: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onSaveWithReminder: (
        String?,
        String,
        LocalDate,
        LocalTime,
        Int,
        String?,
        Int?,
        Boolean,
        (CalendarAgentViewModel.SaveResult) -> Unit,
    ) -> Unit,
) {
    val original = schedule?.let { Instant.ofEpochMilli(it.startAtEpochMs).atZone(ZoneId.systemDefault()) }
    var title by remember(schedule?.id) { mutableStateOf(schedule?.title.orEmpty()) }
    var dateText by remember(schedule?.id) {
        mutableStateOf((original?.toLocalDate() ?: selectedDate).format(DateTimeFormatter.ISO_LOCAL_DATE))
    }
    var timeText by remember(schedule?.id) {
        mutableStateOf(
            (
                original?.toLocalTime()
                    ?: LocalTime.now().plusHours(1).withMinute(0)
                ).format(DateFormats.Time),
        )
    }
    var durationText by remember(schedule?.id) { mutableStateOf((schedule?.durationMinutes ?: 60).toString()) }
    var note by remember(schedule?.id) { mutableStateOf(schedule?.note.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var reminderMinutes by remember(schedule?.id) { mutableStateOf(schedule?.reminderMinutesBefore) }
    var pendingConflict by remember { mutableStateOf<String?>(null) }
    val formValid = title.isNotBlank() &&
        runCatching { LocalDate.parse(dateText) }.isSuccess &&
        runCatching { LocalTime.parse(timeText) }.isSuccess &&
        (durationText.toIntOrNull()?.let { it in 1..1440 } == true)

    fun submit(allowConflict: Boolean) {
        val date = runCatching { LocalDate.parse(dateText) }.getOrNull()
        val time = runCatching { LocalTime.parse(timeText) }.getOrNull()
        val duration = durationText.toIntOrNull()
        when {
            title.isBlank() -> error = "请输入日程名称"

            date == null -> error = "日期格式应为 2026-07-24"

            time == null -> error = "时间格式应为 09:30"

            duration == null || duration !in 1..1440 -> error = "时长应为 1–1440 分钟"

            else -> onSaveWithReminder(
                schedule?.id,
                title,
                date,
                time,
                duration,
                note,
                reminderMinutes,
                allowConflict,
            ) { result ->
                when (result) {
                    is CalendarAgentViewModel.SaveResult.Conflict -> pendingConflict = result.message
                    is CalendarAgentViewModel.SaveResult.Failed -> error = result.message
                    is CalendarAgentViewModel.SaveResult.Saved -> Unit
                }
            }
        }
    }

    com.zhiban.rebuild.ui.components.ZhiBanTaskDialog(
        onDismissRequest = onDismiss,
        maxWidth = 560.dp,
        maxHeight = 720.dp,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            com.zhiban.rebuild.ui.components.ZhiBanDialogHeader(
                title = if (schedule == null) "新建日程" else "编辑日程",
                onDismiss = onDismiss,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(title, {
                title = it
                error = null
            }, Modifier.fillMaxWidth(), label = { Text("日程名称") }, singleLine = true)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(dateText, {
                    dateText = it
                    error = null
                }, Modifier.weight(1.35f), label = { Text("日期") }, singleLine = true)
                OutlinedTextField(timeText, {
                    timeText = it
                    error = null
                }, Modifier.weight(1f), label = { Text("开始") }, singleLine = true)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                durationText,
                {
                    durationText = it.filter(Char::isDigit)
                    error = null
                },
                Modifier.fillMaxWidth(),
                label = { Text("时长（分钟）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(note, {
                note = it
            }, Modifier.fillMaxWidth(), label = { Text("备注（可选）") }, minLines = 2, maxLines = 4)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.NotificationsNone,
                    null,
                    modifier = Modifier.size(ZhiBanIconSize.Field),
                    tint = CalendarInk,
                )
                Spacer(Modifier.width(8.dp))
                Text("提醒我", color = CalendarInk, style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    listOf(null to "不提醒", 10 to "提前 10 分钟", 30 to "提前 30 分钟"),
                    listOf(60 to "提前 1 小时", 1_440 to "提前 1 天"),
                ).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (minutes, label) ->
                            val selected = reminderMinutes == minutes
                            Text(
                                label,
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(if (selected) CalendarAccent else CalendarSoft)
                                    .clickable {
                                        reminderMinutes = minutes
                                        if (minutes != null && !notificationsAllowed) onRequestNotificationPermission()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else CalendarInk,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = CalendarDanger, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(18.dp))
            pendingConflict?.let {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CalendarSoft).padding(14.dp),
                ) {
                    Text(
                        it,
                        color = CalendarInk,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text("你仍然可以保存，两个日程会同时保留。", color = CalendarMuted, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        pendingConflict = null
                        submit(true)
                    }, contentPadding = PaddingValues(0.dp)) {
                        Text("仍然保存", color = CalendarDanger)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            Button(
                onClick = {
                    pendingConflict = null
                    submit(false)
                },
                modifier = Modifier.fillMaxWidth().height(ZhiBanSize.Control),
                enabled = formValid,
                colors = ButtonDefaults.buttonColors(containerColor = CalendarAccent),
                shape = RoundedCornerShape(ZhiBanRadius.Card),
            ) { Text("保存") }
            if (schedule != null) {
                TextButton(onClick = onSaveToSystem, modifier = Modifier.fillMaxWidth()) {
                    Text("添加到手机系统日历", color = CalendarInk)
                }
                Text(
                    "将打开手机系统日历页面，由你检查并确认保存",
                    color = CalendarMuted,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}
