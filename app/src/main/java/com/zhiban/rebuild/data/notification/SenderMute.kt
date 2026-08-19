package com.zhiban.rebuild.data.notification

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.zhiban.rebuild.data.contact.normalizeContactPhone
import kotlinx.coroutines.flow.Flow

/**
 * 身份值归一化:小写、去全部空白、去前导 @。三级降级匹配、观察身份持久化与发送者
 * 静默共用同一把尺子,保证同一发送者在各处的键一致(原为 AgentDataRepository 私有函数,
 * 因静默名单/收件箱折叠也要用而提为包级共享)。
 */
internal fun normalizeIdentityValue(value: String): String = value.lowercase().filterNot(Char::isWhitespace).trimStart('@')

/**
 * 发送者级键:(平台, 归一化 handle)。SMS 走电话号码归一化(带国家码语义),其余平台走
 * 身份值归一化。微信堆叠未读时发送者名可能带 "[N条]" 前缀,先剥掉再归一化,保证与
 * 解析管线(SocialNotificationParser)同一条发送者的多次捕获键一致。
 */
internal fun normalizeSenderHandle(platform: String, senderName: String?): String? {
    val visible = senderName
        ?.replace(SocialNotificationParser.UNREAD_COUNT_PREFIX, "")
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return null
    return if (platform == "SMS") normalizeContactPhone(visible) else normalizeIdentityValue(visible)
}

/**
 * 用户在候选卡点"不再提醒此人"后的持久静默。与 SourceIdentityEntity 分表存放:
 * 静默是用户决策,观察身份是证据,混在一起会污染身份解析语义;且静默键是发送者级
 * (不分群会话),而 source_identities 按会话范围分键,键位并不一致。
 */
@Entity(
    tableName = "sender_mutes",
    indices = [
        Index(value = ["platform", "normalizedHandle"], unique = true),
        Index("createdAtEpochMs"),
    ],
)
data class SenderMuteEntity(
    @PrimaryKey val muteId: String,
    val platform: String,
    val normalizedHandle: String,
    val visibleHandle: String,
    val createdAtEpochMs: Long,
)

@Dao
interface SenderMuteDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(value: SenderMuteEntity): Long

    @Query("SELECT * FROM sender_mutes WHERE platform = :platform AND normalizedHandle = :normalizedHandle")
    suspend fun find(platform: String, normalizedHandle: String): SenderMuteEntity?

    @Query("SELECT * FROM sender_mutes ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<SenderMuteEntity>>

    @Query("DELETE FROM sender_mutes WHERE platform = :platform AND normalizedHandle = :normalizedHandle")
    suspend fun delete(platform: String, normalizedHandle: String): Int
}
