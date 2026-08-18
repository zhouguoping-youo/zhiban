package com.zhiban.rebuild.ui.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiban.rebuild.R
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.event.EventPlanStatus
import com.zhiban.rebuild.data.event.EventResponseStatus
import com.zhiban.rebuild.ui.components.ZhiBanAlertDialog
import com.zhiban.rebuild.ui.components.ZhiBanLeadingIcon
import com.zhiban.rebuild.ui.components.ZhiBanPage
import com.zhiban.rebuild.ui.components.ZhiBanSectionTitle
import com.zhiban.rebuild.ui.components.ZhiBanTopBar
import com.zhiban.rebuild.ui.components.localizedQuantity
import com.zhiban.rebuild.ui.components.zhiBanCardSurface
import com.zhiban.rebuild.ui.theme.ZhiBanIconSize
import com.zhiban.rebuild.ui.theme.ZhiBanSize
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing
import com.zhiban.rebuild.ui.theme.ZhiBanTerracottaSoft
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun EventPlanningPage(onBack: () -> Unit, onOpenAll: () -> Unit, onOpenPlan: (String) -> Unit, viewModel: EventPlanningViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var createOpen by rememberSaveable { mutableStateOf(false) }
    EventPlanningWorkbench(
        state = state,
        onBack = onBack,
        onCreate = { createOpen = true },
        onOpenAll = onOpenAll,
        onOpenPlan = onOpenPlan,
        onDismissMessage = viewModel::clearMessage,
    )
    if (createOpen) {
        CreateEventPlanDialog(
            onDismiss = { createOpen = false },
            onCreate = { draft ->
                viewModel.createPlan(draft) { planId ->
                    createOpen = false
                    onOpenPlan(planId)
                }
            },
        )
    }
}

@Composable
fun EventPlanningListPage(onBack: () -> Unit, onOpenPlan: (String) -> Unit, viewModel: EventPlanningViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ZhiBanPage {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = ZhiBanSpacing.Xxl),
            verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Sm),
        ) {
            item { ZhiBanTopBar("全部安排", onBack) }
            if (state.plans.isEmpty() && !state.isLoading) {
                item { EventPlanningEmpty(Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal), null) }
            } else {
                eventPlanSections(state).forEach { section ->
                    if (section.items.isNotEmpty()) {
                        item {
                            ZhiBanSectionTitle(
                                section.title,
                                modifier = Modifier.padding(
                                    start = ZhiBanSpacing.PageHorizontal,
                                    end = ZhiBanSpacing.PageHorizontal,
                                    top = ZhiBanSpacing.Md,
                                ),
                            )
                        }
                        items(section.items, key = { it.plan.planId }) { item ->
                            EventPlanRow(
                                item,
                                onClick = { onOpenPlan(item.plan.planId) },
                                modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EventPlanningDetailPage(planId: String, onBack: () -> Unit, onAskAgent: (String) -> Unit, viewModel: EventPlanningViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val item = state.plans.firstOrNull { it.plan.planId == planId }
    var contactPickerOpen by rememberSaveable { mutableStateOf(false) }
    var responseContact by remember { mutableStateOf<ContactEntity?>(null) }
    var deleteConfirmOpen by remember { mutableStateOf(false) }
    ZhiBanPage {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = ZhiBanSpacing.Xxl),
            verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Lg),
        ) {
            item { ZhiBanTopBar("安排详情", onBack) }
            if (item == null) {
                if (!state.isLoading) item { EventPlanningEmpty(Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal), null) }
            } else {
                item { EventPlanSummary(item, Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal)) }
                item {
                    ZhiBanSectionTitle(
                        title = "参与人",
                        action = "添加",
                        onActionClick = { contactPickerOpen = true },
                        modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                    )
                }
                if (item.participants.isEmpty()) {
                    item {
                        OutlinedButton(
                            onClick = { contactPickerOpen = true },
                            modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal).fillMaxWidth().height(48.dp),
                        ) {
                            Icon(Icons.Outlined.PersonAddAlt, null, Modifier.size(ZhiBanIconSize.Inline))
                            Spacer(Modifier.size(ZhiBanSpacing.Sm))
                            Text("选择联系人")
                        }
                    }
                } else {
                    items(item.participants, key = { it.contact.contactId }) { participant ->
                        ParticipantRow(
                            participant = participant,
                            onClick = { responseContact = participant.contact },
                            onRemove = { viewModel.removeParticipant(planId, participant.contact.contactId) },
                            modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                        )
                    }
                }
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                        verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Sm),
                    ) {
                        OutlinedButton(
                            onClick = { onAskAgent(eventInvitePrompt(item)) },
                            enabled = item.participants.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) { Text("准备邀请") }
                        Button(
                            onClick = { viewModel.confirmToCalendar(item) },
                            enabled = item.participants.isNotEmpty() && item.plan.status != EventPlanStatus.CONFIRMED,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) {
                            Text(if (item.plan.status == EventPlanStatus.CONFIRMED) "已加入日历" else "确定并加入日历")
                        }
                        TextButton(
                            onClick = { deleteConfirmOpen = true },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) {
                            Text("删除这项安排", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            state.actionMessage?.let { message ->
                item {
                    EventPlanningMessage(
                        message,
                        viewModel::clearMessage,
                        Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                    )
                }
            }
        }
    }
    EventPlanningDetailDialogs(
        EventPlanningDetailDialogSlots(
            contactPickerOpen = contactPickerOpen,
            setContactPickerOpen = { contactPickerOpen = it },
            responseContact = responseContact,
            setResponseContact = { responseContact = it },
            deleteConfirmOpen = deleteConfirmOpen,
            setDeleteConfirmOpen = { deleteConfirmOpen = it },
            item = item,
            state = state,
            planId = planId,
            viewModel = viewModel,
            onBack = onBack,
        ),
    )
}

@Composable
internal fun EventPlanningWorkbench(
    state: EventPlanningState,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onOpenAll: () -> Unit,
    onOpenPlan: (String) -> Unit,
    onDismissMessage: () -> Unit,
) {
    ZhiBanPage {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = ZhiBanSpacing.Xxxl),
            verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Section),
        ) {
            item {
                ZhiBanTopBar(
                    "一起安排",
                    onBack,
                    subtitle = "聚会、探望与出行",
                    trailing = if (state.plans.isNotEmpty()) {
                        {
                            IconButton(
                                onClick = onCreate,
                                modifier = Modifier.size(ZhiBanSize.TouchTarget),
                            ) {
                                Icon(
                                    Icons.Outlined.Add,
                                    contentDescription = "新建安排",
                                    modifier = Modifier.size(ZhiBanIconSize.Action),
                                )
                            }
                        }
                    } else {
                        null
                    },
                )
            }
            if (state.plans.isEmpty() && !state.isLoading) {
                item { EventPlanningEmpty(Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal), onCreate) }
            } else {
                state.activePlans.firstOrNull()?.let { spotlight ->
                    item {
                        EventPlanSpotlight(
                            spotlight,
                            { onOpenPlan(spotlight.plan.planId) },
                            Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                        )
                    }
                }
                if (state.upcomingPlans.isNotEmpty()) {
                    item { ZhiBanSectionTitle("即将发生", Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal)) }
                    items(state.upcomingPlans.take(3), key = { "upcoming-${it.plan.planId}" }) { item ->
                        EventPlanRow(
                            item,
                            { onOpenPlan(item.plan.planId) },
                            Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                        )
                    }
                }
                if (state.plans.isNotEmpty()) {
                    item {
                        ZhiBanSectionTitle(
                            "全部",
                            action = localizedQuantity(R.plurals.view_item_count, state.plans.size),
                            onActionClick = onOpenAll,
                            modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                        )
                    }
                }
            }
            state.actionMessage?.let { message ->
                item {
                    EventPlanningMessage(
                        message,
                        onDismissMessage,
                        Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                    )
                }
            }
        }
    }
}

