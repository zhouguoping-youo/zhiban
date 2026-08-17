package com.zhiban.rebuild.data.ilink

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Caches the per-conversation `context_token` needed to reply to an inbound iLink message. A reply
 * must echo the token verbatim or the server silently drops it; the token is only valid for roughly
 * two minutes, so reads past [TTL_MS] return null and the caller refreshes via `getupdates` first.
 *
 * In-memory only (the tokens are too short-lived to be worth persisting), keyed by the other
 * party's `userId`.
 */
@Singleton
class ContextTokenCache internal constructor(private val clock: () -> Long) {
    @Inject
    constructor() : this(System::currentTimeMillis)

    private val cache = mutableMapOf<String, Entry>()

    private class Entry(val token: String, val receivedAtEpochMs: Long)

    @Synchronized
    fun put(userId: String, token: String) {
        if (userId.isBlank() || token.isBlank()) return
        cache[userId] = Entry(token, clock())
    }

    /** Fresh token for [userId], or null when absent/expired (caller should refresh via getupdates). */
    @Synchronized
    fun get(userId: String): String? {
        val entry = cache[userId] ?: return null
        if (clock() - entry.receivedAtEpochMs > TTL_MS) {
            cache.remove(userId)
            return null
        }
        return entry.token
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }

    private companion object {
        // ~2 minute server validity, minus a safety margin.
        const val TTL_MS = 110_000L
    }
}
