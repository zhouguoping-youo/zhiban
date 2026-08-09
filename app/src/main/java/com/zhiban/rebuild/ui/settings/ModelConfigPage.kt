package com.zhiban.rebuild.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiban.rebuild.ui.chat.PreferencesManager
import com.zhiban.rebuild.ui.components.ZhiBanAlertDialog
import com.zhiban.rebuild.ui.components.ZhiBanGlassCard
import com.zhiban.rebuild.ui.components.ZhiBanPage
import com.zhiban.rebuild.ui.components.ZhiBanSaveButton
import com.zhiban.rebuild.ui.components.ZhiBanSaveState
import com.zhiban.rebuild.ui.components.ZhiBanTabBottomSpacer
import com.zhiban.rebuild.ui.components.ZhiBanTopBar
import com.zhiban.rebuild.ui.theme.CloudBlue
import com.zhiban.rebuild.ui.theme.ErrorRed
import com.zhiban.rebuild.ui.theme.Gray500
import com.zhiban.rebuild.ui.theme.SuccessGreen
import com.zhiban.rebuild.ui.theme.SuccessSurface
import com.zhiban.rebuild.ui.theme.SuccessText
import com.zhiban.rebuild.ui.theme.ZhiBanCard
import com.zhiban.rebuild.ui.theme.ZhiBanIconContainer
import com.zhiban.rebuild.ui.theme.ZhiBanIconSize
import com.zhiban.rebuild.ui.theme.ZhiBanRadius
import com.zhiban.rebuild.ui.theme.ZhiBanSize
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing
import com.zhiban.rebuild.ui.theme.ZhiBanTerracotta
import com.zhiban.rebuild.ui.theme.ZhiBanTerracottaSoft
import com.zhiban.rebuild.ui.theme.ZhiBanTextPrimary
import com.zhiban.rebuild.ui.theme.ZhiBanTextSecondary
import com.zhiban.rebuild.ui.theme.ZhiBanWarmBackground

private data class ModelOption(val id: String, val description: String, val recommended: Boolean)
private val ModelSettingsCanvas = ZhiBanWarmBackground
private val ModelSettingsSurface = ZhiBanCard
private val ModelSettingsPrimary = ZhiBanTextPrimary
private val ModelSettingsSecondary = ZhiBanTextSecondary
private val ModelSettingsIconSurface = ZhiBanTerracottaSoft

