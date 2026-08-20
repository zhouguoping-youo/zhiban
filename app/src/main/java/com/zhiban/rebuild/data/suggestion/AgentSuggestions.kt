package com.zhiban.rebuild.data.suggestion

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 建议状态：与 CRM 建议（CrmSuggestionStatus）保持同一套语义，便于统一呈现。 */
object AgentSuggestionStatus {
    const val PENDING = "PENDING"
    const val ACCEPTED = "ACCEPTED"
    const val DISMISSED = "DISMISSED"
}

/** 建议类型：来自事件唤醒的 LLM 判断产出（后续可扩展 CRM 双写等来源）。 */
object AgentSuggestionType {
    const val WAKEUP_GENERAL = "WAKEUP_GENERAL"
    const val WAKEUP_CONTACT = "WAKEUP_CONTACT"
    const val WAKEUP_CRM = "WAKEUP_CRM"
    const val WAKEUP_SCHEDULE = "WAKEUP_SCHEDULE"
    const val WAKEUP_IDENTITY = "WAKEUP_IDENTITY"
    const val SILENT_CONTACTS = "SILENT_CONTACTS"
    const val UNOBSERVED_REPLY = "UNOBSERVED_REPLY"
    const val SCHEDULE_ADVANCE_CONFIRMATION = "SCHEDULE_ADVANCE_CONFIRMATION"
    const val IMPORTANT_DATE_REMINDER = "IMPORTANT_DATE_REMINDER"

    /** 联系人资料不完整 + 有互动 → 一键转发补全（关联 contact_completion_requests）。 */
    const val WAKEUP_COMPLETION = "WAKEUP_COMPLETION"
}

/**
 * 知伴主动建议中心：事件唤醒 LLM 综合判断后的产出落这里，统一到达用户。
 * 这是"agent 主动到达"通道的数据面——收据沉在自动整理页、CRM 建议只在机会详情页
 * 可见的问题，由本表 + 建议中心页收口。
 */
@Entity(
    tableName = "agent_suggestions",
    indices = [
        Index(value = ["dedupeKey"], unique = true),
        Index("status"),
        Index("createdAtEpochMs"),
        Index("contactId"),
        Index(value = ["status", "priorityScore"]),
    ],
)
data class AgentSuggestionEntity(
    @PrimaryKey val suggestionId: String,
    /** 见 [AgentSuggestionType]。 */
    val type: String,
    val title: String,
    /** LLM 的判断与建议正文（简短）。 */
    val body: String,
    val contactId: String?,
    /** 触发本建议的通知候选（回溯证据链）。 */
    val candidateId: String?,
    /** 唤醒事件类型标识（NOTIFICATION / CALL_ENDED 等）。 */
    val sourceEvent: String,
    /** 幂等键：wakeup-<candidateId>，同一候选只产一条建议。 */
    val dedupeKey: String,
    /** 见 [AgentSuggestionStatus]。 */
    val status: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    // ---- 结构化执行负载（DB 48→49）：接受建议后真正执行动作所需 ----
    /** 执行动作类型：SCHEDULE=创建日历事件；null=仅状态迁移（纯判断类建议）。 */
    val execActionType: String? = null,
    /** 日程标题（EventIntentExtractor 语义化生成，如「明天 09:30 接周国平（万科云城）→ 拜访九州通」）。 */
    val scheduleTitle: String? = null,
    /** 日程开始时间（epoch ms）。 */
    val startAtEpochMs: Long? = null,
    /** 日程时长（分钟）。 */
    val durationMinutes: Int? = null,
    /** 日程主地点（拜访地点优先，其次接人地点）。 */
    val location: String? = null,
    /** 客户公司全称（从联系人库/CRM 补全；查不到时等于简称）。 */
    val companyFull: String? = null,
    /** 待确认项（多行文本，如「拜访对象未提及…」），供用户在接受前核对。 */
    val confirmNotes: String? = null,
    /** 接受后创建的计划/日程 id（回写用，观察「已创建日程」状态）。 */
    val planId: String? = null,
    // ---- 双地点 / 对接人候选 / 行程（DB 49→50）----
    /** 接人地点（如「万科云城」）。 */
    val pickupLocation: String? = null,
    /** 拜访地点（客户公司地址）。 */
    val visitLocation: String? = null,
    /** 拜访地点来源：CONTACT=联系人库地址 / REGISTRY=公司注册地址 / null=未知。 */
    val visitLocationSource: String? = null,
    /** 对接人候选（JSON 数组，字段 contactId/name/title/company）。 */
    val contactCandidatesJson: String? = null,
    /** 建议出发时间（行程估算，epoch ms）。 */
    val departAtEpochMs: Long? = null,
    /** 行程说明（如「建议 08:45 出发（坐标 8.2km 直线 × 1.4 路况系数…）」）。 */
    val travelNote: String? = null,
    // ---- 一键转发补全（DB 50→51）：联系人资料不全 → 转发微信消息请对方补充 ----
    /** 关联 contact_completion_requests.requestId（接受/忽略时联动状态）。 */
    val completionRequestId: String? = null,
    /** 起草好的补全微信消息文案（用户可编辑后再转发）。 */
    val forwardMessage: String? = null,
    /** 本轮要问的字段名 JSON 数组（ContactProfileField.name），供卡片渲染 chips。 */
    val missingFieldsJson: String? = null,
    /** 反馈学习后的展示优先级。50 为中性，值越大越靠前；不包含用户原文。 */
    val priorityScore: Int = DEFAULT_SUGGESTION_PRIORITY,
)

