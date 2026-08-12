package com.zhiban.rebuild.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BusinessCenter
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.zhiban.rebuild.data.contact.PersonEmploymentEpisodeEntity
import com.zhiban.rebuild.ui.components.ZhiBanAlertDialog
import com.zhiban.rebuild.ui.theme.ZhiBanIconSize
import com.zhiban.rebuild.ui.theme.ZhiBanRadius
import com.zhiban.rebuild.ui.theme.ZhiBanSize
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing

@Composable
internal fun OwnerEmploymentAnchor(current: PersonEmploymentEpisodeEntity?, pastCount: Int, onEdit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(ZhiBanRadius.Card))
            .background(RelationSoft)
            .padding(horizontal = ZhiBanSpacing.Md, vertical = ZhiBanSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(ZhiBanSize.TouchTarget).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.BusinessCenter,
                contentDescription = null,
                tint = RelationAccent,
                modifier = Modifier.size(ZhiBanIconSize.Leading),
            )
        }
        Spacer(Modifier.width(ZhiBanSpacing.Sm))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Xs)) {
            Text(
                if (current == null) "还不知道你现在在哪工作" else current.companyNameSnapshot,
                color = RelationInk,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    current == null -> "补充后，知伴才能区分现同事和前同事"
                    !current.title.isNullOrBlank() && pastCount > 0 -> "${current.title} · 另有 $pastCount 段过往经历"
                    !current.title.isNullOrBlank() -> current.title.orEmpty()
                    pastCount > 0 -> "当前工作 · 另有 $pastCount 段过往经历"
                    else -> "当前工作"
                },
                color = RelationMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onEdit, modifier = Modifier.testTag("owner-employment-edit")) {
            Text(if (current == null) "补充" else "修改", color = RelationAccent)
        }
    }
}

@Composable
internal fun OwnerEmploymentEditorDialog(
    current: PersonEmploymentEpisodeEntity?,
    onDismiss: () -> Unit,
    onSave: (company: String, title: String?, result: (String?) -> Unit) -> Unit,
) {
    var company by remember(current?.episodeId) { mutableStateOf(current?.companyNameSnapshot.orEmpty()) }
    var title by remember(current?.episodeId) { mutableStateOf(current?.title.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    ZhiBanAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("我的当前工作") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md)) {
                Text(
                    "填写公司全称，用于区分现同事与前同事。",
                    color = RelationMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = company,
                    onValueChange = {
                        company = it.take(120)
                        error = null
                    },
                    label = { Text("当前公司全称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("owner-employment-company"),
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(80) },
                    label = { Text("职位（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("owner-employment-title"),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = company.isNotBlank() && !saving,
                onClick = {
                    saving = true
                    onSave(company, title.takeIf(String::isNotBlank)) { failure ->
                        saving = false
                        error = failure
                    }
                },
                modifier = Modifier.testTag("owner-employment-save"),
            ) { Text(if (saving) "保存中" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        containerColor = RelationSurface,
    )
}
