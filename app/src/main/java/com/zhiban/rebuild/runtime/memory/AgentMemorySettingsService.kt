package com.zhiban.rebuild.runtime.memory

import com.zhiban.agent.memory.MemoryCommit
import com.zhiban.agent.memory.MemoryNamespace
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.runtime.context.MemoryScope
import com.zhiban.rebuild.runtime.context.RoomStagedMemoryCandidateStore
import com.zhiban.rebuild.runtime.context.Sensitivity
import com.zhiban.rebuild.runtime.tool.RoomMemoryToolExecutor
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AgentMemoryItem(val id: String, val text: String, val type: String, val categoryLabel: String, val sensitivity: String, val createdAtEpochMs: Long)

/** UI-safe Agent memory facade. It owns all database access and deletion barriers. */
@Singleton
class AgentMemorySettingsService @Inject internal constructor(private val database: AgentDatabase) {
    private val store = RoomMemoryGate(database)
    private val staged = RoomStagedMemoryCandidateStore(database)

    private suspend fun ensure() = store.ensureNamespace(
        MemoryNamespace(
            RoomMemoryToolExecutor.GLOBAL_NAMESPACE,
            "local-user",
            "default",
            "GLOBAL",
            "",
            System.currentTimeMillis(),
        ),
    )

    suspend fun list(): List<AgentMemoryItem> = withContext(Dispatchers.IO) {
        ensure()
        store.recall(RoomMemoryToolExecutor.GLOBAL_NAMESPACE).records
            .map {
                AgentMemoryItem(
                    it.logicalMemoryId,
                    it.canonicalText,
                    it.memoryType,
                    category(it.memoryType),
                    it.sensitivity,
                    it.updatedAtEpochMs,
                )
            }
            .sortedByDescending { it.createdAtEpochMs }
    }

    suspend fun add(text: String, type: String) = withContext(Dispatchers.IO) {
        addInternal(text, type)
    }

    private suspend fun addInternal(text: String, type: String, excludeLogicalMemoryId: String? = null) {
        val canonical = text.trim().replace(Regex("\\s+"), " ")
        require(canonical.isNotBlank() && canonical.length <= 500) { "MEMORY_LENGTH_INVALID" }
        require(type in setOf("PROFILE", "PREFERENCE", "RELATIONSHIP", "GOAL", "PROJECT_RULE", "EXPERIENCE", "FACT"))
        ensure()
        if (store.recall(RoomMemoryToolExecutor.GLOBAL_NAMESPACE).records.any {
                it.logicalMemoryId != excludeLogicalMemoryId && it.canonicalText == canonical
            }
        ) {
            return
        }
        val now = System.currentTimeMillis()
        val source = "settings:user-confirmed:$now"
        val candidate = staged.stage(
            MemoryScope.GLOBAL,
            null,
            canonical,
            listOf(source),
            Sensitivity.PERSONAL,
            now,
            24 * 60 * 60 * 1_000L,
        )
        val approval = "settings-${UUID.randomUUID()}"
        check(staged.approve(candidate.id, approval, candidate.revision, now).name in setOf("APPROVED", "DUPLICATE"))
        val suffix = UUID.randomUUID().toString().replace("-", "").take(24)
        store.commit(
            MemoryCommit(
                RoomMemoryToolExecutor.GLOBAL_NAMESPACE, candidate.id, approval, candidate.revision + 1,
                "memory-$suffix", "logical-$suffix", type, "user", type.lowercase(), canonical,
                digest(canonical), digest(source),
            ),
        )
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256").digest("settings-delete:$id".toByteArray())
            .joinToString("") { "%02x".format(it) }
        store.delete(RoomMemoryToolExecutor.GLOBAL_NAMESPACE, id, digest)
    }

    suspend fun update(item: AgentMemoryItem, text: String, type: String) = withContext(Dispatchers.IO) {
        val canonical = text.trim().replace(Regex("\\s+"), " ")
        if (canonical == item.text && type == item.type) return@withContext
        // Exclude the record being edited from duplicate detection. Otherwise changing only the
        // category would skip the replacement and then delete the original memory.
        addInternal(canonical, type, excludeLogicalMemoryId = item.id)
        delete(item.id)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        list().forEach { delete(it.id) }
    }

    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun category(type: String) = when (type) {
        "PROFILE", "FACT" -> "关于我"
        "PREFERENCE" -> "偏好与习惯"
        "RELATIONSHIP" -> "家人与朋友"
        "GOAL" -> "目标与计划"
        "PROJECT_RULE" -> "长期规则"
        "EXPERIENCE" -> "知伴经验"
        else -> "其他"
    }
}