@Composable
private fun EventPlanSpotlight(item: EventPlanUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().zhiBanCardSurface(ZhiBanTerracottaSoft).clickable(onClick = onClick).padding(ZhiBanSpacing.Xl),
        verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZhiBanLeadingIcon(Icons.Outlined.Groups)
            Spacer(Modifier.size(ZhiBanSpacing.Md))
            Column(Modifier.weight(1f)) {
                Text(item.plan.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(eventDateLabel(item.plan.proposedStartAtEpochMs), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(eventProgressLabel(item), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("继续安排") }
    }
}

@Composable
private fun EventPlanSummary(item: EventPlanUi, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().zhiBanCardSurface().padding(ZhiBanSpacing.Xl),
        verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md),
    ) {
        Text(item.plan.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        EventSummaryLine(Icons.Outlined.CalendarMonth, eventDateLabel(item.plan.proposedStartAtEpochMs))
        item.plan.location?.let { EventSummaryLine(Icons.Outlined.LocationOn, it) }
        EventSummaryLine(Icons.Outlined.Groups, eventProgressLabel(item))
        if (item.plan.status == EventPlanStatus.CONFIRMED) {
            EventSummaryLine(Icons.Outlined.CheckCircleOutline, "已写入日历")
        }
    }
}

@Composable
private fun EventSummaryLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(ZhiBanIconSize.Inline), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(ZhiBanSpacing.Sm))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EventPlanRow(item: EventPlanUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = ZhiBanSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZhiBanLeadingIcon(Icons.Outlined.Groups)
        Spacer(Modifier.size(ZhiBanSpacing.Md))
        Column(Modifier.weight(1f)) {
            Text(item.plan.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${eventDateLabel(item.plan.proposedStartAtEpochMs)} · ${eventProgressLabel(item)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, modifier = Modifier.size(ZhiBanIconSize.Inline))
    }
}

@Composable
private fun ParticipantRow(participant: EventParticipantUi, onClick: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(participant.contact.displayName, style = MaterialTheme.typography.titleMedium)
            Text(responseLabel(participant.responseStatus), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onClick) { Text("更新") }
        TextButton(onClick = onRemove) { Text("移除") }
    }
}

