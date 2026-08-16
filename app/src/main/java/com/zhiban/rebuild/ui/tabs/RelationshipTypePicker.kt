package com.zhiban.rebuild.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zhiban.rebuild.relationship.RelationshipGroup
import com.zhiban.rebuild.relationship.RelationshipTaxonomy
import com.zhiban.rebuild.ui.components.ZhiBanChip

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RelationshipTypePicker(selectedType: String, enabled: Boolean = true, onSelect: (String) -> Unit) {
    val initialGroup = RelationshipTaxonomy.find(selectedType)?.group
    var selectedGroup by remember { mutableStateOf(initialGroup) }
    LaunchedEffect(selectedType) {
        RelationshipTaxonomy.find(selectedType)?.group?.let { selectedGroup = it }
    }
    val groups = RelationshipGroup.entries
    val definitions = remember(selectedGroup) { selectedGroup?.let(RelationshipTaxonomy::definitionsFor).orEmpty() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Content-sized chips in a FlowRow: every group/type label renders in full and wraps to the
        // next line on narrow screens, instead of being ellipsised inside a fixed-width grid cell.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            groups.forEach { group ->
                RelationshipChoice(
                    label = group.displayName,
                    selected = selectedGroup == group,
                    enabled = enabled,
                ) {
                    if (selectedGroup != group && RelationshipTaxonomy.find(selectedType)?.group != group) {
                        onSelect("")
                    }
                    selectedGroup = group
                }
            }
        }
        if (selectedGroup == null) return@Column
        Spacer(Modifier.height(2.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            definitions.forEach { definition ->
                RelationshipChoice(
                    label = definition.displayName,
                    selected = selectedType == definition.code,
                    enabled = enabled,
                ) {
                    onSelect(definition.code)
                }
            }
        }
    }
}

@Composable
private fun RelationshipChoice(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ZhiBanChip(
        text = label,
        selected = selected,
        modifier = modifier,
        color = RelationAccent,
        enabled = enabled,
        onClick = onClick,
    )
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