data class AgentSuggestionFeedbackStats(val acceptedCount: Int, val dismissedCount: Int)

@Dao
interface AgentSuggestionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(suggestion: AgentSuggestionEntity): Long

    @Query(
        "SELECT * FROM agent_suggestions ORDER BY " +
            "CASE WHEN status = 'PENDING' THEN 0 ELSE 1 END, priorityScore DESC, createdAtEpochMs DESC " +
            "LIMIT :limit OFFSET :offset",
    )
    fun observeRecent(limit: Int = 100, offset: Int = 0): Flow<List<AgentSuggestionEntity>>

    @Query("SELECT COUNT(*) FROM agent_suggestions WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM agent_suggestions WHERE status = 'PENDING'")
    suspend fun pendingCount(): Int

    @Query("SELECT * FROM agent_suggestions WHERE suggestionId = :suggestionId")
    suspend fun find(suggestionId: String): AgentSuggestionEntity?

    @Query(
        "SELECT * FROM agent_suggestions WHERE status = 'PENDING' AND contactId = :contactId " +
            "ORDER BY createdAtEpochMs DESC LIMIT :limit",
    )
    suspend fun pendingForContact(contactId: String, limit: Int): List<AgentSuggestionEntity>

    @Query(
        """SELECT
           COALESCE(SUM(CASE WHEN status = 'ACCEPTED' THEN 1 ELSE 0 END), 0) AS acceptedCount,
           COALESCE(SUM(CASE WHEN status = 'DISMISSED' THEN 1 ELSE 0 END), 0) AS dismissedCount
           FROM agent_suggestions
           WHERE type = :type AND updatedAtEpochMs >= :sinceEpochMs
             AND ((:contactId IS NULL AND contactId IS NULL) OR contactId = :contactId)
             AND status IN ('ACCEPTED', 'DISMISSED')""",
    )
    suspend fun feedbackStats(type: String, contactId: String?, sinceEpochMs: Long): AgentSuggestionFeedbackStats

    @Query(
        "SELECT * FROM agent_suggestions WHERE status = 'PENDING' AND execActionType = 'SCHEDULE' " +
            "AND startAtEpochMs BETWEEN :nowEpochMs AND :beforeEpochMs ORDER BY startAtEpochMs",
    )
    suspend fun imminentSchedules(nowEpochMs: Long, beforeEpochMs: Long): List<AgentSuggestionEntity>

    @Query(
        "UPDATE agent_suggestions SET status = :status, updatedAtEpochMs = :nowEpochMs " +
            "WHERE suggestionId = :suggestionId AND status = :expectedStatus",
    )
    suspend fun transitionStatus(suggestionId: String, expectedStatus: String, status: String, nowEpochMs: Long): Int

    @Query(
        "UPDATE agent_suggestions SET status = 'ACCEPTED', planId = :planId, updatedAtEpochMs = :nowEpochMs " +
            "WHERE suggestionId = :suggestionId AND status = 'PENDING'",
    )
    suspend fun markScheduleCreated(suggestionId: String, planId: String, nowEpochMs: Long): Int

    @Query(
        "UPDATE agent_suggestions SET status = 'DISMISSED', updatedAtEpochMs = :nowEpochMs WHERE status = 'PENDING' AND (" +
            "(execActionType = 'SCHEDULE' AND startAtEpochMs < :nowEpochMs) OR " +
            "((execActionType IS NULL OR execActionType != 'SCHEDULE' OR startAtEpochMs IS NULL) AND createdAtEpochMs < :cutoffEpochMs))",
    )
    suspend fun expirePending(cutoffEpochMs: Long, nowEpochMs: Long): Int

    @Query("DELETE FROM agent_suggestions WHERE status != 'PENDING' AND createdAtEpochMs < :beforeEpochMs")
    suspend fun pruneSettledBefore(beforeEpochMs: Long): Int
}

