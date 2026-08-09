package com.zhiban.rebuild.ui.agent.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zhiban.rebuild.runtime.personalization.UserProfile
import com.zhiban.rebuild.runtime.personalization.UserProfileStore
import com.zhiban.rebuild.ui.components.ZhiBanPage
import com.zhiban.rebuild.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExtraAccount(val platform: String, val handle: String)

data class UserProfileUiState(
    val name: String = "",
    val preferredName: String = "",
    val phone: String = "",
    val wechatId: String = "",
    val douyinId: String = "",
    val avatarUri: String? = null,
    val avatarBytes: ByteArray? = null,
    val extraAccounts: List<ExtraAccount> = emptyList(),
    val occupations: Set<String> = emptySet(),
    val customInstructions: String = "",
    val saved: Boolean = false,
    val validationError: String? = null,
)

internal val OCCUPATION_OPTIONS = listOf(
    "销售/商务", "管理", "技术", "设计", "运营", "市场",
    "教育", "医疗", "金融", "自由职业", "学生", "其他",
)

internal val EXTRA_ACCOUNT_PLATFORMS = listOf("飞书", "企微", "钉钉", "QQ")

internal fun platformKey(platform: String): String = when (platform) {
    "飞书" -> "feishu"
    "企微" -> "wecom"
    "钉钉" -> "dingtalk"
    "QQ" -> "qq"
    else -> platform.lowercase()
}

internal fun platformLabel(key: String): String = when (key) {
    "feishu" -> "飞书"
    "wecom" -> "企微"
    "dingtalk" -> "钉钉"
    "qq" -> "QQ"
    else -> key
}

internal fun isValidPhone(phone: String): Boolean = phone.length == 11 && phone.startsWith("1") && phone.all(Char::isDigit)

@HiltViewModel class UserProfileViewModel @Inject constructor(private val store: UserProfileStore) : ViewModel() {
    private val current = store.profile.value
    private val _state = MutableStateFlow(
        UserProfileUiState(
            name = current.name,
            preferredName = current.preferredName,
            phone = current.phone,
            wechatId = current.wechatId,
            douyinId = current.douyinId,
            avatarUri = current.avatarUri,
            extraAccounts = buildExtraAccounts(current),
            occupations = current.occupations,
            customInstructions = current.customInstructions,
        ),
    )
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val bytes = store.readAvatarBytes(current.avatarUri)
            _state.update { it.copy(avatarUri = store.profile.value.avatarUri, avatarBytes = bytes) }
        }
    }

    fun name(v: String) = _state.update { it.copy(name = v.take(60), saved = false) }
    fun preferredName(v: String) = _state.update { it.copy(preferredName = v.take(40), saved = false) }
    fun phone(v: String) = _state.update { it.copy(phone = v.filter(Char::isDigit).take(11), saved = false) }
    fun wechat(v: String) = _state.update { it.copy(wechatId = v.take(60), saved = false) }
    fun douyin(v: String) = _state.update { it.copy(douyinId = v.take(60), saved = false) }
    fun customInstructions(v: String) = _state.update { it.copy(customInstructions = v.take(500), saved = false) }

    fun toggleOccupation(option: String) = _state.update {
        it.copy(
            occupations = if (option in it.occupations) it.occupations - option else it.occupations + option,
            saved = false,
        )
    }

    fun addAccountRow(platform: String) = _state.update {
        it.copy(extraAccounts = it.extraAccounts + ExtraAccount(platform, ""), saved = false)
    }

    fun changeAccountHandle(index: Int, handle: String) = _state.update {
        it.copy(
            extraAccounts = it.extraAccounts.mapIndexed { i, row -> if (i == index) row.copy(handle = handle.take(60)) else row },
            saved = false,
        )
    }

    fun removeAccountRow(index: Int) = _state.update {
        it.copy(extraAccounts = it.extraAccounts.filterIndexed { i, _ -> i != index }, saved = false)
    }

    fun onAvatarPicked(uri: android.net.Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val path = store.persistAvatar(uri)
            if (path != null) {
                val bytes = store.readAvatarBytes(path)
                _state.update { it.copy(avatarUri = path, avatarBytes = bytes, saved = false) }
            }
        }
    }

    fun save() {
        val s = _state.value
        val error = validate(s)
        if (error != null) {
            _state.update { it.copy(validationError = error, saved = false) }
            return
        }
        store.save(
            UserProfile(
                name = s.name,
                preferredName = s.preferredName,
                phone = s.phone,
                wechatId = s.wechatId,
                douyinId = s.douyinId,
                avatarUri = s.avatarUri,
                feishuId = s.extraAccounts.firstOrNull { it.platform == "飞书" }?.handle,
                wecomId = s.extraAccounts.firstOrNull { it.platform == "企微" }?.handle,
                dingtalkId = s.extraAccounts.firstOrNull { it.platform == "钉钉" }?.handle,
                qqId = s.extraAccounts.firstOrNull { it.platform == "QQ" }?.handle,
                additionalAccounts = s.extraAccounts
                    .filter { it.handle.isNotBlank() }
                    .groupBy { platformKey(it.platform) }
                    .mapValues { (_, rows) -> rows.map { it.handle } }
                    .takeIf { it.isNotEmpty() },
                occupations = s.occupations,
                customInstructions = s.customInstructions,
            ),
        )
        _state.update { it.copy(saved = true, validationError = null) }
    }

    private fun validate(s: UserProfileUiState): String? = when {
        s.phone.isBlank() -> "请填写手机号"
        !isValidPhone(s.phone) -> "请输入 11 位手机号"
        s.wechatId.isBlank() -> "请填写微信号"
        else -> null
    }
}

