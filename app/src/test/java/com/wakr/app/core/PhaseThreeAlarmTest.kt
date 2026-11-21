package com.wakr.app.core

import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class PhaseThreeAlarmTest {
    private val normal = WakeOptions().withDifficulty(AlarmDifficulty.NORMAL)
    private val hard = WakeOptions().withDifficulty(AlarmDifficulty.HARD)
    private val at = 40_000L
    private val ring = Alarm(7L, LocalTime.NOON, ringingAt = at, wake = normal,
        missions = listOf(AlarmMission(1, MissionType.MATH), AlarmMission(2, MissionType.TYPING)))

    @Test fun presetsSetTheirLimits() {
        assertEquals(3, normal.maxSnoozes)
        assertEquals(0, normal.silenceAfterMin)
        assertFalse(hard.snoozeEnabled)
        assertEquals(1, hard.stillAwakeChecksCount)
        val strict = hard.withDifficulty(AlarmDifficulty.NO_MERCY)
        assertEquals(2, strict.stillAwakeChecksCount)
        assertEquals(60, strict.lockBeforeRingMin)
        assertEquals(5, strict.rebootSurvivalMax)
        val easy = strict.withDifficulty(AlarmDifficulty.EASY)
        assertTrue(easy.snoozeEnabled)
        assertNull(easy.maxSnoozes)
        assertEquals(0, easy.lockBeforeRingMin)
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

    @Test fun missionRequirementIsIndependentOfOtherDifficultySettings() {
        val optional = ring.copy(wake = hard.copy(requireMissions = false))
        assertFalse(optional.wake.needsMissions)
        assertFalse(optional.canSnooze())
        assertNull(optional.finishUserOccurrence(7, at, null)!!.ringingAt)

        val required = ring.copy(wake = WakeOptions(requireMissions = true))
        assertTrue(required.wake.needsMissions)
        assertTrue(required.canSnooze())
        assertSame(required, required.finishUserOccurrence(7, at, null))
    }

    @Test fun legacyCustomAlarmsOnlyRunMissionsWhenConfigured() {
        val legacy = ring.copy(wake = WakeOptions(difficulty = AlarmDifficulty.CUSTOM))
        assertFalse(legacy.wake.needsMissions)
        assertSame(legacy, legacy.finishUserOccurrence(7, at, null))
        val withoutMissions = legacy.copy(missions = emptyList())
        assertNull(withoutMissions.finishUserOccurrence(7, at, null)!!.ringingAt)
    }

    @Test fun emergencyOnlyStopsTheMatchingUnfinishedMission() {
        assertSame(ring, ring.finishEmergencyOccurrence(7, at - 1))
        val completed = ring.copy(missionIndex = ring.missions.size)
        assertSame(completed, completed.finishEmergencyOccurrence(7, at))
        assertNull(ring.finishEmergencyOccurrence(7, at)!!.ringingAt)
        val temporary = ring.copy(wake = ring.wake.copy(deleteAfterDismiss = true))
        assertNull(temporary.finishEmergencyOccurrence(7, at))
    }

    @Test fun allowedSnoozePreservesMissionProgressAndIncrementsCount() {
        val second = ring.copy(missionIndex = 1, snoozeCount = 2)
        val paused = second.finishUserOccurrence(7, at, at + 60_000)!!
        assertEquals(3, paused.snoozeCount)
        assertEquals(1, paused.missionIndex)
        val exhausted = paused.copy(ringingAt = at)
        assertSame(exhausted, exhausted.finishUserOccurrence(7, at, at + 60_000))
        val hardRing = ring.copy(wake = hard)
        assertSame(hardRing, hardRing.finishUserOccurrence(7, at, at + 60_000))
    }

    @Test fun preRingWindowStartsAtBoundary() {
        val alarm = Alarm(10L, LocalTime.of(7, 0), wake = WakeOptions(lockBeforeRingMin = 30))
        assertFalse(alarm.isPreRingProtected(ZonedDateTime.parse("2026-11-21T06:29:59Z")))
        assertTrue(alarm.isPreRingProtected(ZonedDateTime.parse("2026-11-21T06:30:00Z")))
        assertFalse(alarm.copy(enabled = false).isPreRingProtected(ZonedDateTime.parse("2026-11-21T06:30:00Z")))
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
