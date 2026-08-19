package com.zhiban.rebuild.data.notification

import com.zhiban.rebuild.data.contact.ContactPlatformIdentityEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 备注漂移提示载荷。微信等平台的 senderName 是"备注名优先"的显示名且没有稳定外部 ID,
 * 用户改备注会让同一发送者的名字漂移。第三级归一化名命中联系人的同时,若该联系人名下
 * 已有同平台的 userConfirmed 平台身份、且归一化 handle 与当前不同,则打此标记——
 * 只做提示,写身份仍须用户显式确认(红线:禁止系统自行判断后写联系人/身份)。
 */
@Serializable
data class IdentityDriftInfo(val platform: String, val newHandle: String, val oldHandle: String, val oldIdentityId: String) {
    fun toJson(): String = Json.encodeToString(serializer(), this)

    companion object {
        fun fromJson(raw: String): IdentityDriftInfo? = try {
            Json.decodeFromString(serializer(), raw)
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * 纯函数:检测"同联系人、同平台、userConfirmed、归一化 handle 与当前不同"的旧平台身份。
 * 多个旧身份时取最近更新的一条用于提示;没有命中返回 null(不提示)。
 */
internal fun detectIdentityDrift(
    platform: String,
    currentNormalizedHandle: String,
    confirmedIdentities: List<ContactPlatformIdentityEntity>,
): IdentityDriftInfo? {
    val oldIdentity = confirmedIdentities
        .filter { it.platform == platform && it.userConfirmed && it.normalizedHandle != currentNormalizedHandle }
        .maxByOrNull(ContactPlatformIdentityEntity::updatedAtEpochMs)
        ?: return null
    return IdentityDriftInfo(
        platform = platform,
        newHandle = currentNormalizedHandle,
        oldHandle = oldIdentity.handle,
        oldIdentityId = oldIdentity.identityId,
    )
}