private fun buildExtraAccounts(profile: UserProfile): List<ExtraAccount> {
    val rows = mutableListOf<ExtraAccount>()
    profile.feishuId?.let { rows += ExtraAccount("飞书", it) }
    profile.wecomId?.let { rows += ExtraAccount("企微", it) }
    profile.dingtalkId?.let { rows += ExtraAccount("钉钉", it) }
    profile.qqId?.let { rows += ExtraAccount("QQ", it) }
    profile.additionalAccounts?.forEach { (key, handles) ->
        handles.forEach { handle ->
            val row = ExtraAccount(platformLabel(key), handle)
            if (row !in rows) rows += row
        }
    }
    return rows.distinctBy { it.platform to it.handle }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun UserProfilePage(onBack: () -> Unit, viewModel: UserProfileViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    var showPlatformPicker by remember { mutableStateOf(false) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        viewModel.onAvatarPicked(uri)
    }

    ZhiBanPage {
        Column(Modifier.fillMaxSize()) {
            AgentHeader("个人资料", "知伴只使用你主动填写的信息", onBack)
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .imePadding()
                    .padding(horizontal = ZhiBanSpacing.PageHorizontal),
                contentPadding = PaddingValues(bottom = ZhiBanSpacing.Xxxl),
                verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md),
            ) {
                item {
                    AvatarCard(
                        avatarBytes = s.avatarBytes,
                        displayName = s.preferredName.ifBlank { s.name },
                        onPick = {
                            avatarPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                    )
                }

                item { ProfileSectionLabel("基本信息") }
                item {
                    ProfileCard {
                        ProfileCardField(value = s.name, onValueChange = viewModel::name, label = "全名", placeholder = "你的真实姓名（选填）")
                        ProfileCardDivider()
                        ProfileCardField(
                            value = s.preferredName,
                            onValueChange = viewModel::preferredName,
                            label = "知伴怎么称呼你",
                            placeholder = "例如：老周",
                            showDivider = false,
                        )
                    }
                }

                item { ProfileSectionLabel("联系方式") }
                item {
                    ProfileCard {
                        ProfileCardField(
                            value = s.phone,
                            onValueChange = viewModel::phone,
                            label = "手机号",
                            required = true,
                            placeholder = "11位手机号",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                        )
                        ProfileCardDivider()
                        ProfileCardField(
                            value = s.wechatId,
                            onValueChange = viewModel::wechat,
                            label = "微信号",
                            required = true,
                            placeholder = "你的微信号",
                            showDivider = false,
                        )
                        if (s.extraAccounts.isNotEmpty()) {
                            ProfileCardDivider()
                            s.extraAccounts.forEachIndexed { index, row ->
                                ExtraAccountRow(
                                    row = row,
                                    onHandleChange = { viewModel.changeAccountHandle(index, it) },
                                    onRemove = { viewModel.removeAccountRow(index) },
                                )
                            }
                        }
                        AddAccountRow(onClick = { showPlatformPicker = true })
                    }
                }

                item { ProfileSectionLabel("以下哪项最能描述你的工作和生活") }
                item {
                    ProfileCard {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Sm),
                            verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Sm),
                        ) {
                            OCCUPATION_OPTIONS.forEach { option ->
                                OccupationChip(
                                    label = option,
                                    selected = option in s.occupations,
                                    onClick = { viewModel.toggleOccupation(option) },
                                )
                            }
                        }
                    }
                }

                item { ProfileSectionLabel("给知伴的指令") }
                item {
                    ProfileCard {
                        TextField(
                            value = s.customInstructions,
                            onValueChange = viewModel::customInstructions,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                            placeholder = { Text("告诉知伴你希望它如何跟你协作") },
                            minLines = 3,
                            shape = RoundedCornerShape(ZhiBanRadius.Card),
                            colors = profileFieldColors(),
                        )
                        InstructionHintCarousel()
                    }
                }

                s.validationError?.let { error ->
                    item {
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = ZhiBanSpacing.Xs),
                        )
                    }
                }

                item {
                    Text(
                        "这些资料会加密保存在本机。知伴只在称呼你、识别“我本人”或完成相关任务时读取，不会把手机号和社交账号当作长期记忆重复保存。",
                        color = ZhiBanTextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = ZhiBanSpacing.Xs),
                    )
                }
                item {
                    Button(
                        onClick = viewModel::save,
                        modifier = Modifier.fillMaxWidth().height(ZhiBanSize.Control),
                        colors = ButtonDefaults.buttonColors(containerColor = ZhiBanTerracotta),
                        shape = RoundedCornerShape(ZhiBanRadius.Card),
                    ) {
                        Text(if (s.saved) "已保存" else "保存")
                    }
                }
            }
        }
    }

    if (showPlatformPicker) {
        PlatformPickerDialog(
            onSelect = { platform ->
                showPlatformPicker = false
                viewModel.addAccountRow(platform)
            },
            onDismiss = { showPlatformPicker = false },
        )
    }
}

