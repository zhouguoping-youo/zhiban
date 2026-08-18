package com.zhiban.rebuild.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.zhiban.rebuild.data.completion.ContactCompletionDraft
import com.zhiban.rebuild.ui.components.ZhiBanChip
import com.zhiban.rebuild.ui.components.ZhiBanDialogHeader
import com.zhiban.rebuild.ui.components.ZhiBanDialogHost
import com.zhiban.rebuild.ui.theme.ZhiBanRadius
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing

private val CompletionDialogMaxWidth = 560.dp
private val DraftFieldMinHeight = 96.dp

/**
 * §七 补全确认卡：联系人 + 这条消息要问的字段 chips + 可编辑草稿。确认只跳转微信预填——用户亲选联系人
 * 亲发，知伴绝不代发（草稿不会在没有这最后一步人工动作前离开设备）。取消/关闭即撤掉这条 DRAFTED 请求。
 * 全用 ZhiBan 封装与 Relation 主题色，不绕过共享视觉系统（verifyUiConsistency 门禁）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ContactCompletionCard(draft: ContactCompletionDraft, error: String?, onConfirm: (String) -> Unit, onCancel: () -> Unit) {
    var text by rememberSaveable(draft.requestId) { mutableStateOf(draft.draftText) }
    ZhiBanDialogHost(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier.fillMaxSize().systemBarsPadding().imePadding()
                .padding(horizontal = ZhiBanSpacing.Xl, vertical = ZhiBanSpacing.Lg),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .widthIn(max = CompletionDialogMaxWidth)
                    .clip(RoundedCornerShape(ZhiBanRadius.Dialog))
                    .background(RelationSurface)
                    .padding(ZhiBanSpacing.Xl),
                verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md),
            ) {
                ZhiBanDialogHeader(title = "向 ${draft.contactName} 补全资料", onDismiss = onCancel)
                Text("这条消息会问：", color = RelationMuted, style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Sm),
                    verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Sm),
                ) {
                    draft.fields.forEach { field ->
                        ZhiBanChip(text = field.label, selected = false, enabled = false, onClick = {})
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = DraftFieldMinHeight),
                    label = { Text("询问草稿") },
                    minLines = 3,
                    maxLines = 6,
                )
                Text(
                    "跳转微信后请手动选择 ${draft.contactName} 并发送，知伴不会替你发送",
                    color = RelationMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
                error?.let { Text(it, color = RelationDanger, style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onCancel) { Text("取消", color = RelationMuted) }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { onConfirm(text) },
                        enabled = text.isNotBlank(),
                        shape = RoundedCornerShape(ZhiBanRadius.Card),
                        colors = ButtonDefaults.buttonColors(containerColor = RelationAccent),
                    ) { Text("去微信发送") }
                }
            }
        }
    }
}
