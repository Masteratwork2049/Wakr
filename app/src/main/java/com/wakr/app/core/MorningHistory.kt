package com.wakr.app.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal data class WakeDay(
    val date: LocalDate,
    val entries: List<WakeUpLog>,
) {
    val clean = entries.isNotEmpty() && entries.all { it.wasCleanWakeUp }
    val averageDismissalMillis = entries.mapNotNull { it.dismissalMillis }.meanMillis()
}

internal data class MorningHistory(
    val days: List<WakeDay>,
    val week: List<WakeUpLog>,
    val recent: List<WakeUpLog>,
    val currentStreak: Int,
    val bestStreak: Int,
) {
    val today: WakeDay get() = days.last()
    val cleanPercent = week.takeIf { it.isNotEmpty() }?.let { records ->
        (100.0 * records.count { it.wasCleanWakeUp } / records.size).roundToInt()
    }
    val averageDismissalMillis = week.mapNotNull { it.dismissalMillis }.meanMillis()
    val averageSnoozes = week.takeIf { it.isNotEmpty() }?.map { it.snoozeCount }?.average()
    val missionMutes = week.mapNotNull { it.missionMuteCount?.toLong() }.takeIf { it.isNotEmpty() }?.sum()
    val hasUntrackedMutes = week.any { it.missionMuteCount == null }
    // Older history only reports whether a full series passed. Exact counts are recorded per prompt now.
    val completedChecks = week.sumOf { it.completedWakeChecks?.toLong() ?: 0L }
    val passedChecks = week.sumOf { it.passedWakeChecks?.toLong() ?: 0L }
    val legacyCheckResults = week.count { it.stillAwakePassed != null && it.completedWakeChecks == null }
    val legacyCheckPasses = week.count { it.stillAwakePassed == true && it.completedWakeChecks == null }
}

internal fun morningHistory(history: List<WakeUpLog>, today: LocalDate, zone: ZoneId): MorningHistory {
    val recent = history.sortedByDescending { it.firstRingAt }
    val byDate = recent.groupBy { Instant.ofEpochMilli(it.firstRingAt).atZone(zone).toLocalDate() }
    val days = (6L downTo 0L).map { offset ->
        val date = today.minusDays(offset)
        WakeDay(date, byDate[date].orEmpty())
    }
    val cleanDays = byDate.filter { (day, entries) -> day <= today && entries.all { it.wasCleanWakeUp } }.keys
    var previous: LocalDate? = null
    var run = 0
    var best = 0
    for (day in cleanDays.sorted()) {
        run = if (previous == day.minusDays(1)) run + 1 else 1
        best = maxOf(best, run)
        previous = day
    }
    // A day with no recorded alarm yet leaves yesterday's streak visible.
    val streakEnd = if (byDate[today].isNullOrEmpty()) today.minusDays(1) else today
    return MorningHistory(days, days.flatMap { it.entries }, recent, if (previous == streakEnd) run else 0, best)
}

internal val WakeUpLog.dismissalMillis: Long?
    get() = dismissedAt?.takeIf { it >= firstRingAt &&
        (outcome == WakeOutcome.DISMISSED || outcome == WakeOutcome.STILL_AWAKE_FAILED) }?.minus(firstRingAt)

private fun List<Long>.meanMillis(): Long? = takeIf { it.isNotEmpty() }?.average()?.roundToLong()
