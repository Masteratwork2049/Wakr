package com.wakr.app.core

import com.wakr.app.alarm.alertVolumeFloor
import com.wakr.app.ui.screens.countdownText
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test

class TimerAdjustmentTest {
    @Test fun oneTurnFollowsEachTimersOwnDurationAndPauseFreezesTheHand() {
        for (duration in listOf(30_000L, 600_000L)) {
            val timer = ClockTimer(1, "Test", Duration.ofMillis(duration), TimerState.RUNNING, 4_000 + duration)
            assertEquals(0.0, timer.counterclockwiseProgress(4_000), 0.0001)
            assertEquals(.25, timer.counterclockwiseProgress(4_000 + duration / 4), 0.0001)
            val pauseAt = 4_000 + duration / 2
            val paused = timer.copy(state = TimerState.PAUSED, pausedRemaining = timer.remaining(pauseAt))
            assertEquals(.5, paused.counterclockwiseProgress(pauseAt + 80_000), 0.0001)
            assertEquals(1.0, timer.counterclockwiseProgress(4_000 + duration), 0.0001)
        }
    }

    @Test fun overtimeHandRepeatsEachMinuteWhileTheReadoutKeepsCounting() {
        assertEquals(0f, timerOverdueTurns(0), .0001f)
        assertEquals(-.25f, timerOverdueTurns(15_000), .0001f)
        assertEquals(-59f / 60f, timerOverdueTurns(59_000), .0001f)
        assertEquals(0f, timerOverdueTurns(60_000), .0001f)
        assertEquals(-.25f, timerOverdueTurns(75_000), .0001f)
        assertEquals(0f, timerOverdueTurns(120_000), .0001f)
        val finished = ClockTimer(1, "Test", Duration.ofMinutes(15), TimerState.FINISHED, 30_000)
        val other = finished.copy(id = 2, deadlineElapsedMillis = 100_000)
        val waitingForExpiryCallback = finished.copy(state = TimerState.RUNNING)
        assertEquals(1L, waitingForExpiryCallback.dialSeconds(29_999))
        assertEquals(0L, waitingForExpiryCallback.dialSeconds(30_000))
        assertEquals(-1L, waitingForExpiryCallback.dialSeconds(31_000))
        assertEquals("−00:59", withEnglishLocale { countdownText(finished.dialSeconds(89_999)) })
        assertEquals("−01:00", withEnglishLocale { countdownText(finished.dialSeconds(90_000)) })
        assertEquals("−01:02", withEnglishLocale { countdownText(finished.dialSeconds(92_000)) })
        assertEquals("−02:00", withEnglishLocale { countdownText(finished.dialSeconds(150_000)) })
        assertEquals("−00:50", withEnglishLocale { countdownText(other.dialSeconds(150_000)) })
        assertEquals(0f, finished.dialTurns(90_000), .0001f)
        assertEquals(-.25f, finished.dialTurns(105_000), .0001f)
    }

    @Test fun ringingVolumeRoundsUpToEightyPercentOnTheDeviceScale() {
        assertEquals(0, alertVolumeFloor(0))
        assertEquals(6, alertVolumeFloor(7))
        assertEquals(12, alertVolumeFloor(15))
    }

    private fun withEnglishLocale(read: () -> String): String {
        val previous = java.util.Locale.getDefault()
        return try {
            java.util.Locale.setDefault(java.util.Locale.US)
            read()
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test fun extendingCountdownPreservesHandPositionAndFinishesItsTurn() {
        val timer = ClockTimer(1, "Test", Duration.ofSeconds(30), TimerState.RUNNING, 30_000)
        val updated = timer.withAddedSeconds(10, 15_000)
        assertEquals(.5, updated.counterclockwiseProgress(15_000), 0.0001)
        assertEquals(.75, updated.counterclockwiseProgress(27_500), 0.0001)
        assertEquals(1.0, updated.counterclockwiseProgress(40_000), 0.0001)
    }

    @Test fun runningAdjustmentExtendsDeadlineByExactlyOneMinute() {
        val timer = ClockTimer(1, "Test", Duration.ofSeconds(10), TimerState.RUNNING, 10000)
        val next = timer.withAddedSeconds(60)
        assertEquals(70000L, next.deadlineElapsedMillis)
        assertEquals(Duration.ofSeconds(70), next.total)
        assertEquals(timer.state, next.state)
    }
    @Test fun screenAdjustmentStillAddsTenSeconds() {
        val timer = ClockTimer(1, "Test", Duration.ofSeconds(10), TimerState.RUNNING, 10000)
        assertEquals(20000L, timer.withAddedSeconds(10).deadlineElapsedMillis)
        assertEquals(Duration.ofSeconds(20), timer.withAddedSeconds(10).total)
    }
    @Test fun pausedAdjustmentPreservesPauseAndAddsMinuteToRemaining() {
        val timer = ClockTimer(1, "Test", Duration.ofHours(1), TimerState.PAUSED, 123, Duration.ofSeconds(5))
        val next = timer.withAddedSeconds(60)
        assertEquals(Duration.ofSeconds(65), next.pausedRemaining)
        assertEquals(123L, next.deadlineElapsedMillis)
        assertEquals(TimerState.PAUSED, next.state)
    }
}
