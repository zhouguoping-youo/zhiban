package com.zhiban.rebuild.ui.tabs

/** Captures the resource owned by one effect instance instead of reading later mutable state. */
internal fun <T> capturedResourceDisposer(resource: T?, dispose: (T) -> Unit): () -> Unit = {
    resource?.let(dispose)
}