@Composable
private fun EventPlanningEmpty(modifier: Modifier = Modifier, onCreate: (() -> Unit)?) {
    if (onCreate == null) {
        SceneCapabilityEmptyState(
            icon = Icons.Outlined.Groups,
            title = "还没有正在安排的事",
            supportingText = "先确定一件事，再邀请相关的人",
            modifier = modifier,
            testTag = "event-empty-list",
        )
    } else {
        SceneCapabilityEmptyState(
            icon = Icons.Outlined.Groups,
            title = "还没有正在安排的事",
            supportingText = "先确定一件事，再邀请相关的人",
            primaryLabel = "开始安排",
            onPrimary = onCreate,
            modifier = modifier,
            testTag = "event-empty-workbench",
        )
    }
}

@Composable
private fun EventPlanningMessage(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        message,
        modifier = modifier.fillMaxWidth().zhiBanCardSurface(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onDismiss).padding(ZhiBanSpacing.Lg),
        style = MaterialTheme.typography.bodyMedium,
    )
}

private data class EventPlanSection(val title: String, val items: List<EventPlanUi>)

private fun eventPlanSections(state: EventPlanningState): List<EventPlanSection> = listOf(
    EventPlanSection("正在安排", state.activePlans),
    EventPlanSection("已经确定", state.upcomingPlans),
    EventPlanSection("已经结束", state.plans.filter { it.plan.status == EventPlanStatus.COMPLETED }),
)

internal fun eventInvitePrompt(item: EventPlanUi): String {
    val people = item.participants.joinToString("、") { it.contact.displayName }
    val location = item.plan.location?.let { "，地点是$it" }.orEmpty()
    return "帮我为“${item.plan.title}”准备邀请。时间是${eventDateLabel(item.plan.proposedStartAtEpochMs)}$location，参与人是$people。" +
        "请结合联系人关系分别给出简洁自然的消息；不要假装已经发送，发送前必须让我确认。"
}

internal fun eventProgressLabel(item: EventPlanUi): String = when {
    item.participants.isEmpty() -> "还未选择参与人"
    item.pendingReplies > 0 -> "${item.participants.size} 人 · ${item.pendingReplies} 人待回复"
    else -> "${item.participants.size} 人 · 回复已整理"
}

internal fun eventDateLabel(epochMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(epochMs).atZone(zoneId).format(EVENT_DATE_FORMAT)

private fun responseLabel(status: String): String = when (status) {
    EventResponseStatus.GOING -> "会参加"
    EventResponseStatus.MAYBE -> "待确定"
    EventResponseStatus.DECLINED -> "不能参加"
    else -> "等待回复"
}

private val EVENT_DATE_FORMAT = DateTimeFormatter.ofPattern("M月d日 E HH:mm")

private data class EventPlanningDetailDialogSlots(
    val contactPickerOpen: Boolean,
    val setContactPickerOpen: (Boolean) -> Unit,
    val responseContact: ContactEntity?,
    val setResponseContact: (ContactEntity?) -> Unit,
    val deleteConfirmOpen: Boolean,
    val setDeleteConfirmOpen: (Boolean) -> Unit,
    val item: EventPlanUi?,
    val state: EventPlanningState,
    val planId: String,
    val viewModel: EventPlanningViewModel,
    val onBack: () -> Unit,
)

@Composable
private fun EventPlanningDetailDialogs(slots: EventPlanningDetailDialogSlots) {
    if (slots.contactPickerOpen && slots.item != null) {
        EventContactPicker(
            contacts = slots.state.contacts.filterNot { contact -> slots.item!!.participants.any { it.contact.contactId == contact.contactId } },
            onDismiss = { slots.setContactPickerOpen(false) },
            onSelect = { contact ->
                slots.viewModel.addParticipant(slots.planId, contact.contactId)
                slots.setContactPickerOpen(false)
            },
        )
    }
    slots.responseContact?.let { contact ->
        ResponseStatusDialog(
            contactName = contact.displayName,
            onDismiss = { slots.setResponseContact(null) },
            onSelect = { status ->
                slots.viewModel.updateResponse(slots.planId, contact.contactId, status)
                slots.setResponseContact(null)
            },
        )
    }
    if (slots.deleteConfirmOpen && slots.item != null) {
        ZhiBanAlertDialog(
            onDismissRequest = { slots.setDeleteConfirmOpen(false) },
            title = { Text("删除这项安排？") },
            text = {
                Text(
                    if (slots.item!!.plan.status == EventPlanStatus.CONFIRMED) {
                        "“${slots.item!!.plan.title}”将被删除，已加入日历的对应日程也会一并移除。"
                    } else {
                        "“${slots.item!!.plan.title}”及其参与人将被删除。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    slots.setDeleteConfirmOpen(false)
                    slots.viewModel.deletePlan(slots.item!!) { slots.onBack() }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { slots.setDeleteConfirmOpen(false) }) { Text("保留") } },
        )
    }
}

