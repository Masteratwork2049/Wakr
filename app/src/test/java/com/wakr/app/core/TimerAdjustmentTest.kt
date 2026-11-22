package com.wakr.app.core

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
