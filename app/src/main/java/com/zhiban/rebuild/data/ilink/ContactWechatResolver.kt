package com.zhiban.rebuild.data.ilink

import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.ContactPlatformIdentityEntity
import com.zhiban.rebuild.runtime.tool.sha256
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of resolving an Agent-supplied recipient name to a sendable iLink target. */
sealed interface WechatRecipientResolution {
    /** A contact with a known iLink `userId`; safe to send. */
    data class Resolved(val contact: ContactEntity, val userId: String) : WechatRecipientResolution

    /** No active contact matches the name. */
    data object ContactNotFound : WechatRecipientResolution

    /** Contact exists but has no learned iLink `userId` yet (they have not messaged the bot). */
    data class NoWechatLink(val contact: ContactEntity) : WechatRecipientResolution
}

/**
 * Maps 知伴 contacts to WeChat iLink `userId`s (`xxx@im.wechat`) and back, using the
 * `contact_platform_identities.platformUserId` slot. A contact only gains a `userId` once they have
 * messaged the bound bot (captured via `getupdates`); until then they resolve to
 * [WechatRecipientResolution.NoWechatLink] and the caller falls back to a manual-send draft.
 */
@Singleton
internal class ContactWechatResolver @Inject constructor(private val database: AgentDatabase) {
    /** Resolve a recipient display name to a sendable iLink `userId`. */
    suspend fun resolveUserId(recipientName: String): WechatRecipientResolution {
        val normalized = recipientName.trim().lowercase()
        if (normalized.isEmpty()) return WechatRecipientResolution.ContactNotFound
        val contact = database.contactDao().findByNormalizedName(normalized)
            ?: database.contactIdentityDao().findContactByAlias(normalized)
            ?: return WechatRecipientResolution.ContactNotFound
        val userId = database.contactIdentityDao().platformIdentities(contact.contactId)
            .firstOrNull { it.platform == PLATFORM_WECHAT && !it.platformUserId.isNullOrBlank() }
            ?.platformUserId
        return if (userId.isNullOrBlank()) {
            WechatRecipientResolution.NoWechatLink(contact)
        } else {
            WechatRecipientResolution.Resolved(contact, userId)
        }
    }

    /** Reverse lookup for inbound messages: which contact owns this iLink `userId`? */
    suspend fun contactForUserId(userId: String): ContactEntity? = database.contactIdentityDao().findContactByPlatformUserId(PLATFORM_WECHAT, userId)

    /**
     * Record the iLink `userId` observed for [contactId] (learned from an inbound message). Stored
     * as a dedicated iLink-sourced identity row so it never overwrites a human-entered WeChat ID.
     * Idempotent: re-learning the same id for the same contact is a no-op replace.
     */
    suspend fun learnUserId(contactId: String, userId: String, nowEpochMs: Long) {
        if (contactId.isBlank() || userId.isBlank()) return
        val identity = ContactPlatformIdentityEntity(
            identityId = "wpi-${sha256("$contactId|$PLATFORM_WECHAT|$userId").take(ID_DIGEST_LENGTH)}",
            contactId = contactId,
            platform = PLATFORM_WECHAT,
            handle = userId,
            normalizedHandle = userId.lowercase(),
            platformUserId = userId,
            source = SOURCE_ILINK,
            userConfirmed = false,
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
        )
        database.contactIdentityDao().upsertPlatformIdentity(identity)
    }

    companion object {
        const val PLATFORM_WECHAT = "WECHAT"
        const val SOURCE_ILINK = "ilink"
        private const val ID_DIGEST_LENGTH = 24
    }
}
