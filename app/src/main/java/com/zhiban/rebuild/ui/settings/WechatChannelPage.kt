package com.zhiban.rebuild.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zhiban.rebuild.data.ilink.ContextTokenCache
import com.zhiban.rebuild.data.ilink.IlinkBindUiState
import com.zhiban.rebuild.data.ilink.IlinkBotBinding
import com.zhiban.rebuild.data.ilink.IlinkBotBindingController
import com.zhiban.rebuild.data.ilink.IlinkBotCredentialStore
import com.zhiban.rebuild.data.ilink.IlinkCursorStore
import com.zhiban.rebuild.data.ilink.IlinkFetchCoordinator
import com.zhiban.rebuild.runtime.governance.OutboundDataPreferences
import com.zhiban.rebuild.ui.components.ZhiBanToggleRow
import com.zhiban.rebuild.ui.theme.ZhiBanRadius
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing
import com.zhiban.rebuild.ui.theme.ZhiBanTextSecondary
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class WechatChannelState(
    /** Master consent switch (R18): when off, every iLink call is blocked at the outbound gate. */
    val enabled: Boolean = false,
    /** Non-sensitive binding metadata, or null when never bound. */
    val binding: IlinkBotBinding? = null,
    /** Non-null while a QR bind attempt is on screen (shows the QR / waiting / result card). */
    val bindUi: IlinkBindUiState? = null,
)

@HiltViewModel
internal class WechatChannelViewModel @Inject constructor(
    private val preferences: OutboundDataPreferences,
    private val credentialStore: IlinkBotCredentialStore,
    private val bindingController: IlinkBotBindingController,
    private val cursorStore: IlinkCursorStore,
    private val contextTokenCache: ContextTokenCache,
    private val fetchCoordinator: IlinkFetchCoordinator,
) : ViewModel() {
    private val _state = MutableStateFlow(WechatChannelState())
    val state = _state.asStateFlow()

    private var bindJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        _state.update {
            it.copy(
                enabled = preferences.snapshot().allowWechatIlink,
                binding = credentialStore.bindingInfo(),
            )
        }
    }

    fun setEnabled(enabled: Boolean) {
        preferences.setAllowWechatIlink(enabled)
        _state.update { it.copy(enabled = enabled) }
    }

    /** Start (or restart) the QR bind flow. Tapping bind is itself the consent gesture, so enable first. */
    fun startBind() {
        if (bindJob?.isActive == true) return
        setEnabled(true)
        bindJob = viewModelScope.launch {
            bindingController.bind().collect { ui ->
                when (ui) {
                    is IlinkBindUiState.Bound -> {
                        // Bound card now renders from `binding`; clear the transient QR flow.
                        _state.update { it.copy(binding = ui.binding, bindUi = null) }
                    }

                    else -> _state.update { it.copy(bindUi = ui) }
                }
            }
        }
    }

    fun cancelBind() {
        bindJob?.cancel()
        bindJob = null
        _state.update { it.copy(bindUi = null) }
    }

    /** Unbind: wipe the vault token, the cursor, the reply tokens, and turn consent off. */
    fun unbind() {
        cancelBind()
        viewModelScope.launch {
            credentialStore.clear()
            cursorStore.clear()
            contextTokenCache.clear()
            preferences.setAllowWechatIlink(false)
            _state.update { it.copy(enabled = false, binding = null, bindUi = null) }
        }
    }

    /**
     * Force an immediate inbound pull. Manual fallback for when no WeChat notification fires — most
     * commonly because WeChat is also logged in on a desktop, which mutes the phone's notifications.
     */
    fun syncNow() {
        fetchCoordinator.syncNow()
    }
}

@Composable
internal fun WechatChannelPage(onBack: () -> Unit, viewModel: WechatChannelViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsPageFrame("微信", onBack) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ZhiBanSpacing.PageHorizontal)
                .padding(bottom = ZhiBanSpacing.PageBottom),
        ) {
            Text(
                "绑定微信官方 iLink 机器人（Bot）后，知伴可接收和回复与该机器人的一对一对话：对方需先给机器人发消息，知伴才能收发；发送前都会经过你确认。",
                style = MaterialTheme.typography.bodySmall,
                color = ZhiBanTextSecondary,
                modifier = Modifier.padding(horizontal = ZhiBanSpacing.Xs, vertical = ZhiBanSpacing.Sm),
            )
            SettingsCard {
                ZhiBanToggleRow(
                    title = "微信机器人",
                    subtitle = if (state.enabled) "已开启 · 机器人对话可接入" else "关闭后机器人对话不再接入",
                    checked = state.enabled,
                    onCheckedChange = { viewModel.setEnabled(it) },
                    horizontalPadding = 0.dp,
                )
            }
            Spacer(Modifier.height(ZhiBanSpacing.Md))
            BindStatusCard(state, viewModel)
            state.bindUi?.let { bindUi ->
                Spacer(Modifier.height(ZhiBanSpacing.Md))
                BindFlowCard(bindUi, onCancel = viewModel::cancelBind, onRetry = viewModel::startBind)
            }
        }
    }
}

