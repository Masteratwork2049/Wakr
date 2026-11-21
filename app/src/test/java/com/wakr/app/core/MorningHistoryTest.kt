package com.wakr.app.core

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class MorningHistoryTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 11, 21)

    private fun entry(daysAgo: Long, clean: Boolean = true, id: Long = daysAgo): WakeUpLog {
        val first = today.minusDays(daysAgo).atTime(0, 10).atZone(zone).toInstant().toEpochMilli()
        return WakeUpLog(id, id, "Alarm $id", first, first, first + 30_000, 0, null, clean, WakeOutcome.DISMISSED)
    }

    @Test fun streakUsesLocalDatesAndConsidersEveryAlarmOnTheDay() {
        val rows = listOf(entry(11), entry(10), entry(9), entry(2), entry(1))
        val morning = morningHistory(rows, today, zone)
        assertEquals(2, morning.currentStreak)
        assertEquals(3, morning.bestStreak)
        assertEquals(listOf(1L, 2L), morning.recent.take(2).map { it.id })
        assertEquals(today.minusDays(6), morning.days.first().date)
        assertEquals(2, morning.week.size)
        assertTrue(morning.today.entries.isEmpty())

        val interrupted = morningHistory(rows + entry(1, clean = false, id = 101), today, zone)
        assertEquals(0, interrupted.currentStreak)
        assertEquals(3, interrupted.bestStreak)
    }

    @Test fun weeklyMetricsUseKnownDurationsAndTrackLegacyDataSeparately() {
        val legacy = entry(2).copy(stillAwakePassed = true)
        val tracked = entry(1, clean = false).let {
            it.copy(dismissedAt = it.firstRingAt + 90_000, snoozeCount = 2, missionMuteCount = 3,
                completedWakeChecks = 3, passedWakeChecks = 2, outcome = WakeOutcome.STILL_AWAKE_FAILED)
        }
        val emergency = entry(1, clean = false, id = 100).copy(outcome = WakeOutcome.EMERGENCY_STOP)
        val old = entry(7).copy(missionMuteCount = 10)
        val stats = morningHistory(listOf(legacy, tracked, emergency, old), today, zone)
        assertEquals(33, stats.cleanPercent)
        assertEquals(60_000L, stats.averageDismissalMillis)
        assertEquals(2.0 / 3.0, stats.averageSnoozes!!, .0001)
        assertEquals(3L, stats.missionMutes)
        assertTrue(stats.hasUntrackedMutes)
        assertEquals(3L, stats.completedChecks)
        assertEquals(2L, stats.passedChecks)
        assertEquals(1, stats.legacyCheckResults)
        assertEquals(1, stats.legacyCheckPasses)
    }

    @Test fun emptyHistoryLeavesMetricsUnavailable() {
        val stats = morningHistory(emptyList(), today, zone)
        assertNull(stats.cleanPercent)
        assertNull(stats.averageDismissalMillis)
        assertNull(stats.averageSnoozes)
        assertNull(stats.missionMutes)
        assertEquals(0, stats.currentStreak)
        assertEquals(0, stats.bestStreak)
    }

    @Test fun doubleZeroFillsExactlyTwoAvailableTimerSlots() {
        assertEquals("100", appendTimerDigits("1", "00"))
        assertEquals("123400", appendTimerDigits("1234", "00"))
        assertEquals("12345", appendTimerDigits("12345", "00"))
        assertEquals("", appendTimerDigits("", "00"))
        assertEquals("123450", appendTimerDigits("12345", "0"))
    }
}
