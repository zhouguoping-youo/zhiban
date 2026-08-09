package com.zhiban.rebuild.ui.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhiban.agent.skills.BuiltInSkills
import com.zhiban.agent.skills.SkillLevel
import com.zhiban.rebuild.ui.components.ZhiBanLeadingIcon
import com.zhiban.rebuild.ui.components.ZhiBanPage
import com.zhiban.rebuild.ui.components.ZhiBanPrimaryTabHeader
import com.zhiban.rebuild.ui.components.ZhiBanTabBottomSpacer
import com.zhiban.rebuild.ui.components.ZhiBanTabHorizontalPadding
import com.zhiban.rebuild.ui.components.ZhiBanTabTopPadding
import com.zhiban.rebuild.ui.components.zhiBanCardSurface
import com.zhiban.rebuild.ui.theme.ZhiBanIconSize
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing
import com.zhiban.rebuild.ui.theme.ZhiBanTerracotta

@Composable
fun SkillTab(modifier: Modifier = Modifier, onOpenCrm: () -> Unit = {}, isDataEmpty: Boolean = false) {
    if (isDataEmpty) {
        MainTabEmptyPage("skill", modifier)
        return
    }
    val salesCrm = BuiltInSkills.all.first {
        it.id == "sales_crm" && it.level == SkillLevel.SCENE
    }
    val sceneCapabilities = listOf(
        SceneCapability(
            id = salesCrm.id,
            title = salesCrm.displayName,
            description = "联系人、机会与跟进",
            onClick = onOpenCrm,
        ),
    )

    ZhiBanPage(modifier = modifier) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = ZhiBanTabHorizontalPadding,
                top = ZhiBanTabTopPadding,
                end = ZhiBanTabHorizontalPadding,
                bottom = ZhiBanTabBottomSpacer,
            ),
            horizontalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    ZhiBanPrimaryTabHeader(
                        title = "能力",
                        subtitle = "按场景使用知伴",
                    )
                    Spacer(Modifier.height(ZhiBanSpacing.Lg))
                }
            }
            items(
                items = sceneCapabilities,
                key = { it.id },
                span = { if (sceneCapabilities.size == 1) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
            ) { capability ->
                SceneCapabilityCard(capability)
            }
        }
    }
}

private data class SceneCapability(val id: String, val title: String, val description: String, val onClick: () -> Unit)

@Composable
private fun SceneCapabilityCard(capability: SceneCapability) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 168.dp)
            .zhiBanCardSurface()
            .clickable(onClick = capability.onClick)
            .semantics {
                contentDescription = "进入${capability.title}"
            }
            .padding(ZhiBanSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZhiBanLeadingIcon(Icons.Outlined.WorkOutline)
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                tint = ZhiBanTerracotta,
                modifier = Modifier.size(ZhiBanIconSize.Inline),
            )
        }
        Text(
            capability.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            capability.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
    }
}
