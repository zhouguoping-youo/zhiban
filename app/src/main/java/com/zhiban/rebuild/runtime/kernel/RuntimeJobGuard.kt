package com.zhiban.rebuild.runtime.kernel

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Prevents an unexpected runtime child failure from reaching Android's uncaught-exception handler. */
internal fun CoroutineScope.launchGuardedRuntimeJob(onFailure: suspend (Throwable) -> Unit, onFinally: () -> Unit, block: suspend () -> Unit): Job =
    launch(start = CoroutineStart.LAZY) {
        try {
            block()
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try {
                    onFailure(failure)
                } catch (containmentFailure: Throwable) {
                    Log.e(RUNTIME_GUARD_TAG, "RUNTIME_CONTAINMENT_FAILED:${containmentFailure.javaClass.simpleName}")
                }
            }
            if (failure is CancellationException) throw failure
        } finally {
            onFinally()
        }
    }

private const val RUNTIME_GUARD_TAG = "ZhiBanRuntime"
