package com.zhiban.rebuild.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.ui.components.ZhiBanAlertDialog
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun CreateEventPlanDialog(onDismiss: () -> Unit, onCreate: (EventPlanDraft) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var dateText by rememberSaveable { mutableStateOf(LocalDate.now().plusDays(7).format(DateTimeFormatter.ISO_LOCAL_DATE)) }
    var timeText by rememberSaveable { mutableStateOf("18:30") }
    var durationText by rememberSaveable { mutableStateOf("120") }
    var location by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val date = runCatching { LocalDate.parse(dateText) }.getOrNull()
    val time = runCatching { LocalTime.parse(timeText) }.getOrNull()
    val duration = durationText.toIntOrNull()
    val valid = title.isNotBlank() && date != null && time != null && duration in 15..1_440
    ZhiBanAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开始安排") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Sm)) {
                OutlinedTextField(title, { title = it }, label = { Text("做什么") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Sm)) {
                    OutlinedTextField(dateText, { dateText = it }, label = { Text("日期") }, singleLine = true, modifier = Modifier.weight(1.2f))
                    OutlinedTextField(timeText, { timeText = it }, label = { Text("时间") }, singleLine = true, modifier = Modifier.weight(0.8f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Sm)) {
                    OutlinedTextField(durationText, {
                        durationText = it.filter(Char::isDigit)
                    }, label = { Text("分钟") }, singleLine = true, modifier = Modifier.weight(0.8f))
                    OutlinedTextField(location, { location = it }, label = { Text("地点（可选）") }, singleLine = true, modifier = Modifier.weight(1.2f))
                }
                error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    if (date == null || time == null || duration == null) {
                        error = "请检查日期、时间和时长"
                    } else {
                        onCreate(
                            EventPlanDraft(
                                title = title,
                                startAtEpochMs = LocalDateTime.of(date, time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                                durationMinutes = duration,
                                location = location,
                                note = null,
                            ),
                        )
                    }
                },
            ) { Text("继续") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun EventContactPicker(contacts: List<ContactEntity>, onDismiss: () -> Unit, onSelect: (ContactEntity) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(contacts, query) {
        contacts.filter { query.isBlank() || it.displayName.contains(query, ignoreCase = true) || it.company.orEmpty().contains(query, ignoreCase = true) }
    }
    ZhiBanAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加参与人") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md)) {
                OutlinedTextField(query, { query = it }, label = { Text("搜索联系人") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    if (visible.isEmpty()) item { Text("没有找到联系人") }
                    items(visible, key = ContactEntity::contactId) { contact ->
                        TextButton(onClick = { onSelect(contact) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(contact.displayName)
                                contact.company?.let { Text(it, style = androidx.compose.material3.MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