@Composable
fun ModelConfigPage(onBack: () -> Unit = {}, viewModel: ModelConfigViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    ZhiBanPage {
        Column(modifier = Modifier.fillMaxSize().background(ModelSettingsCanvas)) {
            ZhiBanTopBar(title = "大模型连接", onBack = onBack)

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = ZhiBanSpacing.PageHorizontal),
                contentPadding = PaddingValues(bottom = ZhiBanSpacing.PageBottom),
                verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.ContentGap),
            ) {
                item { Spacer(modifier = Modifier.height(2.dp)) }

                // ===== 错误提示 =====
                if (state.errorMessage != null) {
                    item { ErrorBanner(state.errorMessage!!) }
                }

                item {
                    ZhiBanGlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = ZhiBanRadius.Card,
                        containerColor = MaterialTheme.colorScheme.surface,
                        borderColor = Color.Transparent,
                        elevation = 0.dp,
                    ) {
                        Row(
                            modifier = Modifier.padding(ZhiBanSpacing.Lg),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier.size(
                                    ZhiBanIconContainer.Compact,
                                ).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Outlined.CloudQueue,
                                    contentDescription = null,
                                    tint = ZhiBanTerracotta,
                                    modifier = Modifier.size(ZhiBanIconSize.Leading),
                                )
                            }
                            Spacer(modifier = Modifier.width(ZhiBanSpacing.Md))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "阶跃星辰",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = ModelSettingsPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            StatusBadge(configured = state.isApiKeyConfigured)
                        }
                    }
                }

                // ===== API Key 卡片 =====
                item {
                    ZhiBanGlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = ZhiBanRadius.Card,
                        containerColor = MaterialTheme.colorScheme.surface,
                        borderColor = Color.Transparent,
                        elevation = 0.dp,
                    ) {
                        Column(modifier = Modifier.padding(ZhiBanSpacing.Lg)) {
                            Text(
                                "API Key",
                                style = MaterialTheme.typography.titleMedium,
                                color = ModelSettingsPrimary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = state.apiKey,
                                onValueChange = viewModel::onApiKeyChange,
                                placeholder = { Text("请输入阶跃星辰 API Key", color = ModelSettingsSecondary) },
                                visualTransformation = if (state.apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                ),
                                trailingIcon = {
                                    Box(
                                        modifier = Modifier.size(ZhiBanSize.TouchTarget).clip(CircleShape).clickable {
                                            viewModel.toggleApiKeyVisibility()
                                        },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = if (state.apiKeyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                            contentDescription = if (state.apiKeyVisible) "隐藏" else "显示",
                                            tint = Gray500,
                                            modifier = Modifier.size(ZhiBanIconSize.Field),
                                        )
                                    }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(ZhiBanRadius.Card),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                    cursorColor = MaterialTheme.colorScheme.primary,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (state.isApiKeyConfigured) {
                                    TextButton(
                                        onClick = viewModel::checkConnection,
                                        enabled = !state.isChecking,
                                        contentPadding = PaddingValues(horizontal = 0.dp),
                                    ) {
                                        Text(if (state.isChecking) "检测中…" else "检测连接", color = CloudBlue)
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    TextButton(
                                        onClick = { confirmClear = true },
                                        contentPadding = PaddingValues(horizontal = 0.dp),
                                    ) {
                                        Text("清除", color = ErrorRed)
                                    }
                                }
                                Spacer(modifier = Modifier.weight(1f))
                                Text("本机加密保存", color = Gray500, style = MaterialTheme.typography.labelSmall)
                            }
                            state.healthMessage?.let {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    it,
                                    color = if (it ==
                                        "连接正常"
                                    ) {
                                        SuccessGreen
                                    } else {
                                        ErrorRed
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                item {
                    ZhiBanSaveButton(
                        state = when {
                            state.isSaving -> ZhiBanSaveState.SAVING
                            state.savedTick -> ZhiBanSaveState.SAVED
                            else -> ZhiBanSaveState.IDLE
                        },
                        onClick = viewModel::save,
                        enabled = !state.isLoading &&
                            (state.apiKey.isNotBlank() || state.isApiKeyConfigured),
                        idleLabel = if (state.isApiKeyConfigured && state.apiKey.isBlank()) "保存" else "连接",
                    )
                }

                item { Spacer(modifier = Modifier.height(ZhiBanTabBottomSpacer)) }
            }
        }
    }
    if (confirmClear) {
        ZhiBanAlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除 API Key？") },
            text = { Text("清除后，文字、图片和实时语音将停止使用，直到重新连接。") },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        viewModel.clearApiKey()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("清除")
                }
            },
            shape = RoundedCornerShape(ZhiBanRadius.Dialog),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
        )
    }
}

@Composable
private fun StatusBadge(configured: Boolean) {
    val (text, color, background) = if (configured) {
        Triple(
            "已连接",
            SuccessText,
            SuccessSurface,
        )
    } else {
        Triple("未配置", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HeroBlock(providerName: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CloudBlue.copy(alpha = 0.10f))
            .border(1.dp, CloudBlue.copy(alpha = 0.20f), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(CloudBlue.copy(alpha = 0.20f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.CloudQueue,
                contentDescription = null,
                tint = CloudBlue,
                modifier = Modifier.size(ZhiBanIconSize.Leading),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                "$providerName · 官方兼容协议",
                style = MaterialTheme.typography.titleSmall,
                color = ZhiBanTextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text("仅连接内置官方地址，API Key 只保存在本机", style = MaterialTheme.typography.bodySmall, color = ZhiBanTextSecondary)
        }
    }
}

@Composable
private fun SectionLabel(title: String, subtitle: String) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = ZhiBanTextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ZhiBanTextSecondary)
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ErrorRed.copy(alpha = 0.10f))
            .border(1.dp, ErrorRed.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⚠", color = ErrorRed, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.width(8.dp))
        Text(message, color = ErrorRed, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ModelRow(option: ModelOption, selected: Boolean, onClick: () -> Unit) {
    val borderColor = if (selected) ZhiBanTerracotta else Color.White.copy(alpha = 0.86f)
    val bgColor = if (selected) ZhiBanTerracottaSoft else ZhiBanCard.copy(alpha = 0.6f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(if (selected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // radio dot
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(if (selected) ZhiBanTerracotta else Color.Transparent)
                .border(1.5.dp, if (selected) ZhiBanTerracotta else Gray500, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(Color.White))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    option.id,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZhiBanTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                if (option.recommended) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(ZhiBanTerracotta)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text("推荐", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Text(option.description, style = MaterialTheme.typography.bodySmall, color = ZhiBanTextSecondary)
        }
    }
}
