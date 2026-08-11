package com.zhiban.rebuild.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.zhiban.rebuild.relationship.RelationshipGroup
import com.zhiban.rebuild.relationship.RelationshipTaxonomy
import com.zhiban.rebuild.ui.theme.ZhiBanRadius
import com.zhiban.rebuild.ui.theme.ZhiBanSize

@Composable
internal fun RelationshipTypePicker(selectedType: String, enabled: Boolean = true, onSelect: (String) -> Unit) {
    val initialGroup = RelationshipTaxonomy.find(selectedType)?.group ?: RelationshipGroup.WORK
    var selectedGroup by remember(selectedType) { mutableStateOf(initialGroup) }
    val groups = RelationshipGroup.entries
    val definitions = remember(selectedGroup) { RelationshipTaxonomy.definitionsFor(selectedGroup) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        groups.chunked(GROUP_COLUMNS).forEach { rowGroups ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rowGroups.forEach { group ->
                    RelationshipChoice(
                        label = group.displayName,
                        selected = selectedGroup == group,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        selectedGroup = group
                    }
                }
                repeat(GROUP_COLUMNS - rowGroups.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(2.dp))
        definitions.chunked(TYPE_COLUMNS).forEach { rowDefinitions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rowDefinitions.forEach { definition ->
                    RelationshipChoice(
                        label = definition.displayName,
                        selected = selectedType == definition.code,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        onSelect(definition.code)
                    }
                }
                repeat(TYPE_COLUMNS - rowDefinitions.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun RelationshipChoice(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(ZhiBanSize.Control)
            .clip(RoundedCornerShape(ZhiBanRadius.Medium))
            .background(if (selected) RelationAccent else RelationSoft)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else RelationMuted,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun relationLabel(type: String, isHistorical: Boolean = false): String = RelationshipTaxonomy.displayName(type, isHistorical)

/** Compact labels keep the relationship readable between two nearby nodes. */
internal fun graphRelationLabel(type: String, isHistorical: Boolean = false): String {
    if (isHistorical) return relationLabel(type, isHistorical = true)
    return when (type) {
        "SUPPLIER" -> "供应"
        "PROJECT_PARTNER" -> "项目"
        "BUSINESS_PARTNER" -> "合作"
        else -> relationLabel(type)
    }
}

private const val GROUP_COLUMNS = 4
private const val TYPE_COLUMNS = 3
