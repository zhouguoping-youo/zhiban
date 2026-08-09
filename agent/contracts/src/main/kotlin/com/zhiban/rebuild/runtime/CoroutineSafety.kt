package com.zhiban.rebuild.runtime

import kotlinx.coroutines.CancellationException

/** Result wrapper for suspend boundaries that must never convert coroutine cancellation into a product error. */
suspend inline fun <T> runSuspendCatching(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    Result.failure(failure)
}
