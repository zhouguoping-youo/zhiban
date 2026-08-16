package com.zhiban.rebuild.data.calendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SystemCalendarEvent(
    val sourceId: String,
    val title: String,
    val description: String?,
    val location: String?,
    val startAtEpochMs: Long,
    val endAtEpochMs: Long?,
    val calendarName: String?,
    val calendarId: Long? = null,
)

data class SystemCalendarReadResult(val events: List<SystemCalendarEvent>, val errorMessage: String? = null)

@Singleton
class SystemCalendarReader @Inject constructor(@ApplicationContext private val context: Context) : ExternalCalendarConflictSource {
    fun readRange(from: LocalDate, untilExclusive: LocalDate, limit: Int = 100): SystemCalendarReadResult {
        val zone = ZoneId.systemDefault()
        val fromEpoch = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val untilEpoch = untilExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
        return queryEpochRange(fromEpoch, untilEpoch, limit)
    }

    override suspend fun findConflicts(startAtEpochMs: Long, endAtEpochMs: Long, excludeScheduleId: String?, limit: Int): List<ExternalCalendarConflict> =
        withContext(Dispatchers.IO) {
            if (startAtEpochMs < 0 || endAtEpochMs <= startAtEpochMs) return@withContext emptyList()
            val excludedSourceId = excludeScheduleId
                ?.takeIf { it.startsWith(SYSTEM_IMPORT_ID_PREFIX) }
                ?.removePrefix(SYSTEM_IMPORT_ID_PREFIX)
            queryEpochRange(startAtEpochMs, endAtEpochMs, limit)
                .events
                .asSequence()
                .filter { event -> event.sourceId != excludedSourceId }
                .filter { event ->
                    val eventEnd = event.endAtEpochMs ?: (event.startAtEpochMs + DEFAULT_EVENT_DURATION_MS)
                    event.startAtEpochMs < endAtEpochMs && eventEnd > startAtEpochMs
                }
                .map { event ->
                    ExternalCalendarConflict(
                        sourceId = event.sourceId,
                        title = event.title,
                        startAtEpochMs = event.startAtEpochMs,
                        endAtEpochMs = event.endAtEpochMs ?: (event.startAtEpochMs + DEFAULT_EVENT_DURATION_MS),
                    )
                }
                .take(limit.coerceIn(1, 50))
                .toList()
        }

    private fun queryEpochRange(fromEpoch: Long, untilEpoch: Long, limit: Int): SystemCalendarReadResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return SystemCalendarReadResult(emptyList(), "尚未获得系统日历读取权限")
        }
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(fromEpoch.toString())
            .appendPath(untilEpoch.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.CALENDAR_ID,
        )
        return runCatching {
            val values = context.contentResolver.query(
                uri,
                projection,
                ACTIVE_INSTANCE_SELECTION,
                arrayOf(CalendarContract.Events.STATUS_CANCELED.toString()),
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext() && size < limit.coerceIn(1, 500)) {
                        val eventId = cursor.getLong(0)
                        val start = cursor.getLong(4)
                        add(
                            SystemCalendarEvent(
                                sourceId = "$eventId:$start",
                                title = cursor.getString(1).orEmpty().trim().ifBlank { "未命名日程" },
                                description = cursor.getString(2)?.trim()?.takeIf(String::isNotEmpty),
                                location = cursor.getString(3)?.trim()?.takeIf(String::isNotEmpty),
                                startAtEpochMs = start,
                                endAtEpochMs = cursor.getLong(5).takeIf { it > start },
                                calendarName = cursor.getString(6)?.trim()?.takeIf(String::isNotEmpty),
                                calendarId = cursor.getLong(7),
                            ),
                        )
                    }
                }
            }.orEmpty()
            SystemCalendarReadResult(values.distinctBy(SystemCalendarEvent::sourceId))
        }.getOrElse(::queryFailureResult)
    }

    private companion object {
        const val SYSTEM_IMPORT_ID_PREFIX = "system-calendar-"
        const val DEFAULT_EVENT_DURATION_MS = 60 * 60_000L
    }
}

internal val ACTIVE_INSTANCE_SELECTION =
    "${CalendarContract.Instances.VISIBLE} = 1 AND " +
        "(${CalendarContract.Instances.STATUS} IS NULL OR ${CalendarContract.Instances.STATUS} != ?)"

// R15: never swallow cancellation — only genuine query failures become an error result.
internal fun queryFailureResult(failure: Throwable): SystemCalendarReadResult {
    if (failure is CancellationException) throw failure
    return SystemCalendarReadResult(emptyList(), failure.message ?: "读取系统日历失败")
}