@Composable
private fun AvatarCard(avatarBytes: ByteArray?, displayName: String, onPick: () -> Unit) {
    ProfileCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(ZhiBanRadius.Card))
                    .background(ZhiBanTerracotta)
                    .clickable(onClick = onPick),
                contentAlignment = Alignment.Center,
            ) {
                if (avatarBytes != null) {
                    coil.compose.AsyncImage(
                        model = avatarBytes,
                        contentDescription = "头像",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                } else {
                    Text(
                        displayName.take(1).ifBlank { "我" },
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 2.dp, y = 2.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(18.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onBackground),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = "更换头像",
                            tint = Color.White,
                            modifier = Modifier.size(11.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.width(ZhiBanSpacing.Lg))
            Column {
                Text(
                    displayName.ifBlank { "设置头像与称呼" },
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "点击头像可更换",
                    color = ZhiBanTextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ExtraAccountRow(row: ExtraAccount, onHandleChange: (String) -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = ZhiBanSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            row.platform,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(48.dp),
        )
        TextField(
            value = row.handle,
            onValueChange = onHandleChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("选填") },
            singleLine = true,
            shape = RoundedCornerShape(ZhiBanRadius.Small),
            colors = profileFieldColors(),
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(ZhiBanIconContainer.TouchTarget)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "删除${row.platform}账号",
                tint = ZhiBanTextSecondary,
                modifier = Modifier.size(ZhiBanIconSize.Inline),
            )
        }
    }
}

@Composable
private fun AddAccountRow(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ZhiBanRadius.Small))
            .clickable(onClick = onClick)
            .padding(vertical = ZhiBanSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(ZhiBanTerracottaSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, tint = ZhiBanTerracotta, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(ZhiBanSpacing.Sm))
        Text("添加更多账号（飞书/企微/钉钉/QQ）", color = ZhiBanTerracotta, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PlatformPickerDialog(onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择平台") },
        text = {
            Column {
                EXTRA_ACCOUNT_PLATFORMS.forEach { platform ->
                    Text(
                        platform,
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(ZhiBanRadius.Small))
                            .clickable { onSelect(platform) }
                            .padding(vertical = ZhiBanSpacing.Md, horizontal = ZhiBanSpacing.Sm),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun OccupationChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = RoundedCornerShape(ZhiBanRadius.Full),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surface,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = ZhiBanTerracotta,
            selectedLabelColor = Color.White,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.outlineVariant,
            selectedBorderColor = ZhiBanTerracotta,
        ),
    )
}

private val INSTRUCTION_HINTS = listOf(
    "回答要简洁，先说结论",
    "重要的事提前一天提醒我",
    "优先用中文，术语保留英文",
    "不确定的事告诉我「不确定」",
)

@Composable
private fun InstructionHintCarousel() {
    var index by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(3_000)
            index = (index + 1) % INSTRUCTION_HINTS.size
        }
    }
    Text(
        "例如：${INSTRUCTION_HINTS[index]}",
        color = ZhiBanTextSecondary,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(top = ZhiBanSpacing.Sm),
    )
}

@Composable
private fun ProfileSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = ZhiBanTextSecondary,
        modifier = Modifier.padding(horizontal = ZhiBanSpacing.Xs),
    )
}

@Composable
private fun ProfileCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ZhiBanRadius.Card))
            .background(MaterialTheme.colorScheme.surface)
            .padding(ZhiBanSpacing.Lg),
        content = content,
    )
}

@Composable
private fun ProfileCardDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(vertical = ZhiBanSpacing.Xs),
    )
}

@Composable
private fun ProfileCardField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    required: Boolean = false,
    showDivider: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
) {
    Column {
        Row {
            Text(label, color = ZhiBanTextSecondary, style = MaterialTheme.typography.labelMedium)
            if (required) Text(" *", color = ZhiBanTerracotta, style = MaterialTheme.typography.labelMedium)
        }
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder) },
            singleLine = true,
            keyboardOptions = keyboardOptions,
            shape = RoundedCornerShape(ZhiBanRadius.Small),
            colors = profileFieldColors(),
        )
    }
}

@Composable
private fun profileFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
)
