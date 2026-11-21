package com.wakr.app.core

import java.time.LocalDate
import java.time.ZonedDateTime

internal data class ScheduledAlarm(val alarm: Alarm, val at: ZonedDateTime)

internal data class MorningSchedule(
    val next: ScheduledAlarm?,
    val byDay: Map<LocalDate, List<ScheduledAlarm>>,
)

internal fun morningSchedule(alarms: List<Alarm>, now: ZonedDateTime): MorningSchedule {
    val end = now.toLocalDate().plusDays(7).atStartOfDay(now.zone)
    val upcoming = alarms.mapNotNull { alarm -> alarm.nextFire(now)?.let { ScheduledAlarm(alarm, it) } }
    val week = buildList {
        for (first in upcoming) {
            var occurrence = first
            while (occurrence.at < end) {
                add(occurrence)
                if (first.alarm.isOneShot) break
                val next = first.alarm.nextFire(occurrence.at) ?: break
                occurrence = ScheduledAlarm(first.alarm, next)
            }
        }
    }.sortedWith(compareBy({ it.at.toInstant() }, { it.alarm.id }))
    return MorningSchedule(upcoming.minWithOrNull(compareBy({ it.at.toInstant() }, { it.alarm.id })),
        week.groupBy { it.at.toLocalDate() })
}
