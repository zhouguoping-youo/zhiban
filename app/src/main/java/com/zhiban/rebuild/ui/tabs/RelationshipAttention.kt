package com.zhiban.rebuild.ui.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhiban.rebuild.ui.components.zhiBanCardSurface
import com.zhiban.rebuild.ui.theme.ZhiBanIconSize
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing

internal enum class RelationshipAttentionKind { CALL_NOTE, REPLY }

internal data class RelationshipAttentionItem(val kind: RelationshipAttentionKind, val title: String, val detail: String)

/**
 * Attention rows list only concrete, actionable items. Contact maintenance is
 * intentionally absent: it lives as a permanent header entry with a count
 * badge instead of occupying a recurring card here.
 */
internal fun buildRelationshipAttentionItems(
    pendingCallContactNames: List<String>,
    replySuggestions: List<ReplySuggestionCardModel>,
): List<RelationshipAttentionItem> = buildList {
    pendingCallContactNames.firstOrNull()?.let { name ->
        add(
            RelationshipAttentionItem(
                kind = RelationshipAttentionKind.CALL_NOTE,
                title = if (name.isBlank()) "补充刚才的通话要点" else "$name · 补充刚才的通话要点",
                detail = "记录结论和下一步",
            ),
        )
    }
    replySuggestions.firstOrNull()?.let { suggestion ->
        add(
            RelationshipAttentionItem(
                kind = RelationshipAttentionKind.REPLY,
                title = "${suggestion.contactName} · 有一条消息待回复",
                detail = suggestion.incomingExcerpt.ifBlank { "知伴已准备回复建议" },
            ),
        )
    }
}.take(3)

@Composable
internal fun RelationshipAttentionSection(items: List<RelationshipAttentionItem>, onClick: (RelationshipAttentionItem) -> Unit) {
    if (items.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        Text(
            "现在值得关注",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = RelationInk,
            modifier = Modifier.padding(bottom = ZhiBanSpacing.Sm),
        )
        Column(Modifier.fillMaxWidth().zhiBanCardSurface(RelationSurface)) {
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = ZhiBanSpacing.Lg),
                        color = RelationLine,
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onClick(item) }
                        .padding(horizontal = ZhiBanSpacing.Lg, vertical = ZhiBanSpacing.Md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = RelationInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            item.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = RelationMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(ZhiBanSpacing.Sm))
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = RelationMuted,
                        modifier = Modifier.size(ZhiBanIconSize.Inline),
                    )
                }
            }
        }
    }
}
