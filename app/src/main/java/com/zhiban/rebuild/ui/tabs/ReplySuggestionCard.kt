package com.zhiban.rebuild.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhiban.rebuild.ui.theme.ZhiBanRadius

/** One reply-suggestion group (2–3 drafts for a single unanswered incoming message), resolved for display. */
data class ReplySuggestionCardModel(
    val candidateId: String,
    val contactId: String?,
    val platform: String,
    val contactName: String,
    val incomingExcerpt: String,
    val drafts: List<String>,
    val createdAtEpochMs: Long,
)

/**
 * The §7.2 reply card: contact + the message awaiting a reply + 2–3 AI drafts the user adopts, edits, or
 * dismisses. Forwarding only jumps to WeChat with the text prefilled — the user always picks the chat and
 * presses send themselves, so a draft never leaves the device without that final human act.
 */
@Composable
internal fun ReplySuggestionCard(
    model: ReplySuggestionCardModel,
    onForward: (ReplySuggestionCardModel, String) -> Unit,
    onDismiss: (ReplySuggestionCardModel) -> Unit,
    onOptOut: (ReplySuggestionCardModel) -> Unit,
) {
    var selectedIndex by remember(model.candidateId) { mutableStateOf<Int?>(null) }
    var editing by remember(model.candidateId) { mutableStateOf(false) }
    var editedText by remember(model.candidateId) { mutableStateOf("") }
    val forwardText = if (editing) editedText.trim() else selectedIndex?.let(model.drafts::getOrNull).orEmpty()
    val canForward = forwardText.isNotBlank()

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(ZhiBanRadius.Card)).background(RelationSoft).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                model.contactName,
                color = RelationInk,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                " · ${replyPlatformLabel(model.platform)} · ${replyRelativeTime(model.createdAtEpochMs)}",
                color = RelationMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (model.incomingExcerpt.isNotBlank()) {
            Text(
                "“${model.incomingExcerpt}”",
                color = RelationMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        model.drafts.forEachIndexed { index, draft ->
            val selected = !editing && selectedIndex == index
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(ZhiBanRadius.Card))
                    .background(if (selected) RelationSurface else RelationSurface.copy(alpha = 0.4f))
                    .clickable {
                        editing = false
                        selectedIndex = index
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = if (selected) "已采用" else "采用",
                    tint = if (selected) RelationAccent else RelationMuted,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(draft, color = RelationInk, style = MaterialTheme.typography.bodyMedium)
            }
        }
        TextButton(onClick = {
            if (!editing) {
                editedText = selectedIndex?.let(model.drafts::getOrNull).orEmpty()
                editing = true
            } else {
                editing = false
            }
        }) {
            Icon(Icons.Outlined.Edit, contentDescription = null, tint = RelationInk, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (editing) "收起改写" else "自己改写", color = RelationInk, style = MaterialTheme.typography.labelLarge)
        }
        if (editing) {
            OutlinedTextField(
                value = editedText,
                onValueChange = { editedText = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                placeholder = { Text("改写这条回复…") },
                maxLines = 4,
            )
        }
        Text(
            "转发后请在${replyPlatformLabel(model.platform)}里选择 ${model.contactName}，知伴不会替你发送",
            color = RelationMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onDismiss(model) }) { Text("忽略", color = RelationMuted) }
            if (model.contactId != null) {
                TextButton(onClick = { onOptOut(model) }) { Text("不再建议", color = RelationMuted) }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { onForward(model, forwardText) },
                enabled = canForward,
                shape = RoundedCornerShape(ZhiBanRadius.Card),
                colors = ButtonDefaults.buttonColors(containerColor = RelationInk),
            ) { Text("转发给 ${model.contactName}") }
        }
    }
}

internal fun replyPlatformLabel(platform: String): String = when (platform) {
    "WECHAT" -> "微信"
    "SMS" -> "短信"
    "QQ" -> "QQ"
    "TIM" -> "TIM"
    "FEISHU" -> "飞书"
    "LARK" -> "Lark"
    "WEWORK" -> "企业微信"
    "DINGTALK" -> "钉钉"
    else -> platform
}

internal fun replyRelativeTime(epochMs: Long): String {
    val elapsed = (System.currentTimeMillis() - epochMs).coerceAtLeast(0L)
    val minutes = elapsed / 60_000L
    val hours = elapsed / 3_600_000L
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        hours < 24 -> "$hours 小时前"
        else -> "${hours / 24} 天前"
    }
}
