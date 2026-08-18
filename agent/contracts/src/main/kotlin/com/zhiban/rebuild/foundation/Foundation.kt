package com.zhiban.rebuild.foundation

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/** Result wrapper for suspend boundaries that must never convert coroutine cancellation into a product error. */
suspend inline fun <T> runSuspendCatching(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    Result.failure(failure)
}

fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

fun changeIdFor(idempotencyKey: String): String = "change-${sha256(idempotencyKey).take(24)}"

enum class Sensitivity { PUBLIC, PERSONAL, SENSITIVE }

enum class MemoryScope { WORKING, SESSION, PERSON, GLOBAL }

enum class MemoryCandidateState { PENDING, APPROVED, REJECTED, CONSUMED, DELETED }
