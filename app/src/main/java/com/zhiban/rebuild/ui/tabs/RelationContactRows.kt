package com.zhiban.rebuild.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.data.interaction.ContactInteractionIntensity
import com.zhiban.rebuild.runtime.personalization.UserProfile
import com.zhiban.rebuild.runtime.personalization.hasCompleteRequiredIdentity
import com.zhiban.rebuild.runtime.personalization.isValidMainlandMobileNumber
import com.zhiban.rebuild.ui.components.ZhiBanCompactEmptyState
import com.zhiban.rebuild.ui.components.ZhiBanLeadingIcon
import com.zhiban.rebuild.ui.components.zhiBanCardSurface
import com.zhiban.rebuild.ui.theme.ZhiBanIconSize
import com.zhiban.rebuild.ui.theme.ZhiBanRadius

@Composable
internal fun OwnerProfilePrompt(profile: UserProfile, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ZhiBanRadius.Card))
            .background(RelationSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZhiBanLeadingIcon(Icons.Outlined.PersonOutline)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "完善我的资料",
                color = RelationInk,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                profile.missingRequiredIdentityHint(),
                color = RelationMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = "去完善个人资料",
            tint = RelationMuted,
            modifier = Modifier.size(ZhiBanIconSize.Leading),
        )
    }
}

internal fun shouldShowOwnerProfilePrompt(profile: UserProfile, selectedCategory: String, query: String): Boolean =
    !profile.hasCompleteRequiredIdentity() && selectedCategory == "全部" && query.isBlank()

private fun UserProfile.missingRequiredIdentityHint(): String {
    val missing = buildList {
        if (!phone.isValidMainlandMobileNumber()) add("手机号")
        if (wechatId.isBlank()) add("微信号")
    }
    return "补充${missing.joinToString("和")}，帮助知伴识别你"
}

@Composable
internal fun ContactRow(contact: ContactEntity, contextSummary: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .zhiBanCardSurface(RelationSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(RelationSoft), contentAlignment = Alignment.Center) {
            Text(
                contact.displayName.take(1),
                color = RelationInk,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                contact.displayName,
                color = RelationInk,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            val subtitle = contextSummary.orEmpty().ifBlank {
                listOfNotNull(contact.company, contact.title).take(2).joinToString(" · ")
            }
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = RelationMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        contact.tagsJson.firstKnownTag()?.let {
            Text(
                it,
                color = RelationMuted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.background(
                    RelationSoft,
                    RoundedCornerShape(ZhiBanRadius.Medium),
                ).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

internal fun contactContextSummary(
    contact: ContactEntity,
    relationships: List<RelationshipEdgeEntity>,
    interaction: ContactInteractionIntensity?,
    nowEpochMs: Long,
): String {
    val directRelationship = relationships.firstOrNull { edge ->
        (edge.fromContactId == RelationshipPersonIds.SELF && edge.toContactId == contact.contactId) ||
            (edge.toContactId == RelationshipPersonIds.SELF && edge.fromContactId == contact.contactId)
    }?.displayRelationLabel()
    val company = contact.company?.trim()?.takeIf(String::isNotBlank)
    val recency = interaction?.lastInteractionAtEpochMs?.let { relationshipInteractionRecency(it, nowEpochMs) }
    return listOfNotNull(directRelationship, company, recency).distinct().take(3).joinToString(" · ")
}

internal fun relationshipInteractionRecency(lastInteractionAtEpochMs: Long, nowEpochMs: Long): String? {
    if (lastInteractionAtEpochMs <= 0L || lastInteractionAtEpochMs > nowEpochMs) return null
    val days = (nowEpochMs - lastInteractionAtEpochMs) / 86_400_000L
    return when {
        days == 0L -> "今天联系过"
        days == 1L -> "昨天联系过"
        days < 7L -> "${days}天前联系"
        days < 30L -> "${days / 7L}周前联系"
        days < 365L -> "${days / 30L}个月前联系"
        else -> "${days / 365L}年前联系"
    }
}

@Composable
internal fun RelationEmpty(searching: Boolean, onImport: () -> Unit, onAdd: () -> Unit) {
    if (searching) {
        ZhiBanCompactEmptyState(
            title = "没有找到联系人",
            subtitle = "换个词或分类试试",
            icon = Icons.Rounded.Groups,
        )
    } else {
        ZhiBanCompactEmptyState(
            title = "还没有联系人",
            subtitle = "从通讯录选择重要的人",
            icon = Icons.Rounded.Groups,
            primaryLabel = "导入联系人",
            onPrimary = onImport,
            secondaryLabel = "手动添加",
            onSecondary = onAdd,
        )
    }
}
