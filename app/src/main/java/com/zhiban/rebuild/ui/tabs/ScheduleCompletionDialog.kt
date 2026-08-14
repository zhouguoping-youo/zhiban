package com.zhiban.rebuild.ui.tabs

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import com.zhiban.rebuild.ui.components.ZhiBanDialogHeader
import com.zhiban.rebuild.ui.components.ZhiBanTaskDialog
import com.zhiban.rebuild.ui.theme.ZhiBanRadius
import com.zhiban.rebuild.ui.theme.ZhiBanSize

@Composable
internal fun ScheduleCompletionDialog(
    schedule: ScheduleProjection,
    onDismiss: () -> Unit,
    onComplete: (String?) -> Unit,
    onPostpone: () -> Unit,
    onCancelSchedule: () -> Unit,
) {
    var feedback by remember(schedule.id) { mutableStateOf(schedule.outcomeNote.orEmpty()) }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { feedback = it }
    }
    ZhiBanTaskDialog(onDismissRequest = onDismiss, maxWidth = 480.dp, maxHeight = 520.dp) {
        ZhiBanDialogHeader("更新进展", onDismiss, subtitle = schedule.title)
        OutlinedTextField(
            value = feedback,
            onValueChange = { feedback = it.take(1_000) },
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            minLines = 3,
            label = { Text("结果或备注（可选）") },
            trailingIcon = {
                TextButton(
                    onClick = {
                        speech.launch(
                            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "说出这项安排的结果")
                            },
                        )
                    },
                ) { Icon(Icons.Outlined.Mic, "语音填写结果") }
            },
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = onPostpone,
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
                onClick = { onComplete(feedback.trim().takeIf(String::isNotEmpty)) },
                modifier = Modifier.weight(1f).height(ZhiBanSize.Control),
                colors = ButtonDefaults.buttonColors(containerColor = CalendarAccent),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(ZhiBanRadius.Card),
            ) { Text("标记完成") }
        }
        TextButton(
            onClick = onCancelSchedule,
            modifier = Modifier.fillMaxWidth().height(ZhiBanSize.Control),
        ) {
            Text("取消日程", color = CalendarDanger)
        }
    }
}
