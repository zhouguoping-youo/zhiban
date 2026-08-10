package com.zhiban.rebuild.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Mic
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhiban.rebuild.navigation.MainTabContract
import com.zhiban.rebuild.ui.components.ZhiBanGlassCard
import com.zhiban.rebuild.ui.components.ZhiBanPage
import com.zhiban.rebuild.ui.theme.Gray100
import com.zhiban.rebuild.ui.theme.Gray500
import com.zhiban.rebuild.ui.theme.SuccessText
import com.zhiban.rebuild.ui.theme.ZhiBanCard
import com.zhiban.rebuild.ui.theme.ZhiBanIconContainer
import com.zhiban.rebuild.ui.theme.ZhiBanIconSize
import com.zhiban.rebuild.ui.theme.ZhiBanTerracotta
import com.zhiban.rebuild.ui.theme.ZhiBanTextPrimary
import com.zhiban.rebuild.ui.theme.ZhiBanTextSecondary
import java.util.Calendar as JavaCalendar

@Composable
fun HomeTab(modifier: Modifier = Modifier, onOpenAssistantChat: (String) -> Unit = {}) {
    var inputText by remember { mutableStateOf("") }
    val tab = MainTabContract.requireTab("home")
    val greeting = remember {
        when (JavaCalendar.getInstance().get(JavaCalendar.HOUR_OF_DAY)) {
            in 0..5 -> "夜深了"
            in 6..8 -> "早上好"
            in 9..11 -> "上午好"
            in 12..13 -> "中午好"
            in 14..17 -> "下午好"
            else -> "晚上好"
        }
    }

    ZhiBanPage(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier.size(
                        ZhiBanIconContainer.TouchTarget,
                    ).clip(
                        CircleShape,
                    ).background(ZhiBanCard).border(1.dp, ZhiBanTerracotta.copy(alpha = 0.16f), CircleShape).clickable {
                        onOpenAssistantChat("")
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.ChatBubbleOutline,
                        contentDescription = "打开问问",
                        tint = ZhiBanTerracotta,
                        modifier = Modifier.size(ZhiBanIconSize.Action),
                    )
                }
            }
            Spacer(modifier = Modifier.height(68.dp))
            Text(
                "$greeting，知伴用户",
                style = MaterialTheme.typography.headlineLarge,
                color = ZhiBanTextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(tab.pageTitle, style = MaterialTheme.typography.bodyLarge, color = ZhiBanTextSecondary)
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                tab.emptyTitle,
                style = MaterialTheme.typography.titleMedium,
                color = ZhiBanTextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(tab.emptyDescription, style = MaterialTheme.typography.bodySmall, color = Gray500)
            Spacer(modifier = Modifier.height(50.dp))

            // Input card — tapping anywhere on the card transitions to the
            // conversation page (AssistantChat) so the BottomNavBar hides
            // per v3.1 spec §3.1. The current draft is carried over.
            ZhiBanGlassCard(
                modifier = Modifier.fillMaxWidth().clickable {
                    onOpenAssistantChat(inputText.trim())
                },
                cornerRadius = 30.dp,
                containerColor = ZhiBanCard,
                elevation = 16.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().height(190.dp).padding(horizontal = 24.dp, vertical = 22.dp),
                ) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        if (inputText.isEmpty()) {
                            Text(
                                "问问知伴...",
                                style = MaterialTheme.typography.titleMedium,
                                color = Gray500,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        BasicTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            textStyle = MaterialTheme.typography.titleMedium.copy(color = ZhiBanTextPrimary),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(ZhiBanTerracotta),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Send,
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = {
                                if (inputText.isNotBlank()) onOpenAssistantChat(inputText.trim())
                            }),
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { inner ->
                                if (inputText.isEmpty()) {
                                    Text(
                                        "问问知伴...",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Gray500,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                inner()
                            },
                        )
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Gray100))
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(
                                ZhiBanIconContainer.TouchTarget,
                            ).clip(CircleShape).background(Gray100),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.Add,
                                contentDescription = "添加",
                                tint = ZhiBanTextSecondary,
                                modifier = Modifier.size(ZhiBanIconSize.Action),
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Box(
                            modifier = Modifier.clip(
                                RoundedCornerShape(999.dp),
                            ).background(
                                ZhiBanTerracotta.copy(alpha = 0.10f),
                            ).padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(SuccessText))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "连接大模型",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ZhiBanTerracotta,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Box(
                            modifier = Modifier.size(
                                ZhiBanIconContainer.Emphasized,
                            ).clip(CircleShape).background(ZhiBanTerracotta).clickable {
                                if (inputText.isNotBlank()) onOpenAssistantChat(inputText.trim())
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.Mic,
                                contentDescription = "发送",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(ZhiBanIconSize.Action),
                            )
                        }
                    }
                }
            }
        }
    }
}
