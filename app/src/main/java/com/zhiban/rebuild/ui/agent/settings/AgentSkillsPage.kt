package com.zhiban.rebuild.ui.agent.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiban.agent.skills.BuiltInSkills
import com.zhiban.agent.skills.SkillSpec
import com.zhiban.rebuild.runtime.config.AgentControlStore
import com.zhiban.rebuild.ui.components.ZhiBanGlassCard
import com.zhiban.rebuild.ui.components.ZhiBanLeadingIcon
import com.zhiban.rebuild.ui.components.ZhiBanPage
import com.zhiban.rebuild.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 内置技能的一句话描述（SkillSpec 只带 planningInstruction，界面描述单独维护）。 */
internal fun skillTagline(id: String): String = when (id) {
    "calendar_coordination" -> "协调日程：先核对时间，创建修改前与你确认"
    "contact_relationship" -> "识别对话里的人，补全资料、梳理关系"
    "sales_crm" -> "以销售机会为主线，推进客户关系"
    "personal_life" -> "重要日期与承诺，提前做好准备"
    "social_planning" -> "协调参与人、时间与邀请"
    "memory_preference" -> "区分临时与长期偏好，记住你的习惯"
    else -> "内置技能"
}

internal fun skillIcon(id: String): ImageVector = when (id) {
    "calendar_coordination" -> Icons.Outlined.Event
    "contact_relationship" -> Icons.Outlined.Contacts
    "sales_crm" -> Icons.Outlined.AutoAwesome
    "personal_life" -> Icons.Outlined.FavoriteBorder
    "social_planning" -> Icons.Outlined.Groups
    "memory_preference" -> Icons.Outlined.FavoriteBorder
    else -> Icons.Outlined.AutoAwesome
}

data class AgentSkillsState(val skills: List<SkillSpec> = emptyList(), val enabled: Map<String, Boolean> = emptyMap())

@HiltViewModel
class AgentSkillsViewModel @Inject constructor(private val controls: AgentControlStore) : ViewModel() {
    private val _state = MutableStateFlow(snapshot())
    val state = _state.asStateFlow()

    private fun snapshot() = AgentSkillsState(
        BuiltInSkills.all,
        BuiltInSkills.all.map { it.id }.associateWith(controls::isSkillEnabled),
    )

    fun setEnabled(id: String, value: Boolean) {
        controls.saveSkillEnabled(id, value)
        _state.update { it.copy(enabled = it.enabled + (id to value)) }
    }
}

@Composable
fun AgentSkillsPage(onBack: () -> Unit, viewModel: AgentSkillsViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    ZhiBanPage {
        Column(Modifier.fillMaxSize()) {
            AgentHeader("技能", onBack)
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = ZhiBanSpacing.PageHorizontal),
                contentPadding = PaddingValues(bottom = ZhiBanSpacing.PageBottom),
                verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.ContentGap),
            ) {
                items(s.skills, key = { it.id }) { skill ->
                    SkillCard(
                        skill = skill,
                        enabled = s.enabled[skill.id] != false,
                        onToggle = { viewModel.setEnabled(skill.id, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SkillCard(skill: SkillSpec, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    ZhiBanGlassCard(Modifier.fillMaxWidth(), cornerRadius = ZhiBanRadius.Card) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = ZhiBanSize.ListRowWithSubtitle)
                .padding(horizontal = ZhiBanSpacing.Lg, vertical = ZhiBanSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZhiBanLeadingIcon(skillIcon(skill.id))
            Spacer(Modifier.width(ZhiBanSpacing.Md))
            Column(Modifier.weight(1f)) {
                Text(skill.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    skillTagline(skill.id),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.width(ZhiBanSpacing.Sm))
            Switch(enabled, onToggle)
        }
    }
}
