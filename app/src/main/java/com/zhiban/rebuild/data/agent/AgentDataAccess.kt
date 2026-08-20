package com.zhiban.rebuild.data.agent

import androidx.room.withTransaction
import com.zhiban.rebuild.data.autowrite.AutoWriteAuditDraft
import com.zhiban.rebuild.data.autowrite.ChangeLogDao
import com.zhiban.rebuild.data.contact.ContactDao
import com.zhiban.rebuild.data.contact.ContactIdentityDao
import com.zhiban.rebuild.data.contact.ContactIntelligenceDao
import com.zhiban.rebuild.data.contact.ContactKnowledgeDao
import com.zhiban.rebuild.data.facts.FactDao
import com.zhiban.rebuild.data.interaction.ContactInteractionDao
import com.zhiban.rebuild.data.notification.NotificationCandidateDao

/**
 * AgentDataRepository 直接使用的那几个 DAO，收拢成一个注入参数。
 * 目的：让 AgentDataRepository 不再持有整个 AgentDatabase 实例（dim 1c），
 * 只依赖它真正需要的 DAO，子领域各自经 DI 拿到自己的 DAO。
 */
internal data class AgentDataDaos(
    val notificationCandidateDao: NotificationCandidateDao,
    val contactDao: ContactDao,
    val contactIdentityDao: ContactIdentityDao,
    val contactKnowledgeDao: ContactKnowledgeDao,
    val contactIntelligenceDao: ContactIntelligenceDao,
    val factDao: FactDao,
    val changeLogDao: ChangeLogDao,
    val senderMuteDao: com.zhiban.rebuild.data.notification.SenderMuteDao,
    val contactInteractionDao: ContactInteractionDao,
)

/**
 * 把 Room 的 `database.withTransaction` 抽象成能力，让 AgentDataRepository
 * 不再依赖 AgentDatabase 实例即可运行多表事务。
 */
internal interface AgentTransactionRunner {
    suspend fun <T> runInTransaction(block: suspend () -> T): T
}

internal class RoomAgentTransactionRunner(private val database: AgentDatabase) : AgentTransactionRunner {
    override suspend fun <T> runInTransaction(block: suspend () -> T): T = database.withTransaction { block() }
}

/** 把"可见自动写"这条 DB 级操作收成能力注入，AgentDataRepository 不必为此持有 AgentDatabase。 */
internal fun interface AutoWriteSink {
    suspend fun insertVisible(draft: AutoWriteAuditDraft)
}

/** Side effect kept outside the Room transaction that creates an automatic schedule. */
internal fun interface ScheduleReminderSink {
    fun replace(schedule: ScheduleEntity)
}
