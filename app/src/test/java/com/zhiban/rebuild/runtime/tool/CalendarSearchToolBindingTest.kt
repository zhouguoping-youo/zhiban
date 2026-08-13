package com.zhiban.rebuild.runtime.tool

import com.zhiban.rebuild.data.agent.ScheduleDao
import com.zhiban.rebuild.data.agent.ScheduleEntity
import com.zhiban.rebuild.data.agent.ScheduleProjection
import com.zhiban.rebuild.data.calendar.ExternalCalendarConflict
import com.zhiban.rebuild.data.calendar.ExternalCalendarConflictSource
import com.zhiban.rebuild.runtime.provider.ProviderFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarSearchToolBindingTest {
    private val spec = RuntimeToolCatalog.production().requireRegistered("calendar.schedule.search")

    @Test fun readsRealDomainProjectionWithoutConfirmation() = runTest {
        val dao = FakeScheduleDao(listOf(ScheduleProjection("s1", "见客户", 1_000, 30, "A 公司")))
        val binding = CalendarSearchToolBinding(spec, dao)
        val result = binding.executeReadOnly(
            RuntimeToolCallRequest("call-1", spec.name, """{"fromEpochMs":0,"toEpochMs":2000}"""),
            context(),
        )
        assertEquals(0L to 2_000L, dao.lastRange)
        assertTrue(result.safeResultJson.contains("见客户"))
        assertTrue(result.safeResultJson.contains("\"count\":1"))
    }

    @Test fun rejectsUnknownFieldsAndOversizedRanges() = runTest {
        val binding = CalendarSearchToolBinding(spec, FakeScheduleDao(emptyList()))
        val unknown = runCatching {
            binding.executeReadOnly(
                RuntimeToolCallRequest("c", spec.name, """{"fromEpochMs":0,"toEpochMs":1,"sql":"x"}"""),
                context(),
            )
        }.exceptionOrNull()
        val range = runCatching {
            binding.executeReadOnly(
                RuntimeToolCallRequest("c", spec.name, """{"fromEpochMs":0,"toEpochMs":999999999999}"""),
                context(),
            )
        }.exceptionOrNull()
        assertTrue(unknown is ProviderFailure)
        assertTrue(range is ProviderFailure)
    }

    @Test fun `conflict tool includes device calendar events`() = runTest {
        val conflictSpec = RuntimeToolCatalog.production().requireRegistered("calendar.schedule.conflicts")
        val external = ExternalCalendarConflictSource { _, _, _, _ ->
            listOf(ExternalCalendarConflict("42:1000", "系统客户会议", 1_000, 3_600_000))
        }
        val binding = CalendarConflictToolBinding(conflictSpec, FakeScheduleDao(emptyList()), external)

        val result = binding.executeReadOnly(
            RuntimeToolCallRequest(
                "call-conflict",
                conflictSpec.name,
                """{"startAtEpochMs":1000,"durationMinutes":30}""",
            ),
            context(),
        )

        assertTrue(result.safeResultJson.contains("\"hasConflict\":true"))
        assertTrue(result.safeResultJson.contains("系统客户会议"))
        assertTrue(result.safeResultJson.contains("SYSTEM_CALENDAR"))
    }

    @Test fun `conflict tool does not duplicate an imported system event`() = runTest {
        val conflictSpec = RuntimeToolCatalog.production().requireRegistered("calendar.schedule.conflicts")
        val local = ScheduleProjection("system-calendar-42:1000", "系统客户会议", 1_000, 60, null)
        val external = ExternalCalendarConflictSource { _, _, _, _ ->
            listOf(ExternalCalendarConflict("42:1000", "系统客户会议", 1_000, 3_601_000))
        }
        val binding = CalendarConflictToolBinding(conflictSpec, FakeScheduleDao(listOf(local)), external)

        val result = binding.executeReadOnly(
            RuntimeToolCallRequest(
                "call-conflict",
                conflictSpec.name,
                """{"startAtEpochMs":1000,"durationMinutes":30}""",
            ),
            context(),
        )

        assertTrue(result.safeResultJson.contains("\"count\":1"))
        assertEquals(0, "SYSTEM_CALENDAR".toRegex().findAll(result.safeResultJson).count())
    }

    private fun context() = RuntimeToolRouteContext("run", "session", "attempt", "owner", 1, 1, 1)

    private class FakeScheduleDao(private val rows: List<ScheduleProjection>) : ScheduleDao {
        var lastRange: Pair<Long, Long>? = null
        override suspend fun insert(entity: ScheduleEntity) = Unit
        override suspend fun update(entity: ScheduleEntity): Int = 0
        override suspend fun findByRunId(runId: String) = emptyList<ScheduleEntity>()
        override fun observeRange(fromEpochMs: Long, toEpochMs: Long): Flow<List<ScheduleProjection>> = flowOf(rows)
        override suspend fun listRange(fromEpochMs: Long, toEpochMs: Long, limit: Int): List<ScheduleProjection> {
            lastRange = fromEpochMs to toEpochMs
            return rows.take(limit)
        }
        override suspend fun searchRange(query: String, fromEpochMs: Long, toEpochMs: Long, limit: Int): List<ScheduleProjection> = rows.filter { row ->
            row.startAtEpochMs in fromEpochMs..toEpochMs &&
                (query.isBlank() || row.title.contains(query) || row.note?.contains(query) == true)
        }.take(limit)
        override suspend fun findConflicts(startEpochMs: Long, endEpochMs: Long, excludeId: String?, limit: Int) = rows.filter {
            it.id != excludeId && it.startAtEpochMs < endEpochMs &&
                it.startAtEpochMs + it.durationMinutes * 60_000L > startEpochMs
        }.take(limit)
        override suspend fun findById(id: String): ScheduleEntity? = null
        override suspend fun updateCompletion(id: String, status: String, outcomeNote: String?, completedAtEpochMs: Long?, nowEpochMs: Long): Int = 0
        override suspend fun count(): Int = rows.size
        override suspend fun listPageForExport(limit: Int, offset: Int) = emptyList<ScheduleEntity>()
        override suspend fun deleteById(id: String): Int = 0
        override suspend fun countLegacyCrmDemo(): Int = 0
        override suspend fun deleteLegacyCrmDemo(): Int = 0
        override suspend fun migrateCrmLabel(): Int = 0
    }
}
