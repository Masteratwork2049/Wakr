package com.wakr.app.ui.screens

import android.text.format.DateFormat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddAlarm
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.wakr.app.core.Alarm
import com.wakr.app.core.ScheduledAlarm
import com.wakr.app.core.WakeUpLog
import com.wakr.app.core.morningSchedule
import com.wakr.app.ui.luminousElasticClickable
import com.wakr.app.ui.rememberWallTicker
import com.wakr.app.ui.theme.ClockFace
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
internal fun MorningScreen(
    alarms: List<Alarm>, history: List<WakeUpLog>, padding: PaddingValues, use24h: Boolean,
    selected: Boolean, onAdd: () -> Unit, onEdit: (Alarm) -> Unit,
) {
    val millis by rememberWallTicker(periodMillis = 60_000L)
    val zone = ZoneId.systemDefault()
    val now = remember(millis, zone) { Instant.ofEpochMilli(millis).atZone(zone) }
    val today = now.toLocalDate()
    val schedule = remember(alarms, now) { morningSchedule(alarms, now) }
    var chosen by rememberSaveable(today.toEpochDay()) { mutableLongStateOf(today.toEpochDay()) }
    val day = LocalDate.ofEpochDay(chosen).coerceIn(today, today.plusDays(6))
    val events = schedule.byDay[day].orEmpty()
    val locale = LocalConfiguration.current.locales[0]
    val clock = remember(locale, use24h) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, if (use24h) "Hm" else "hm"), locale)
    }
    val calendar = remember(locale) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEMMMd"), locale)
    }
    val recent = remember(history) { history.sortedByDescending { it.firstRingAt } }
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxSize(), contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Morning", color = colors.onSurface, style = MaterialTheme.typography.headlineLarge)
            }
            item {
                NextAlarmCard(schedule.next, clock, calendar, use24h, selected, onAdd, onEdit)
            }
            item {
                Text("Next seven days", Modifier.padding(top = 12.dp), color = colors.onSurface,
                    style = MaterialTheme.typography.titleLarge)
            }
            item {
                WeekStrip(today, day, schedule.byDay.keys, locale, calendar) { chosen = it.toEpochDay() }
            }
            if (events.isEmpty()) {
                item(key = "empty-$day") {
                    Text("No alarms scheduled for ${calendar.format(day)}.",
                        Modifier.animateItem().padding(vertical = 8.dp), color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge)
                }
            }
            items(events, key = { "due-${it.alarm.id}-${it.at.toInstant()}" }) { event ->
                Row(Modifier.animateItem().fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(colors.surfaceContainerLow).clickable { onEdit(event.alarm) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(clock.format(event.at), color = colors.onSurface, style = MaterialTheme.typography.titleLarge)
                    Text(event.alarm.label.ifBlank { "Alarm" }, color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (recent.isNotEmpty()) {
                item {
                    Text("Recent alarms", Modifier.padding(top = 12.dp), color = colors.onSurface,
                        style = MaterialTheme.typography.titleLarge)
                }
                items(recent, key = { "history-${it.id}" }) { entry ->
                    val at = Instant.ofEpochMilli(entry.firstRingAt).atZone(zone)
                    Column(Modifier.fillMaxWidth().background(colors.surfaceContainerLow, RoundedCornerShape(20.dp))
                        .padding(16.dp)) {
                        Text(entry.alarmLabel.ifBlank { "Alarm" }, color = colors.onSurface,
                            style = MaterialTheme.typography.titleMedium)
                        Text("${calendar.format(at)} · ${clock.format(at)} · ${entry.outcome.label}",
                            color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun NextAlarmCard(
    next: ScheduledAlarm?, clock: DateTimeFormatter, calendar: DateTimeFormatter,
    use24h: Boolean, selected: Boolean, onAdd: () -> Unit, onEdit: (Alarm) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    val enter = remember { Animatable(0f) }
    LaunchedEffect(selected) {
        if (selected) enter.animateTo(1f, spring(dampingRatio = .72f, stiffness = 400f))
        else enter.snapTo(0f)
    }
    Column(Modifier.fillMaxWidth().graphicsLayer {
        scaleX = .94f + .06f * enter.value
        scaleY = scaleX
        translationY = 12.dp.toPx() * (1f - enter.value)
    }.luminousElasticClickable(shape = shape, onClick = { if (next == null) onAdd() else onEdit(next.alarm) })
        .background(colors.primaryContainer, shape)
        .border(1.25.dp, Color.White.copy(alpha = .26f), shape).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Next alarm", color = colors.onPrimaryContainer, style = MaterialTheme.typography.titleMedium)
            Icon(if (next == null) Icons.Outlined.AddAlarm else Icons.Outlined.WbSunny, null,
                Modifier.size(28.dp).graphicsLayer { rotationZ = (1f - enter.value) * -45f },
                tint = colors.onPrimaryContainer)
        }
        if (next == null) {
            Text("No upcoming alarm", color = colors.onPrimaryContainer, style = MaterialTheme.typography.headlineMedium)
            Text("Add an alarm", color = colors.onPrimaryContainer, style = MaterialTheme.typography.labelLarge)
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                AlarmTimeReadout(time = next.at.toLocalTime(), use24h = use24h,
                    capHeight = (maxWidth * .28f).coerceIn(34.dp, 88.dp),
                    ink = colors.onPrimaryContainer, weight = ClockFace.WEIGHT_ON,
                    modifier = Modifier.clearAndSetSemantics { contentDescription = clock.format(next.at) })
            }
            Text(listOf(calendar.format(next.at), next.alarm.label).filter { it.isNotBlank() }.joinToString(" · "),
                color = colors.onPrimaryContainer, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun WeekStrip(
    today: LocalDate, selected: LocalDate, scheduled: Set<LocalDate>, locale: Locale,
    calendar: DateTimeFormatter, onSelect: (LocalDate) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cellWidth = ((maxWidth - 24.dp) / 7).coerceAtLeast(44.dp)
        Row(Modifier.horizontalScroll(rememberScrollState()).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(7) { index ->
                val date = today.plusDays(index.toLong())
                val active = date == selected
                val fill by animateColorAsState(if (active) colors.primary else colors.surfaceContainerLow,
                    tween(220), label = "morningDayFill")
                val ink by animateColorAsState(if (active) colors.onPrimary else colors.onSurface,
                    tween(220), label = "morningDayInk")
                val hasAlarm = date in scheduled
                Column(Modifier.width(cellWidth).clip(RoundedCornerShape(22.dp)).background(fill)
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(date) })
                    .semantics { contentDescription = calendar.format(date) + if (hasAlarm) ", alarm scheduled" else ", no alarms" }
                    .padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(date.dayOfWeek.getDisplayName(TextStyle.NARROW, locale), color = ink,
                        style = MaterialTheme.typography.labelMedium)
                    Text(date.dayOfMonth.toString(), color = ink, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.padding(top = 3.dp).size(4.dp)
                        .background(if (hasAlarm) ink else Color.Transparent, CircleShape))
                }
            }
        }
    }
}
