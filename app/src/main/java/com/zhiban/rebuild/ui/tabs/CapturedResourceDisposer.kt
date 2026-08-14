package com.zhiban.rebuild.ui.tabs

import kotlinx.coroutines.CancellationException

/** Captures the resource owned by one effect instance instead of reading later mutable state. */
internal fun <T> capturedResourceDisposer(resource: T?, dispose: (T) -> Unit): () -> Unit = {
    resource?.let(dispose)
}

/** Releases ownership when initialization fails, while preserving coroutine cancellation. */
internal fun <T> acquireStartedResource(create: () -> T, start: (T) -> Unit, release: (T) -> Unit): Result<T> {
    val resource = try {
        create()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        return Result.failure(failure)
    }
    return try {
        start(resource)
        Result.success(resource)
    } catch (cancelled: CancellationException) {
        releaseAfterFailedStart(resource, release, cancelled)
        throw cancelled
    } catch (failure: Throwable) {
        releaseAfterFailedStart(resource, release, failure)
        Result.failure(failure)
    }
}

private fun <T> releaseAfterFailedStart(resource: T, release: (T) -> Unit, originalFailure: Throwable) {
    try {
        release(resource)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (releaseFailure: Throwable) {
        originalFailure.addSuppressed(releaseFailure)
    }
}