@Composable
private fun BindStatusCard(state: WechatChannelState, viewModel: WechatChannelViewModel) {
    SettingsCard {
        val binding = state.binding
        when {
            binding == null -> {
                WechatInfoRow("绑定状态", "未绑定")
                Spacer(Modifier.height(ZhiBanSpacing.Sm))
                // 点"绑定机器人"本身就是同意动作(startBind 会先打开同意开关),按钮无需因开关未开而禁用。
                Button(
                    onClick = viewModel::startBind,
                    enabled = state.bindUi == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("绑定机器人") }
            }

            binding.sessionExpired -> {
                WechatInfoRow("绑定状态", "会话已过期")
                WechatInfoRow("微信", binding.ilinkUserId)
                Spacer(Modifier.height(ZhiBanSpacing.Sm))
                Button(
                    onClick = viewModel::startBind,
                    enabled = state.enabled && state.bindUi == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("重新绑定") }
                TextButton(onClick = viewModel::unbind, modifier = Modifier.fillMaxWidth()) { Text("解绑") }
            }

            else -> {
                WechatInfoRow("绑定状态", "已绑定")
                WechatInfoRow("微信", binding.ilinkUserId)
                WechatInfoRow("绑定时间", formatEpochMs(binding.boundAtEpochMs))
                Spacer(Modifier.height(ZhiBanSpacing.Sm))
                Button(onClick = viewModel::syncNow, modifier = Modifier.fillMaxWidth()) { Text("立即同步消息") }
                Text(
                    "电脑登录微信时手机不弹通知，收不到可点上方手动拉取",
                    style = MaterialTheme.typography.bodySmall,
                    color = ZhiBanTextSecondary,
                    modifier = Modifier.padding(top = ZhiBanSpacing.Xs),
                )
                TextButton(onClick = viewModel::unbind, modifier = Modifier.fillMaxWidth()) { Text("解绑") }
            }
        }
    }
}

@Composable
private fun BindFlowCard(bindUi: IlinkBindUiState, onCancel: () -> Unit, onRetry: () -> Unit) {
    SettingsCard {
        when (bindUi) {
            is IlinkBindUiState.Bound -> Unit

            // handled via `binding`
            is IlinkBindUiState.Failed -> {
                WechatInfoRow("绑定失败", bindFailureMessage(bindUi.reasonCode))
                Spacer(Modifier.height(ZhiBanSpacing.Sm))
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("重试") }
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("取消") }
            }

            // ShowQrcode / WaitingScan / Scanned: the QR stays on screen for the entire scan, with
            // only the line beneath it changing as the handshake progresses.
            else -> {
                Text(
                    "用微信扫描下方二维码完成绑定",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(ZhiBanSpacing.Md))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    WechatQrImage(bindUi.qrcodeImgUrl)
                }
                Spacer(Modifier.height(ZhiBanSpacing.Sm))
                if (bindUi is IlinkBindUiState.Scanned) {
                    WechatWaitingRow("已扫码，请在手机上确认")
                } else {
                    Text(
                        "同一台手机：先截图，再到微信「扫一扫 → 相册」选这张截图即可",
                        style = MaterialTheme.typography.bodySmall,
                        color = ZhiBanTextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(ZhiBanSpacing.Sm))
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("取消") }
            }
        }
    }
}

@Composable
private fun WechatWaitingRow(text: String) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(vertical = ZhiBanSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.size(ZhiBanSpacing.Md))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun WechatInfoRow(title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = ZhiBanSpacing.Sm)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Renders the bind QR by encoding the server's `qrcode_img_content` payload locally. That payload is
 * a landing-page URL, not a displayable image (see [rememberQrcodeBitmap]), so it must be encoded
 * into a QR, not fetched. White background + no filtering keeps the modules crisp and scannable.
 */
@Composable
private fun WechatQrImage(content: String?) {
    val bitmap = rememberQrcodeBitmap(content, QR_CODE_SIZE_PX)
    if (bitmap == null) {
        Text(
            "二维码生成失败，请重试",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
    } else {
        Image(
            bitmap = bitmap,
            contentDescription = "微信绑定二维码",
            modifier = Modifier
                .size(220.dp)
                .background(Color.White, RoundedCornerShape(ZhiBanRadius.Card)),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None,
        )
    }
}

private fun bindFailureMessage(reasonCode: String): String = when (reasonCode) {
    "ILINK_BIND_TIMEOUT" -> "二维码超时，请重试"
    "WECHAT_ILINK_CONSENT_REQUIRED" -> "请先开启“微信机器人”"
    else -> "连接微信失败，请检查网络后重试"
}

private fun formatEpochMs(epochMs: Long): String = if (epochMs <= 0L) "—" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))

// The QR is generated at a fixed pixel size well above its on-screen dp size; FilterQuality.None
// then downscales without smoothing so the modules stay sharp enough for WeChat to scan.
private const val QR_CODE_SIZE_PX = 512
