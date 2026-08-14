package com.zhiban.rebuild.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zhiban.rebuild.data.export.AgentPortableBackupService
import com.zhiban.rebuild.data.export.PortableRestoreSummary
import com.zhiban.rebuild.runtime.runSuspendCatching
import com.zhiban.rebuild.ui.components.ZhiBanAlertDialog
import com.zhiban.rebuild.ui.theme.ZhiBanCard
import com.zhiban.rebuild.ui.theme.ZhiBanRadius
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class PortableBackupUiState(
    val busy: Boolean = false,
    val backupFile: File? = null,
    val restoreSummary: PortableRestoreSummary? = null,
    val error: Boolean = false,
)

@HiltViewModel
internal class PortableBackupViewModel @Inject constructor(private val service: AgentPortableBackupService) : ViewModel() {
    private val mutableState = MutableStateFlow(PortableBackupUiState())
    val state = mutableState.asStateFlow()

    fun create(password: String) = runOperation {
        service.create(password.toCharArray()).also { file ->
            mutableState.update { it.copy(busy = false, backupFile = file) }
        }
    }

    fun restore(uri: Uri, password: String, openInput: (Uri) -> java.io.InputStream?) = runOperation {
        val input = requireNotNull(openInput(uri)) { "BACKUP_FILE_UNREADABLE" }
        service.stageRestore(input, password.toCharArray()).also { summary ->
            mutableState.update { it.copy(busy = false, restoreSummary = summary) }
        }
    }

    fun cancelRestore() = runOperation {
        service.cancelPendingRestore()
        mutableState.update { PortableBackupUiState() }
    }

    fun backupConsumed() = mutableState.update { it.copy(backupFile = null) }

    private fun runOperation(block: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, error = false) }
        viewModelScope.launch {
            runSuspendCatching { block() }.onFailure {
                mutableState.update { state -> state.copy(busy = false, error = true) }
            }
        }
    }
}

@Composable
internal fun PortableBackupSection(viewModel: PortableBackupViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<BackupPasswordDialog?>(null) }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { dialog = BackupPasswordDialog.Restore(it) }
    }
    LaunchedEffect(state.backupFile) {
        state.backupFile?.let { file ->
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = AgentPortableBackupService.MIME_TYPE
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "保存加密备份",
                ),
            )
            viewModel.backupConsumed()
        }
    }
    Column {
        SettingsCard {
            SettingsActionRow(
                if (state.busy) "正在处理…" else "创建加密备份",
                onClick = { dialog = BackupPasswordDialog.Create },
            )
            androidx.compose.material3.HorizontalDivider()
            SettingsActionRow(
                "从加密备份恢复",
                onClick = {
                    restorePicker.launch(arrayOf(AgentPortableBackupService.MIME_TYPE, "application/octet-stream"))
                },
            )
        }
        Text(
            "含联系人、关系、日程、记忆和 CRM；不含 API Key",
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = com.zhiban.rebuild.ui.theme.ZhiBanTextSecondary,
            modifier = Modifier.padding(horizontal = ZhiBanSpacing.Lg, vertical = ZhiBanSpacing.Sm),
        )
        if (state.error) {
            Text(
                "无法处理备份，请检查密码或文件",
                color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = ZhiBanSpacing.Lg),
            )
        }
    }
    dialog?.let { request ->
        BackupPasswordDialog(
            request = request,
            busy = state.busy,
            onDismiss = { dialog = null },
            onConfirm = { password ->
                dialog = null
                when (request) {
                    BackupPasswordDialog.Create -> viewModel.create(password)

                    is BackupPasswordDialog.Restore -> viewModel.restore(request.uri, password) {
                        context.contentResolver.openInputStream(it)
                    }
                }
            },
        )
    }
    state.restoreSummary?.let { summary ->
        RestoreReadyDialog(
            summary = summary,
            onCancel = viewModel::cancelRestore,
            onExit = {
                (context as? Activity)?.finishAndRemoveTask()
                Process.killProcess(Process.myPid())
            },
        )
    }
}

private sealed interface BackupPasswordDialog {
    data object Create : BackupPasswordDialog
    data class Restore(val uri: Uri) : BackupPasswordDialog
}

@Composable
private fun BackupPasswordDialog(request: BackupPasswordDialog, busy: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember(request) { mutableStateOf("") }
    var repeated by remember(request) { mutableStateOf("") }
    val creating = request == BackupPasswordDialog.Create
    val valid = password.length >= AgentPortableBackupService.MIN_PASSWORD_LENGTH && (!creating || password == repeated)
    ZhiBanAlertDialog(
        onDismissRequest = onDismiss,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(ZhiBanRadius.Dialog),
        containerColor = ZhiBanCard,
        title = { Text(if (creating) "创建加密备份" else "恢复加密备份") },
        text = {
            Column {
                Text(if (creating) "设置至少 10 位密码。恢复时必须使用同一密码。" else "输入创建备份时设置的密码。")
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("备份密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = ZhiBanSpacing.Md),
                )
                if (creating) {
                    OutlinedTextField(
                        value = repeated,
                        onValueChange = { repeated = it },
                        label = { Text("再次输入") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = ZhiBanSpacing.Sm),
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            Button(onClick = { onConfirm(password) }, enabled = valid && !busy) {
                Text(if (creating) "创建" else "验证并准备恢复")
            }
        },
    )
}

@Composable
private fun RestoreReadyDialog(summary: PortableRestoreSummary, onCancel: () -> Unit, onExit: () -> Unit) {
    ZhiBanAlertDialog(
        onDismissRequest = {},
        shape = androidx.compose.foundation.shape.RoundedCornerShape(ZhiBanRadius.Dialog),
        containerColor = ZhiBanCard,
        title = { Text("备份已验证") },
        text = {
            Text(
                "将恢复 ${summary.contactCount} 位联系人、${summary.relationshipCount} 条关系和 ${summary.scheduleCount} 条日程。" +
                    "退出后再次打开知伴即可完成。",
            )
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消恢复") } },
        confirmButton = {
            Button(
                onClick = onExit,
                colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.error),
            ) { Text("退出并恢复") }
        },
    )
}
