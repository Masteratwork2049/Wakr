package com.wakr.app.core

/** The occurrence timestamp travels from the firing receiver through every notification action. */
internal fun Alarm.finishOccurrence(id: Long, at: Long, snoozeUntil: Long?): Alarm? = when {
    this.id != id || ringingAt != at -> this
    snoozeUntil != null -> copy(enabled = true, snoozedUntilMillis = snoozeUntil, ringingAt = null)
    temporary -> null
    else -> copy(ringingAt = null, missedRetries = 0)
}

internal fun Alarm.missOccurrence(at: Long, now: Long): Alarm {
    if (ringingAt != at) return this
    if (wake.repeatMissedCount >= 0 && missedRetries >= wake.repeatMissedCount) {
        return copy(ringingAt = null, missedRetries = 0)
    }
    return copy(enabled = true, ringingAt = null, missedRetries = missedRetries + 1,
        snoozedUntilMillis = now + wake.reRingIntervalMin.coerceAtLeast(1) * 60_000L)
}
