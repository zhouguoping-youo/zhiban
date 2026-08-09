package com.zhiban.rebuild.data.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.BaseColumns
import android.provider.CallLog
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class CallLogAccessStatus {
    NOT_GRANTED,
    AVAILABLE,
    RESTRICTED,
    UNAVAILABLE,
}

/**
 * Verifies effective CallLog access without reading or exposing phone numbers.
 * A runtime permission flag alone is insufficient for a hard-restricted permission.
 */
object CallLogAccessProbe {
    suspend fun probe(context: Context): CallLogAccessStatus = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext CallLogAccessStatus.NOT_GRANTED
        }
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(BaseColumns._ID),
                null,
                null,
                "${CallLog.Calls.DATE} DESC",
            )?.use { cursor ->
                cursor.moveToFirst()
            } ?: return@withContext CallLogAccessStatus.UNAVAILABLE
            CallLogAccessStatus.AVAILABLE
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            CallLogAccessStatus.RESTRICTED
        } catch (_: RuntimeException) {
            CallLogAccessStatus.UNAVAILABLE
        }
    }
}
