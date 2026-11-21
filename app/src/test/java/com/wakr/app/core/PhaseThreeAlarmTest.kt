package com.wakr.app.core

import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class PhaseThreeAlarmTest {
    private val at = 40_000L
    private val ring = Alarm(7L, LocalTime.NOON, ringingAt = at, wake = WakeOptions(maxSnoozes = 3),
        missions = listOf(AlarmMission(1, MissionType.MATH), AlarmMission(2, MissionType.TYPING)))

    @Test fun newAlarmsCannotSnoozeUntilALimitIsSelected() {
        val alarm = Alarm(10, LocalTime.NOON, ringingAt = at)
        assertFalse(alarm.canSnooze())
        assertSame(alarm, alarm.finishUserOccurrence(alarm.id, at, at + 60_000))
        assertTrue(alarm.copy(wake = WakeOptions(maxSnoozes = null), snoozeCount = 50).canSnooze())
    }

    @Test fun alarmCannotDismissBeforeAllMissionsIncludingBeforeFirstUnlock() {
        assertSame(ring, ring.finishUserOccurrence(7, at, null))
        val partlySolved = ring.copy(missionIndex = 1)
        assertSame(partlySolved, partlySolved.finishUserOccurrence(7, at, null))
        val private = ring.copy(missions = emptyList(), privateMissionCount = 2)
        assertSame(private, private.finishUserOccurrence(7, at, null))
        val complete = ring.copy(missionIndex = 2)
        assertNull(complete.finishUserOccurrence(7, at, null)!!.ringingAt)
    }

    @Test fun aConfiguredMissionRequiresCompletionAndAnEmptyAlarmDismissesDirectly() {
        assertSame(ring, ring.finishUserOccurrence(7, at, null))
        val withoutMissions = ring.copy(missions = emptyList())
        assertNull(withoutMissions.finishUserOccurrence(7, at, null)!!.ringingAt)
    }

    @Test fun finalMissionDismissesAtomicallyAndRejectsOlderCallbacks() {
        assertSame(ring, ring.finishAfterMission(7, at, 0))
        val final = ring.copy(missionIndex = 1)
        assertSame(final, final.finishAfterMission(7, at - 1, 1))
        assertSame(final, final.finishAfterMission(7, at, 0))
        val missingPrivateMission = final.copy(privateMissionCount = 3)
        assertSame(missingPrivateMission, missingPrivateMission.finishAfterMission(7, at, 1))
        val dismissed = final.finishAfterMission(7, at, 1)!!
        assertNull(dismissed.ringingAt)
        assertSame(dismissed, dismissed.finishAfterMission(7, at, 1))
        val temporary = final.copy(wake = final.wake.copy(deleteAfterDismiss = true))
        assertNull(temporary.finishAfterMission(7, at, 1))
    }

    @Test fun emergencyDismissalRequiresOptInAndMatchesTheOccurrence() {
        assertSame(ring, ring.finishEmergencyOccurrence(7, at))
        val enabled = ring.copy(wake = ring.wake.copy(emergencyStopEnabled = true))
        assertSame(enabled, enabled.finishEmergencyOccurrence(7, at - 1))
        assertNull(enabled.finishEmergencyOccurrence(7, at)!!.ringingAt)
        val withoutMissions = enabled.copy(missions = emptyList())
        assertNull(withoutMissions.finishEmergencyOccurrence(7, at)!!.ringingAt)
        val temporary = enabled.copy(wake = enabled.wake.copy(deleteAfterDismiss = true))
        assertNull(temporary.finishEmergencyOccurrence(7, at))
    }

    @Test fun allowedSnoozePreservesMissionProgressAndIncrementsCount() {
        val second = ring.copy(missionIndex = 1, snoozeCount = 2)
        val paused = second.finishUserOccurrence(7, at, at + 60_000)!!
        assertEquals(3, paused.snoozeCount)
        assertEquals(1, paused.missionIndex)
        val exhausted = paused.copy(ringingAt = at)
        assertSame(exhausted, exhausted.finishUserOccurrence(7, at, at + 60_000))
        val noSnooze = ring.copy(wake = WakeOptions())
        assertSame(noSnooze, noSnooze.finishUserOccurrence(7, at, at + 60_000))
    }

    @Test fun preRingWindowStartsAtBoundary() {
        val alarm = Alarm(10L, LocalTime.of(7, 0), wake = WakeOptions(lockBeforeRingMin = 30))
        assertFalse(alarm.isPreRingProtected(ZonedDateTime.parse("2026-11-21T06:29:59Z")))
        assertTrue(alarm.isPreRingProtected(ZonedDateTime.parse("2026-11-21T06:30:00Z")))
        assertFalse(alarm.copy(enabled = false).isPreRingProtected(ZonedDateTime.parse("2026-11-21T06:30:00Z")))
    }

    @Test fun preRingProtectionSupportsWholeHours() {
        val alarm = Alarm(12, LocalTime.of(7, 0), wake = WakeOptions(lockBeforeRingMin = 3 * 60))
        assertFalse(alarm.isPreRingProtected(ZonedDateTime.parse("2026-11-21T03:59:59Z")))
        assertTrue(alarm.isPreRingProtected(ZonedDateTime.parse("2026-11-21T04:00:00Z")))
    }

    @Test fun typingAllowsEditsAndRejectsMultipleInsertedCharacters() {
        assertTrue(typingStepAllowed("Good day", "Good days"))
        assertTrue(typingStepAllowed("Good day", "Good"))
        assertTrue(typingStepAllowed("God day", "Good day"))
        assertFalse(typingStepAllowed("", "Good day"))
        assertFalse(typingStepAllowed("Good day", "Good morning"))
    }

    @Test fun slidingPuzzleIsScrambledAndReachable() {
        for (seed in 0..30) {
            val tiles = shuffledTiles(Random(seed))
            val numbers = tiles.filter { it != 0 }
            val inversions = numbers.indices.sumOf { first -> numbers.drop(first + 1).count { it < numbers[first] } }
            assertEquals((0..8).toSet(), tiles.toSet())
            assertEquals(0, inversions % 2)
            assertNotEquals((1..8).toList() + 0, tiles)
        }
    }
}
