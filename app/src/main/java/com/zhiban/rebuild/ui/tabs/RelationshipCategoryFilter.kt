package com.zhiban.rebuild.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhiban.rebuild.ui.theme.ZhiBanSize
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing

@Composable
internal fun RelationshipCategoryFilter(selected: String, options: List<String>, onSelected: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ZhiBanSize.TouchTarget)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(role = Role.Button) { expanded = true }
                .semantics(mergeDescendants = true) {
                    contentDescription = "关系分类"
                    stateDescription = "当前选择：$selected"
                    role = Role.Button
                }
                .testTag("relation-category-filter")
                .padding(horizontal = ZhiBanSpacing.Lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.FilterList,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "关系分类",
                modifier = Modifier.weight(1f).padding(start = ZhiBanSpacing.Md),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = selected,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (option == selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    },
                    trailingIcon = if (option == selected) {
                        {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = "已选择",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}
