package com.wakr.app.core

/** Label/audio changes must not undo a dismissed occurrence. Re-arming/time changes do. */
internal fun Alarm.preservingDismissal(previous: Alarm?): Alarm {
    val sameOccurrence = previous != null && previous.enabled == enabled &&
        previous.time == time && previous.days == days && previous.wake.scheduledDateEpochDay == wake.scheduledDateEpochDay &&
        previous.wake.timeZoneId == wake.timeZoneId
    return copy(snoozedUntilMillis = null, ringingAt = null, missedRetries = 0,
        skippedOccurrenceMillis = if (sameOccurrence) previous?.skippedOccurrenceMillis else null,
        skippedOccurrenceDate = if (sameOccurrence) previous?.skippedOccurrenceDate else null)
}

/** Copies the alarm's settings and switch state without carrying over a ringing occurrence. */
internal fun Alarm.asDuplicate(): Alarm = copy(id = 0L, createdAt = null).preservingDismissal(null)
