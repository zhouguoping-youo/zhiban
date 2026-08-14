package com.zhiban.rebuild.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zhiban.rebuild.data.agent.ScheduleProjection
import com.zhiban.rebuild.runtime.input.asr.CloudAsrAvailability
import com.zhiban.rebuild.ui.components.ZhiBanDialogHeader
import com.zhiban.rebuild.ui.components.ZhiBanTaskDialog
import com.zhiban.rebuild.ui.theme.ZhiBanRadius
import com.zhiban.rebuild.ui.theme.ZhiBanSize
import java.io.File

@Composable
internal fun ScheduleCompletionDialog(
    schedule: ScheduleProjection,
    actions: ScheduleCompletionActions,
    voice: ScheduleOutcomeVoiceConfig = ScheduleOutcomeVoiceConfig(),
) {
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
        ZhiBanDialogHeader("更新进展", dismissDialog, subtitle = schedule.title)
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
            Button(
                onClick = actions.onPostpone,
                enabled = !voiceController.isBusy,
                modifier = Modifier.weight(1f).height(ZhiBanSize.Control),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CalendarSoft,
                    contentColor = CalendarInk,
                ),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(ZhiBanRadius.Card),
            ) {
                Text("延期")
            }
            Button(
                onClick = { actions.onComplete(feedback.trim().takeIf(String::isNotEmpty)) },
                enabled = !voiceController.isBusy,
                modifier = Modifier.weight(1f).height(ZhiBanSize.Control),
                colors = ButtonDefaults.buttonColors(containerColor = CalendarAccent),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(ZhiBanRadius.Card),
            ) { Text("标记完成") }
        }
        TextButton(
            onClick = actions.onCancelSchedule,
            enabled = !voiceController.isBusy,
            modifier = Modifier.fillMaxWidth().height(ZhiBanSize.Control),
        ) {
            Text("取消日程", color = CalendarDanger)
        }
    }
}

internal data class ScheduleCompletionActions(
    val onDismiss: () -> Unit,
    val onComplete: (String?) -> Unit,
    val onPostpone: () -> Unit,
    val onCancelSchedule: () -> Unit,
)

internal data class ScheduleOutcomeVoiceConfig(
    val availability: CloudAsrAvailability? = null,
    val onTranscribe: (File, (String?, String?) -> Unit) -> Unit = { _, callback ->
        callback(null, "语音服务暂不可用")
    },
)

private const val MAX_OUTCOME_LENGTH = 1_000
