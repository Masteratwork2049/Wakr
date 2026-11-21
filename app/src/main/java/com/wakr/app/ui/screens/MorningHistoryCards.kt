package com.wakr.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.wakr.app.core.MorningHistory
import com.wakr.app.core.WakeOutcome
import com.wakr.app.core.WakeUpLog
import com.wakr.app.core.dismissalMillis
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
internal fun MorningToday(history: MorningHistory) {
    val today = history.today
    val count = today.entries.size
    HistoryCard {
        Text("Today", style = MaterialTheme.typography.titleLarge)
        Text(when {
            count == 0 -> "No alarms have rung today yet."
            today.clean && count == 1 -> "1 clean wake-up"
            today.clean -> "$count clean wake-ups"
            count == 1 -> today.entries.single().outcome.label
            else -> "${today.entries.count { it.wasCleanWakeUp }} of $count wake-ups were clean"
        }, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun MorningWeekSummary(history: MorningHistory) {
    HistoryCard {
        Text("How it went", style = MaterialTheme.typography.titleLarge)
        val count = history.week.size
        Text("Last 7 days · $count ${if (count == 1) "alarm" else "alarms"}",
            style = MaterialTheme.typography.bodyLarge)
        if (history.week.isNotEmpty()) {
            val mutes = history.missionMutes
            Text(when {
                mutes == null -> "Mission mutes weren’t tracked for these alarms."
                history.hasUntrackedMutes -> "$mutes recorded mission ${if (mutes == 1L) "mute" else "mutes"}"
                mutes == 1L -> "Muted once during a mission"
                else -> "Muted $mutes times during missions"
            }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun MorningStreak(history: MorningHistory) {
    val colors = MaterialTheme.colorScheme
    HistoryCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(56.dp).background(colors.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.LocalFireDepartment, null, tint = colors.onPrimaryContainer)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val count = history.currentStreak
                Text("$count-day streak", style = MaterialTheme.typography.headlineSmall)
                Text("Best: ${history.bestStreak} ${if (history.bestStreak == 1) "day" else "days"}",
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
        Text("Consecutive days with only clean wake-ups.", style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant)
    }
}

@Composable
internal fun MorningDismissalChart(history: MorningHistory, calendar: DateTimeFormatter, locale: Locale) {
    val colors = MaterialTheme.colorScheme
    val scale = maxOf(60_000L, history.days.maxOf { it.averageDismissalMillis ?: 0L })
    HistoryCard {
        Text("Time to get up", style = MaterialTheme.typography.titleLarge)
        Text("Average time from first ring to dismissal each day. Error-colored bars mark days with a wake-up that wasn’t clean.",
            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            history.days.forEach { day ->
                val duration = day.averageDismissalMillis
                val barHeight = when {
                    duration != null -> 72.dp * (duration.toDouble() / scale).toFloat().coerceIn(.14f, 1f)
                    day.entries.isNotEmpty() -> 10.dp
                    else -> 0.dp
                }
                val description = when {
                    day.entries.isEmpty() -> "No recorded alarms"
                    duration == null -> "No recorded dismissal"
                    else -> "${dismissalTimeLabel(duration)} average, ${if (day.clean) "clean wake-ups" else "included a wake-up that wasn’t clean"}"
                }
                Column(Modifier.weight(1f).clearAndSetSemantics {
                    contentDescription = "${calendar.format(day.date)}, $description"
                }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.widthIn(max = 28.dp).fillMaxWidth().height(72.dp)
                        .background(colors.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.BottomCenter) {
                        if (barHeight > 0.dp) Box(Modifier.fillMaxWidth().height(barHeight)
                            .background(if (day.clean) colors.primary else colors.error, CircleShape))
                    }
                    Text(day.date.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                        color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
internal fun MorningStatGrid(history: MorningHistory, locale: Locale) {
    val snoozes = history.averageSnoozes?.let {
        NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }.format(it)
    } ?: "—"
    val checks = when {
        history.completedChecks > 0 -> "${history.passedChecks} / ${history.completedChecks}"
        history.legacyCheckResults > 0 -> "${history.legacyCheckPasses} / ${history.legacyCheckResults}"
        else -> "—"
    }
    val checksLabel = if (history.completedChecks == 0L && history.legacyCheckResults > 0)
        "Still-awake series passed" else "Still-awake checks passed"
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatRow(history.cleanPercent?.let { "$it%" } ?: "—", "Clean wake-ups",
            history.averageDismissalMillis?.let(::dismissalTimeLabel) ?: "—", "Average time to dismiss")
        StatRow(snoozes, "Average snoozes", checks, checksLabel)
    }
}

@Composable
private fun StatRow(first: String, firstLabel: String, second: String, secondLabel: String) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HistoryStat(first, firstLabel, Modifier.weight(1f).fillMaxHeight())
        HistoryStat(second, secondLabel, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun HistoryStat(value: String, label: String, modifier: Modifier) {
    HistoryCard(modifier) {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun RecentAlarmRow(entry: WakeUpLog, zone: ZoneId, clock: DateTimeFormatter, calendar: DateTimeFormatter) {
    val colors = MaterialTheme.colorScheme
    val at = Instant.ofEpochMilli(entry.firstRingAt).atZone(zone)
    val time = clock.format(at)
    val detail = listOfNotNull(
        calendar.format(at), time, entry.dismissalMillis?.let(::dismissalTimeLabel),
        entry.snoozeCount.takeIf { it > 0 }?.let { "Snoozed $it ${if (it == 1) "time" else "times"}" },
        entry.missionMuteCount?.takeIf { it > 0 }?.let { "$it ${if (it == 1) "mute" else "mutes"}" },
    ).joinToString(" · ")
    HistoryCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val dismissed = entry.outcome == WakeOutcome.DISMISSED
            Icon(if (dismissed) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null,
                tint = if (dismissed) colors.primary else colors.error, modifier = Modifier.size(22.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(entry.alarmLabel.ifBlank { time }, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Text(entry.outcome.label, style = MaterialTheme.typography.labelLarge,
                    color = if (dismissed) colors.onSurface else colors.error)
            }
        }
    }
}

@Composable
private fun HistoryCard(modifier: Modifier = Modifier.fillMaxWidth(), content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

private fun dismissalTimeLabel(millis: Long): String {
    if (millis <= 0) return "0 s"
    if (millis < 1_000) return "<1 s"
    val seconds = millis / 1_000
    return when {
        seconds < 60 -> "$seconds s"
        seconds < 3_600 -> "${seconds / 60} min" + if (seconds % 60 == 0L) "" else " ${seconds % 60} s"
        else -> "${seconds / 3_600} h ${(seconds % 3_600) / 60} min"
    }
}
