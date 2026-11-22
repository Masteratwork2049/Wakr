package com.wakr.app.core

/** The occurrence timestamp travels from the firing receiver through every notification action. */
internal fun Alarm.finishOccurrence(id: Long, at: Long, snoozeUntil: Long?): Alarm? = when {
    this.id != id || ringingAt != at -> this
    snoozeUntil != null -> copy(enabled = true, snoozedUntilMillis = snoozeUntil, ringingAt = null, snoozeCount = snoozeCount + 1)
    temporary -> null
    else -> copy(ringingAt = null, missedRetries = 0, snoozeCount = 0, missionIndex = 0, rebootCount = 0, firstRingAt = null, missionMuteCount = null, ringSoundUri = null)
}

internal fun Alarm.missOccurrence(at: Long, now: Long): Alarm {
    if (ringingAt != at) return this
    if (wake.repeatMissedCount >= 0 && missedRetries >= wake.repeatMissedCount) {
        return copy(ringingAt = null, missedRetries = 0, snoozeCount = 0, missionIndex = 0, rebootCount = 0, firstRingAt = null, missionMuteCount = null, ringSoundUri = null)
    }
    return copy(enabled = true, ringingAt = null, missedRetries = missedRetries + 1,
        snoozedUntilMillis = now + wake.reRingIntervalMin.coerceAtLeast(1) * 60_000L)
}

/** Both notification buttons and the ringing activity must pass these occurrence checks. */
internal fun Alarm.finishUserOccurrence(id: Long, at: Long, snoozeUntil: Long?): Alarm? {
    if (snoozeUntil == null && missionsRemaining || snoozeUntil != null && !canSnooze()) return this
    return finishOccurrence(id, at, snoozeUntil)
}

/** Emergency stop is available only when the current alarm enables it. */
internal fun Alarm.finishEmergencyOccurrence(id: Long, at: Long): Alarm? =
    if (wake.emergencyStopEnabled) finishOccurrence(id, at, null) else this

/** Apply the final solved mission and dismissal in the same occurrence update. */
internal fun Alarm.finishAfterMission(id: Long, at: Long, solvedIndex: Int): Alarm? {
    if (this.id != id || ringingAt != at || missionIndex != solvedIndex ||
        ringMissions.lastIndex != solvedIndex || solvedIndex < 0 ||
        privateMissionCount > solvedIndex + 1
    ) return this
    return copy(missionIndex = solvedIndex + 1).finishUserOccurrence(id, at, null)
}
