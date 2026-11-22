package com.wakr.app.core

/** Add time while preserving the current hand angle and the remaining part of its turn. */
internal fun ClockTimer.withAddedSeconds(seconds: Long, nowElapsedMillis: Long = deadlineElapsedMillis - sweepRemainingMillis): ClockTimer {
    val added = seconds * 1_000L
    val remainingMillis = remaining(nowElapsedMillis).toMillis()
    val next = copy(
        total = total.plusSeconds(seconds),
        sweepAnchor = counterclockwiseProgress(nowElapsedMillis),
        sweepRemainingMillis = remainingMillis + added,
    )
    return if (state == TimerState.RUNNING) next.copy(deadlineElapsedMillis = deadlineElapsedMillis + added)
    else next.copy(pausedRemaining = pausedRemaining.plusSeconds(seconds))
}
