package com.zhiban.rebuild.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zhiban.rebuild.data.agent.ScheduleProjection
import com.zhiban.rebuild.data.agent.ScheduleStatus
import com.zhiban.rebuild.runtime.input.asr.CloudAsrAvailability
import com.zhiban.rebuild.ui.components.ZhiBanDialogHeader
import com.zhiban.rebuild.ui.components.ZhiBanPrimaryButton
import com.zhiban.rebuild.ui.components.ZhiBanSecondaryButton
import com.zhiban.rebuild.ui.components.ZhiBanTaskDialog
import com.zhiban.rebuild.ui.components.ZhiBanTextActionButton
import java.io.File

@Composable
internal fun ScheduleCompletionDialog(
    schedule: ScheduleProjection,
    actions: ScheduleCompletionActions,
    voice: ScheduleOutcomeVoiceConfig = ScheduleOutcomeVoiceConfig(),
) {
    val completed = schedule.status == ScheduleStatus.COMPLETED
    var feedback by remember(schedule.id) { mutableStateOf(schedule.outcomeNote.orEmpty()) }
    val voiceController = rememberScheduleOutcomeVoiceController(
        scheduleId = schedule.id,
        onTranscribe = voice.onTranscribe,
        onRecognized = { feedback = it.take(MAX_OUTCOME_LENGTH) },
    )
    val dismissDialog = {
        voiceController.dispose()
        actions.onDismiss()
    }
    ZhiBanTaskDialog(onDismissRequest = dismissDialog, maxWidth = 480.dp, maxHeight = 520.dp) {
        ZhiBanDialogHeader(if (completed) "查看结果" else "更新进展", dismissDialog, subtitle = schedule.title)
        ScheduleOutcomeVoiceField(
            value = feedback,
            onValueChange = { feedback = it.take(MAX_OUTCOME_LENGTH) },
            availability = voice.availability,
            controller = voiceController,
            modifier = Modifier.padding(top = 20.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ZhiBanSecondaryButton(
                text = "改期",
                onClick = actions.onPostpone,
                modifier = Modifier.weight(1f),
                enabled = !voiceController.isBusy,
            )
            ZhiBanPrimaryButton(
                text = if (completed) "保存结果" else "标记完成",
                onClick = { actions.onComplete(feedback.trim().takeIf(String::isNotEmpty)) },
                modifier = Modifier.weight(1f),
                enabled = !voiceController.isBusy,
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ZhiBanTextActionButton(
                text = "编辑详情",
                onClick = actions.onEdit,
                enabled = !voiceController.isBusy,
            )
            ZhiBanTextActionButton(
                text = "取消日程",
                onClick = actions.onCancelSchedule,
                enabled = !voiceController.isBusy,
                danger = true,
            )
        }
    }
}

internal data class ScheduleCompletionActions(
    val onDismiss: () -> Unit,
    val onComplete: (String?) -> Unit,
    val onPostpone: () -> Unit,
    val onEdit: () -> Unit,
    val onCancelSchedule: () -> Unit,
)

internal data class ScheduleOutcomeVoiceConfig(
    val availability: CloudAsrAvailability? = null,
    val onTranscribe: (File, (String?, String?) -> Unit) -> Unit = { _, callback ->
        callback(null, "语音服务暂不可用")
    },
)

private const val MAX_OUTCOME_LENGTH = 1_000
