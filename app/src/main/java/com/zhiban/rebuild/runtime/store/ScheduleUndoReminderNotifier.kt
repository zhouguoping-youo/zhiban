package com.zhiban.rebuild.runtime.store

import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.ScheduleEntity
import com.zhiban.rebuild.runtime.governance.AutoWriteToolNames
import com.zhiban.rebuild.runtime.runSuspendCatching
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val SCHEDULE_TOOLS = setOf(
    "calendar.schedule.create",
    "calendar.schedule.update",
    "calendar.schedule.delete",
    AutoWriteToolNames.SCHEDULE_CREATE,
)

internal suspend fun AgentDatabase.notifyReminderAfterScheduleUndo(commandId: String, notify: (String, ScheduleEntity?) -> Unit) {
    val command = runtimeCommandInboxDao().find(commandId)?.takeIf { it.commandType == "Undo" } ?: return
    val changeId = runSuspendCatching {
        Json.parseToJsonElement(command.payloadJson).jsonObject["payloadRef"]?.jsonPrimitive?.content
    }.getOrNull() ?: return
    val change = changeLogDao().find(changeId)?.takeIf {
        it.toolName in SCHEDULE_TOOLS && it.undoState == "UNDONE"
    } ?: return
    notify(change.targetId, scheduleDao().findById(change.targetId))
}
