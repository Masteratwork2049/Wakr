package com.wakr.app.core

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class PhaseTwoAlarmTest {
    private val now = ZonedDateTime.parse("2026-03-07T18:00Z")
    private val alarm = Alarm(7, LocalTime.of(2, 30))

    @Test fun duplicatePreservesSwitchStateAndClearsOccurrence() {
        val original = alarm.copy(
            enabled = false,
            createdAt = 10L,
            skippedOccurrenceDate = now.toLocalDate(),
            ringingAt = 20L,
            snoozedUntilMillis = 30L,
            missedRetries = 2,
        )
        val duplicate = original.asDuplicate()
        assertFalse(duplicate.enabled)
        assertTrue(original.copy(enabled = true).asDuplicate().enabled)
        assertTrue(original.copy(enabled = true, pausedByGroup = true).asDuplicate().pausedByGroup)
        assertEquals(0L, duplicate.id)
        assertEquals(original.time, duplicate.time)
        assertNull(duplicate.createdAt)
        assertNull(duplicate.skippedOccurrenceDate)
        assertNull(duplicate.ringingAt)
        assertNull(duplicate.snoozedUntilMillis)
        assertEquals(0, duplicate.missedRetries)
    }

    @Test fun fixedDateInPinnedZoneAcrossSpringGap() {
        val fixed = alarm.copy(wake = WakeOptions(timeZoneId = "America/New_York",
            scheduledDateEpochDay = LocalDate.of(2026, 3, 8).toEpochDay()))
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), fixed.nextFire(now)?.toInstant())
        assertNull(fixed.nextFire(now.plusDays(2)))
    }

    @Test fun tomorrowUsesOriginalTimeAfterGap() {
        val daily = alarm.copy(days = DayOfWeek.entries.toSet(), wake = WakeOptions(timeZoneId = "America/New_York"))
        val shifted = daily.nextFire(now)!!
        assertEquals(Instant.parse("2026-03-09T06:30:00Z"), daily.nextFire(shifted)?.toInstant())
    }

    @Test fun relativeAlarmKeepsExactInstantAcrossFallBackAndTravel() {
        val before = ZonedDateTime.parse("2026-11-01T01:50:15-04:00[America/New_York]")
        val target = before.plusMinutes(20).toInstant()
        val quick = alarm.copy(wake = WakeOptions(relativeTriggerMillis = target.toEpochMilli()))
        assertEquals(target, quick.nextFire(before.withZoneSameInstant(ZoneId.of("Asia/Kolkata")))?.toInstant())
        assertNull(quick.nextFire(target.atZone(ZoneOffset.UTC)))
    }

    @Test fun longPauseResumesOnSelectedWeekday() {
        val date = LocalDate.of(2026, 12, 1)
        val weekly = alarm.copy(days = setOf(DayOfWeek.FRIDAY), wake = WakeOptions(pausedUntilEpochDay = date.toEpochDay()))
        assertEquals(LocalDate.of(2026, 12, 4), weekly.nextFire(now)?.toLocalDate())
        assertNull(weekly.copy(pausedByGroup = true).nextFire(now))
    }

    @Test fun weeklyAlarmSkipsNextWeekWhenTodaysTimeHasPassed() {
        val weekly = alarm.copy(days = setOf(now.dayOfWeek), skippedOccurrenceDate = now.toLocalDate().plusWeeks(1))
        assertEquals(now.toLocalDate().plusWeeks(2), weekly.nextFire(now)?.toLocalDate())
    }

    @Test fun snoozedTemporaryAlarmSurvivesUntilDismissalOfNewOccurrence() {
        val ringing = alarm.copy(enabled = false, ringingAt = 100, wake = WakeOptions(deleteAfterDismiss = true))
        val snoozed = ringing.finishOccurrence(7, 100, 200)!!
        assertTrue(snoozed.enabled)
        assertEquals(200L, snoozed.snoozedUntilMillis)
        assertSame(snoozed, snoozed.finishOccurrence(7, 100, null))
        val again = snoozed.copy(ringingAt = 200, snoozedUntilMillis = null, enabled = false)
        assertSame(again, again.finishOccurrence(8, 200, null))
        assertNull(again.finishOccurrence(7, 200, null))
    }

    @Test fun missedAlarmRetriesStopAtLimitWithoutAutoDeleting() {
        val ringing = alarm.copy(enabled = false, ringingAt = 1, wake = WakeOptions(deleteAfterDismiss = true, repeatMissedCount = 1))
        val retry = ringing.missOccurrence(1, 10)
        assertEquals(300010L, retry.snoozedUntilMillis)
        val last = retry.copy(enabled = false, ringingAt = retry.snoozedUntilMillis, snoozedUntilMillis = null)
        assertFalse(last.missOccurrence(300010, 40).enabled)
        assertSame(last, last.missOccurrence(1, 40))
    }
}
