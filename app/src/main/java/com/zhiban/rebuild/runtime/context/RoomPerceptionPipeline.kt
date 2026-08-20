package com.zhiban.rebuild.runtime.context

import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.contact.findMentionedCandidates
import com.zhiban.rebuild.foundation.runSuspendCatching
import kotlinx.serialization.json.Json

internal interface PerceptionGateway {
    suspend fun perceive(text: String): QueryContext
    fun fallback(text: String): QueryContext
}

internal class RoomPerceptionPipeline(
    private val database: AgentDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val extractor: LocalEntityExtractor = LocalEntityExtractor(),
) : PerceptionGateway {
    override suspend fun perceive(text: String): QueryContext {
        val contacts = database.contactDao().findMentionedCandidates(text)
        val dictionary = contacts.map { contact ->
            val role = database.contactDao().roles(contact.contactId).firstOrNull()
            EntityDictionaryEntry(
                value = contact.displayName,
                entityId = contact.contactId,
                aliases = runSuspendCatching {
                    Json.decodeFromString<List<String>>(contact.aliasesJson)
                }.getOrDefault(emptyList()),
                roleType = role?.roleType,
                skillId = role?.skillId,
            )
        }
        return extractor.extract(text, clock(), dictionary)
    }

    override fun fallback(text: String): QueryContext = extractor.extract(text, clock())
}
