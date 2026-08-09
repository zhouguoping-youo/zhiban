package com.zhiban.rebuild.data.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reproduces the P0 "日历 UI 不显示已创建日程" defect: a schedule that the tool
 * executor persists (and that calendar.schedule.search/listRange can read back)
 * must also be visible to the calendar UI, which reads via observeRange over the
 * window [today.startOfDay, tomorrow.startOfDay - 1].
 */
@RunWith(AndroidJUnit4::class)
class ScheduleObserveRangeReproTest {

    private lateinit var database: AgentDatabase

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = database.close()

    private fun schedule(id: String, title: String, startAtEpochMs: Long) = ScheduleEntity(
        id = id,
        title = title,
        startAtEpochMs = startAtEpochMs,
        durationMinutes = 60,
        note = null,
        createdByRunId = null,
        createdAtEpochMs = startAtEpochMs,
        updatedAtEpochMs = startAtEpochMs,
        reminderMinutesBefore = 10,
    )

    @Test fun insertedTodayScheduleIsVisibleToObserveRangeAndListRange() = runBlocking {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val from = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        // "今天 16:00" — the same wall-clock the tool resolved for 写周报.
        val fourPm = today.atTime(16, 0).atZone(zone).toInstant().toEpochMilli()
        database.scheduleDao().insert(schedule("schedule-today-16", "写周报", fourPm))

        val observed = database.scheduleDao().observeRange(from, to).first()
        val listed = database.scheduleDao().listRange(from, to, 20)

        assertTrue("observeRange must see today's schedule", observed.any { it.id == "schedule-today-16" })
        assertTrue("listRange must see today's schedule", listed.any { it.id == "schedule-today-16" })
        assertEquals(observed.map { it.id }, listed.map { it.id })
    }

    @Test fun observeRangeEmitsAfterInsertOnSameInstance() = runBlocking {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val from = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val fourPm = today.atTime(16, 0).atZone(zone).toInstant().toEpochMilli()

        // Empty before insert.
        assertTrue(database.scheduleDao().observeRange(from, to).first().isEmpty())
        database.scheduleDao().insert(schedule("schedule-today-16", "写周报", fourPm))
        // A fresh collection must observe the newly inserted row.
        val after = database.scheduleDao().observeRange(from, to).first()
        assertEquals(listOf("schedule-today-16"), after.map { it.id })
    }
}
