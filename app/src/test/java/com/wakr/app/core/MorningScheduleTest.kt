package com.wakr.app.core

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class MorningScheduleTest {
    private val now = ZonedDateTime.parse("2026-11-21T21:50:00+05:30[Asia/Kolkata]")
    private val gym = Alarm(1L, LocalTime.of(6, 46), label = "Gym", days = setOf(DayOfWeek.MONDAY))

    @Test fun includesOnlyUpcomingLocalOccurrencesAndOneShotOnce() {
        val once = Alarm(2L, LocalTime.of(5, 0))
        val disabled = once.copy(id = 3L, enabled = false)
        val schedule = morningSchedule(listOf(gym, once, disabled), now)
        assertEquals(once, schedule.next?.alarm)
        assertEquals(listOf(once, gym), schedule.byDay.values.flatten().map { it.alarm })
        assertEquals(setOf(now.toLocalDate().plusDays(1), now.toLocalDate().plusDays(2)), schedule.byDay.keys)
    }

    @Test fun keepsNextEvenIfOutsideStripAndOmitsPastOrSkippedDates() {
        val afterWindow = gym.copy(days = setOf(now.dayOfWeek))
        val delayed = gym.copy(skippedOccurrenceDate = now.toLocalDate().plusDays(2))
        val schedule = morningSchedule(listOf(afterWindow, delayed), now)
        assertTrue(schedule.byDay.isEmpty())
        assertEquals(now.toLocalDate().plusDays(7), schedule.next?.at?.toLocalDate())
    }

    @Test fun respectsSnoozeAndLocalCalendarDaysAcrossDst() {
        val beforeDst = ZonedDateTime.parse("2026-10-31T06:00:00-04:00[America/New_York]")
        val until = beforeDst.plusHours(3)
        val snoozed = gym.copy(days = DayOfWeek.entries.toSet(),
            snoozedUntilMillis = until.toInstant().toEpochMilli())
        val schedule = morningSchedule(listOf(snoozed), beforeDst)
        assertEquals(until, schedule.next?.at)
        assertEquals(7, schedule.byDay.size)
        assertEquals(LocalTime.of(6, 46), schedule.byDay.getValue(beforeDst.toLocalDate().plusDays(1)).single().at.toLocalTime())
    }
}
