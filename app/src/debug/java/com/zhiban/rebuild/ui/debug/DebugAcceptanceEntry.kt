package com.zhiban.rebuild.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.zhiban.rebuild.navigation.AgentVisualPrototype
import com.zhiban.rebuild.navigation.AssistantChat
import com.zhiban.rebuild.navigation.DebugAcceptance
import com.zhiban.rebuild.ui.components.ZhiBanGlassCard
import com.zhiban.rebuild.ui.components.ZhiBanPage
import com.zhiban.rebuild.ui.theme.ZhiBanTerracotta
import com.zhiban.rebuild.ui.theme.ZhiBanTextPrimary
import com.zhiban.rebuild.ui.theme.ZhiBanTextSecondary

@Composable
fun DebugAcceptanceEntry(onClick: () -> Unit) {
    ZhiBanGlassCard(
        modifier = Modifier.fillMaxWidth().clickable {
            onClick()
        },
        cornerRadius = 20.dp,
        elevation = 4.dp,
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                "模拟器验收入口",
                style = MaterialTheme.typography.titleMedium,
                color = ZhiBanTextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text("仅 Debug 可见 · 切换 Chat 模拟状态", style = MaterialTheme.typography.bodySmall, color = ZhiBanTextSecondary)
        }
    }
}

fun NavGraphBuilder.debugAcceptanceRoute(navController: NavHostController) {
    composable<DebugAcceptance> {
        DebugAcceptancePage(onOpenChat = { script ->
            navController.navigate(AssistantChat(draft = script))
        }, onOpenAgentPrototype = { state -> navController.navigate(AgentVisualPrototype(state)) })
    }
    composable<AgentVisualPrototype> { entry ->
        val route = entry.toRoute<AgentVisualPrototype>()
        AgentVisualPrototypePage(state = route.state, onBack = { navController.popBackStack() })
    }
}

@Composable
private fun DebugAcceptancePage(onOpenChat: (String) -> Unit, onOpenAgentPrototype: (String) -> Unit) {
    ZhiBanPage {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp)) {
            Text(
                "模拟器验收",
                style = MaterialTheme.typography.headlineMedium,
                color = ZhiBanTextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "选择状态后打开 Chat。此入口和路由不会进入 Release。",
                style = MaterialTheme.typography.bodyMedium,
                color = ZhiBanTextSecondary,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "问问视觉原型",
                style = MaterialTheme.typography.titleMedium,
                color = ZhiBanTextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "empty" to "空态",
                    "confirm" to "确认",
                    "executing" to "执行",
                    "success" to "成功",
                    "error" to "错误",
                ).forEach { (state, label) ->
                    Text(
                        label,
                        modifier = Modifier.background(
                            ZhiBanTerracotta.copy(alpha = .12f),
                            RoundedCornerShape(16.dp),
                        ).clickable {
                            onOpenAgentPrototype(state)
                        }.padding(horizontal = 10.dp, vertical = 8.dp),
                        color = ZhiBanTerracotta,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "多模态逐态验收",
                style = MaterialTheme.typography.titleMedium,
                color = ZhiBanTextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "复用生产组件；不触发外网、Gateway 或 API Key。",
                style = MaterialTheme.typography.bodySmall,
                color = ZhiBanTextSecondary,
            )
            listOf(
                "permission_unknown" to "权限未知", "permission_requestable" to "可请求", "permission_denied" to "已拒绝",
                "permission_permanent" to "永久拒绝", "permission_granted" to "已授权",
                "capability_probing" to "能力探测", "capability_verified" to "能力可用", "capability_expired" to "能力过期",
                "capability_failed" to "能力失败",
                "attachment_selected" to "已选择", "attachment_preflighting" to "预检", "attachment_ready" to "待上传",
                "attachment_uploading" to "上传中", "attachment_finalizing" to "完成中", "attachment_completed" to "已完成",
                "attachment_failed" to "失败/413", "attachment_cancelling" to "取消中", "attachment_cancelled" to "已取消",
                "attachment_uri_expired" to "URI 失效",
                "transcription_partial" to "转写 partial", "transcription_final" to "转写 final",
            ).chunked(3).forEach { row ->
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (state, label) ->
                        Text(
                            label,
                            modifier = Modifier.weight(
                                1f,
                            ).background(ZhiBanTerracotta.copy(alpha = .10f), RoundedCornerShape(12.dp)).clickable {
                                onOpenAgentPrototype("mm_$state")
                            }.padding(8.dp),
                            color = ZhiBanTerracotta,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(28.dp))
            listOf(
                Triple("加载中", "loading", "展示发送中与防重复提交"),
                Triple("成功", "hello", "展示本地 Mock 完整回复"),
                Triple("错误", "error", "展示可理解错误与重试入口"),
            ).forEach { (label, script, description) ->
                Row(
                    modifier = Modifier.fillMaxWidth().background(
                        Color.White.copy(alpha = 0.72f),
                        RoundedCornerShape(18.dp),
                    ).clickable {
                        onOpenChat(script)
                    }.padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            label,
                            style = MaterialTheme.typography.titleMedium,
                            color = ZhiBanTextPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(description, style = MaterialTheme.typography.bodySmall, color = ZhiBanTextSecondary)
                    }
                    Text(
                        "打开",
                        color = if (script ==
                            "error"
                        ) {
                            ZhiBanTerracotta
                        } else {
                            ZhiBanTerracotta
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(14.dp))
            }
        }
    }
}
