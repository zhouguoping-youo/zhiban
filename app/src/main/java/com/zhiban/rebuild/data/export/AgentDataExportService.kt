package com.zhiban.rebuild.data.export

import android.content.Context
import android.util.JsonWriter
import androidx.room.withTransaction
import com.zhiban.rebuild.BuildConfig
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.MemoryEntity
import com.zhiban.rebuild.data.agent.ScheduleEntity
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.crm.CrmActivityEntity
import com.zhiban.rebuild.data.crm.CrmLeadEntity
import com.zhiban.rebuild.data.crm.CrmNextActionEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityEntity
import com.zhiban.rebuild.data.store.RuntimeConversationTurnEntity
import com.zhiban.rebuild.provider.SecretRedactor
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private typealias ExportPageFetcher<T> = suspend (Int, Int) -> List<T>
private typealias ExportItemWriter<T> = JsonWriter.(T) -> Unit

/**
 * 导出知伴保存的数据。数据库按页读取、JSON 直接写文件，避免大数据量时同时持有实体列表、JSON 树和编码字符串。
 * API Key / 凭据不导出；直接标识符在落盘前经 [SecretRedactor] 脱敏。
 */
class AgentDataExportService @Inject internal constructor(
    @ApplicationContext private val context: Context,
    private val database: AgentDatabase,
    private val redactor: SecretRedactor,
) {
    private var pageSize: Int = DEFAULT_PAGE_SIZE

    internal constructor(context: Context, database: AgentDatabase, redactor: SecretRedactor, pageSize: Int) :
        this(context, database, redactor) {
        require(pageSize > 0) { "EXPORT_PAGE_SIZE_INVALID" }
        this.pageSize = pageSize
    }

    suspend fun create(nowEpochMs: Long = System.currentTimeMillis()): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        directory.listFiles()?.filter { nowEpochMs - it.lastModified() > RETENTION_MS }?.forEach(File::delete)
        val target = File(directory, "zhiban-data-export-$nowEpochMs.json")
        val partial = File(directory, "${target.name}.part")
        partial.delete()
        var committed = false
        try {
            partial.outputStream().bufferedWriter().use { output ->
                JsonWriter(output).use { writer ->
                    writer.setIndent("  ")
                    database.withTransaction { writeExport(writer, nowEpochMs) }
                }
            }
            check(!target.exists() || target.delete()) { "EXPORT_REPLACE_FAILED" }
            check(partial.renameTo(target)) { "EXPORT_COMMIT_FAILED" }
            committed = true
            target
        } finally {
            if (!committed) partial.delete()
        }
    }

    private suspend fun writeExport(writer: JsonWriter, nowEpochMs: Long) = with(writer) {
        beginObject()
        name("schemaVersion").value(SCHEMA_VERSION.toLong())
        name("generatedAtEpochMs").value(nowEpochMs)
        name("appVersion").value(BuildConfig.VERSION_NAME)
        name("privacy").value("REDACTED_NO_CREDENTIALS")
        name("conversations")
        writePagedArray(database.runtimeConversationTurnDao()::listPageForExport) { writeConversation(it) }
        name("memories")
        writePagedArray(database.memoryDao()::listPageForExport) { writeMemory(it) }
        name("contacts")
        writePagedArray(database.contactDao()::listActivePageForExport) { writeContact(it) }
        name("schedules")
        writePagedArray(database.scheduleDao()::listPageForExport) { writeSchedule(it) }
        name("crm")
        writeCrm(this)
        endObject()
    }

    private suspend fun writeCrm(writer: JsonWriter) = with(writer) {
        val crm = database.crmDao()
        beginObject()
        name("leads")
        writePagedArray(crm::listLeadPageForExport) { writeLead(it) }
        name("opportunities")
        writePagedArray(crm::listOpportunityPageForExport) { writeOpportunity(it) }
        name("activities")
        writePagedArray(crm::listActivityPageForExport) { writeActivity(it) }
        name("nextActions")
        writePagedArray(crm::listNextActionPageForExport) { writeNextAction(it) }
        endObject()
    }

    private suspend fun <T> JsonWriter.writePagedArray(fetchPage: ExportPageFetcher<T>, writeItem: ExportItemWriter<T>) {
        beginArray()
        var offset = 0
        do {
            val page = fetchPage(pageSize, offset)
            page.forEach { writeItem(it) }
            offset += page.size
        } while (page.size == pageSize)
        endArray()
    }

    private fun JsonWriter.writeConversation(turn: RuntimeConversationTurnEntity) {
        beginObject()
        name("role").value(turn.role)
        name("text").value(redactor.redactWithoutTruncation(turn.content))
        name("createdAtEpochMs").value(turn.createdAtEpochMs)
        endObject()
    }

    private fun JsonWriter.writeMemory(memory: MemoryEntity) {
        beginObject()
        name("kind").value(memory.kind)
        name("content").value(redactor.redactWithoutTruncation(memory.content))
        name("createdAtEpochMs").value(memory.createdAtEpochMs)
        endObject()
    }

    private fun JsonWriter.writeContact(contact: ContactEntity) {
        beginObject()
        name("displayName").value(redactor.redactWithoutTruncation(contact.displayName))
        name("phone").value(redactor.redactWithoutTruncation(contact.phone))
        name("email").value(redactor.redactWithoutTruncation(contact.email))
        name("wechatId").value(contact.wechatId?.takeIf(String::isNotBlank)?.let { REDACTED_CONTACT_ID }.orEmpty())
        name("company").value(redactor.redactWithoutTruncation(contact.company))
        name("title").value(redactor.redactWithoutTruncation(contact.title))
        name("note").value(redactor.redactWithoutTruncation(contact.note))
        name("source").value(contact.source)
        endObject()
    }

    private fun JsonWriter.writeSchedule(schedule: ScheduleEntity) {
        beginObject()
        name("title").value(redactor.redactWithoutTruncation(schedule.title))
        name("startAtEpochMs").value(schedule.startAtEpochMs)
        name("durationMinutes").value(schedule.durationMinutes.toLong())
        schedule.note?.let { name("note").value(redactor.redactWithoutTruncation(it)) }
        schedule.reminderMinutesBefore?.let { name("reminderMinutesBefore").value(it.toLong()) }
        endObject()
    }

    private fun JsonWriter.writeLead(lead: CrmLeadEntity) {
        beginObject()
        name("displayName").value(redactor.redactWithoutTruncation(lead.displayNameSnapshot))
        lead.companyNameSnapshot?.let { name("company").value(redactor.redactWithoutTruncation(it)) }
        name("status").value(lead.status)
        name("sourceType").value(lead.sourceType)
        lead.fitSummary?.let { name("fitSummary").value(redactor.redactWithoutTruncation(it)) }
        endObject()
    }

    private fun JsonWriter.writeOpportunity(opportunity: CrmOpportunityEntity) {
        beginObject()
        name("title").value(redactor.redactWithoutTruncation(opportunity.title))
        name("account").value(redactor.redactWithoutTruncation(opportunity.accountNameSnapshot))
        name("stage").value(opportunity.stage)
        name("status").value(opportunity.status)
        opportunity.valueMinor?.let { name("valueMinor").value(it) }
        name("currencyCode").value(opportunity.currencyCode)
        endObject()
    }

    private fun JsonWriter.writeActivity(activity: CrmActivityEntity) {
        beginObject()
        name("activityType").value(activity.activityType)
        name("title").value(redactor.redactWithoutTruncation(activity.title))
        name("summary").value(redactor.redactWithoutTruncation(activity.summary))
        name("occurredAtEpochMs").value(activity.occurredAtEpochMs)
        endObject()
    }

    private fun JsonWriter.writeNextAction(action: CrmNextActionEntity) {
        beginObject()
        name("actionType").value(action.actionType)
        name("title").value(redactor.redactWithoutTruncation(action.title))
        action.dueAtEpochMs?.let { name("dueAtEpochMs").value(it) }
        name("status").value(action.status)
        endObject()
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val DIRECTORY = "data-export"
        const val DEFAULT_PAGE_SIZE = 200
        const val RETENTION_MS = 24L * 60 * 60 * 1_000
        const val REDACTED_CONTACT_ID = "[REDACTED_CONTACT_ID]"
    }
}