internal const val DEFAULT_SUGGESTION_PRIORITY = 50

/** 对接人候选的 JSON 编解码（存储于 agent_suggestions.contactCandidatesJson）。 */
object AgentSuggestionCodecs {
    private val json = Json { ignoreUnknownKeys = true }

    fun encodeCandidates(candidates: List<EventIntentExtractor.ContactCandidate>): String? {
        if (candidates.isEmpty()) return null
        return buildJsonArray {
            candidates.forEach { c ->
                add(
                    buildJsonObject {
                        put("contactId", c.contactId)
                        put("name", c.name)
                        c.title?.let { put("title", it) }
                        c.company?.let { put("company", it) }
                    },
                )
            }
        }.toString()
    }

    fun decodeCandidates(encoded: String?): List<EventIntentExtractor.ContactCandidate> {
        if (encoded.isNullOrBlank()) return emptyList()
        return try {
            json.parseToJsonElement(encoded).jsonArray.map { el ->
                val o = el.jsonObject
                EventIntentExtractor.ContactCandidate(
                    contactId = o["contactId"]?.jsonPrimitive?.contentOrNull ?: "",
                    name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    title = o["title"]?.jsonPrimitive?.contentOrNull,
                    company = o["company"]?.jsonPrimitive?.contentOrNull,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 补全建议：要问的字段名 JSON 数组（ContactProfileField.name）。 */
    fun encodeMissingFields(fields: List<com.zhiban.rebuild.data.contact.ContactProfileField>): String? {
        if (fields.isEmpty()) return null
        return buildJsonArray {
            fields.forEach { add(JsonPrimitive(it.name)) }
        }.toString()
    }

    /** 补全建议：字段名 JSON → ContactProfileField 列表；未知/损坏时回退空列表（不影响卡片渲染）。 */
    fun decodeMissingFields(encoded: String?): List<com.zhiban.rebuild.data.contact.ContactProfileField> {
        if (encoded.isNullOrBlank()) return emptyList()
        return try {
            json.parseToJsonElement(encoded).jsonArray.mapNotNull { el ->
                val name = el.jsonPrimitive.contentOrNull
                name?.let { n -> com.zhiban.rebuild.data.contact.ContactProfileField.entries.firstOrNull { it.name == n } }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
