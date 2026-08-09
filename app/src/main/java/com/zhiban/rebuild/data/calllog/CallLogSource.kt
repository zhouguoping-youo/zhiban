package com.zhiban.rebuild.data.calllog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SystemCallLogRow(
    val providerRowId: Long,
    val number: String?,
    val numberPresentation: Int,
    val systemType: Int,
    val startedAtEpochMs: Long,
    val durationSeconds: Long,
    val lastModifiedEpochMs: Long,
    val phoneAccountId: String?,
    val phoneAccountComponentName: String?,
)

interface CallLogSource {
    suspend fun readChangedSince(lastModifiedEpochMs: Long, limit: Int): List<SystemCallLogRow>
}

@Singleton
class AndroidCallLogSource @Inject constructor(@ApplicationContext private val context: Context) : CallLogSource {
    override suspend fun readChangedSince(lastModifiedEpochMs: Long, limit: Int): List<SystemCallLogRow> = withContext(Dispatchers.IO) {
        require(limit in 1..2_000)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext emptyList()
        }
        val uri = CallLog.Calls.CONTENT_URI.buildUpon()
            .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, limit.toString())
            .build()
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.NUMBER_PRESENTATION,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.LAST_MODIFIED,
            CallLog.Calls.PHONE_ACCOUNT_ID,
            CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME,
        )
        context.contentResolver.query(
            uri,
            projection,
            "${CallLog.Calls.LAST_MODIFIED} >= ?",
            arrayOf(lastModifiedEpochMs.coerceAtLeast(0L).toString()),
            "${CallLog.Calls.LAST_MODIFIED} ASC, ${CallLog.Calls._ID} ASC",
        )?.use { cursor ->
            buildList {
                val id = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
                val number = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val presentation = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER_PRESENTATION)
                val type = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val date = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val duration = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val modified = cursor.getColumnIndexOrThrow(CallLog.Calls.LAST_MODIFIED)
                val accountId = cursor.getColumnIndexOrThrow(CallLog.Calls.PHONE_ACCOUNT_ID)
                val accountComponent = cursor.getColumnIndexOrThrow(CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME)
                while (cursor.moveToNext()) {
                    add(
                        SystemCallLogRow(
                            providerRowId = cursor.getLong(id),
                            number = cursor.nullableString(number),
                            numberPresentation = cursor.getInt(presentation),
                            systemType = cursor.getInt(type),
                            startedAtEpochMs = cursor.getLong(date),
                            durationSeconds = cursor.getLong(duration).coerceAtLeast(0L),
                            lastModifiedEpochMs = cursor.getLong(modified),
                            phoneAccountId = cursor.nullableString(accountId),
                            phoneAccountComponentName = cursor.nullableString(accountComponent),
                        ),
                    )
                }
            }
        }.orEmpty()
    }
}

internal fun callDirection(systemType: Int): String = when (systemType) {
    CallLog.Calls.INCOMING_TYPE -> "INCOMING"
    CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
    CallLog.Calls.MISSED_TYPE -> "MISSED"
    CallLog.Calls.REJECTED_TYPE -> "REJECTED"
    CallLog.Calls.BLOCKED_TYPE -> "BLOCKED"
    CallLog.Calls.VOICEMAIL_TYPE -> "VOICEMAIL"
    CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "ANSWERED_EXTERNALLY"
    else -> "OTHER"
}

private fun android.database.Cursor.nullableString(index: Int): String? =
    if (index < 0 || isNull(index)) null else getString(index)?.trim()?.takeIf(String::isNotEmpty)
