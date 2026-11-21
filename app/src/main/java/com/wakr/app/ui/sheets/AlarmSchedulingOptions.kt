package com.wakr.app.ui.sheets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wakr.app.data.toJson
import com.wakr.app.data.toWakeOptions
import com.wakr.app.core.WakeOptions
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

internal val WakeDraftSaver = androidx.compose.runtime.saveable.Saver<WakeOptions, String>(
    save = { it.toJson().toString() }, restore = { org.json.JSONObject(it).toWakeOptions() },
)

internal fun WakeOptions.forAlarmSave(days: Set<java.time.DayOfWeek>, time: LocalTime): WakeOptions {
    val zone = timeZoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    val relative = relativeTriggerMillis?.takeIf {
        val local = Instant.ofEpochMilli(it).atZone(zone)
        days.isEmpty() && local.toLocalDate().toEpochDay() == scheduledDateEpochDay &&
            local.hour == time.hour && local.minute == time.minute
    }
    return copy(relativeTriggerMillis = relative, scheduledDateEpochDay = scheduledDateEpochDay.takeIf { days.isEmpty() },
        pausedUntilEpochDay = pausedUntilEpochDay.takeIf { days.isNotEmpty() }, deleteAfterDismiss = deleteAfterDismiss && days.isEmpty())
}

@Composable
internal fun AlarmSchedulingOptions(
    wake: WakeOptions, once: Boolean, editing: Boolean, hour: Int, minute: Int, initialRelative: Boolean,
    setTime: (LocalTime) -> Unit, setWake: (WakeOptions) -> Unit,
    setDays: (Set<java.time.DayOfWeek>) -> Unit, duplicate: () -> Unit,
) {
    var dialog by rememberSaveable { mutableStateOf(if (initialRelative) "Alarm in…" else "") }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val zone = wake.timeZoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    Row {
        TextButton(onClick = { dialog = "Type time" }) { Text("Type time") }
        if (!editing) TextButton(onClick = { dialog = "Alarm in…" }) { Text("Alarm in…") }
    }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide alarm options" else "Alarm options") }
    AnimatedVisibility(expanded) {
        Column {
            NavigateRow("Repeat", if (once) "Once" else "Selected days") { dialog = "Repeat" }
            if (once) {
                NavigateRow("Schedule alarm", wake.scheduledDateEpochDay?.let { LocalDate.ofEpochDay(it).toString() } ?: "Next occurrence") { dialog = "Schedule alarm" }
                SwitchRow("Auto-delete after dismissal", "Show as Temporary on the Alarms tab", wake.deleteAfterDismiss) {
                    setWake(wake.copy(deleteAfterDismiss = it))
                }
            } else NavigateRow("Pause until", wake.pausedUntilEpochDay?.let { LocalDate.ofEpochDay(it).toString() } ?: "Off") { dialog = "Pause until" }
            NavigateRow("Time zone", wake.timeZoneId ?: "Device time zone") { dialog = "Time zone" }
            if (androidx.compose.ui.platform.LocalContext.current.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_FLASH)) SwitchRow("Flash", "Light the camera LED while ringing", wake.flashEnabled) { setWake(wake.copy(flashEnabled = it)) }
            NavigateRow("Silence after", wake.silenceAfterMin?.let { if (it == 0) "Never" else "$it min" } ?: "App default") { dialog = "Silence after" }
            NavigateRow("Repeat missed alarm", when (wake.repeatMissedCount) { -1 -> "Indefinitely"; 0 -> "Off"; else -> "${wake.repeatMissedCount} times" }) { dialog = "Repeat missed alarm" }
            if (wake.repeatMissedCount != 0) NavigateRow("Repeat interval", "${wake.reRingIntervalMin} min") { dialog = "Repeat interval" }
            SwitchRow("Lock alarm", "Require unlocking before editing", wake.isLocked) { setWake(wake.copy(isLocked = it)) }
            if (editing) TextButton(onClick = duplicate) { Text("Save as duplicate") }
        }
    }
    if (dialog == "") return
    if (dialog == "Schedule alarm" || dialog == "Pause until") {
        val scheduled = dialog == "Schedule alarm"
        val current = if (scheduled) wake.scheduledDateEpochDay else wake.pausedUntilEpochDay
        val today = LocalDate.now(zone)
        val picker = rememberDatePickerState(initialSelectedDateMillis = (current ?: today.toEpochDay()) * 86_400_000L)
        val date = picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
        fun apply(value: Long?) {
            setWake(if (scheduled) wake.copy(relativeTriggerMillis = null, scheduledDateEpochDay = value) else wake.copy(pausedUntilEpochDay = value))
            dialog = ""
        }
        DatePickerDialog(onDismissRequest = { dialog = "" }, confirmButton = {
            TextButton(enabled = date != null && date >= today && (!scheduled || date.atTime(hour, minute).atZone(zone).toInstant() > Instant.now()),
                onClick = { apply(date?.toEpochDay()) }) { Text("Set") }
        }, dismissButton = { TextButton(onClick = { apply(null) }) { Text("Clear") } }) { DatePicker(picker) }
        return
    }
    if (dialog == "Type time" || dialog == "Alarm in…") {
        val relative = dialog == "Alarm in…"
        var h by rememberSaveable(dialog) { mutableIntStateOf(if (relative) 0 else hour) }
        var m by rememberSaveable(dialog) { mutableIntStateOf(if (relative) 10 else minute) }
        var digits by rememberSaveable { mutableStateOf("%02d%02d".format(java.util.Locale.ROOT, hour, minute)) }
        val typed = digits.padStart(4, '0')
        val valid = digits.length in 3..4 && typed.take(2).toIntOrNull() in 0..23 && typed.takeLast(2).toIntOrNull() in 0..59
        AlertDialog(onDismissRequest = { dialog = "" }, title = { Text(dialog) }, text = {
            if (relative) Column {
                Text("Hours and minutes")
                Row {
                    AlarmWheel(Modifier.weight(1f), (0..24).toList(), h, Int::toString) { h = it }
                    AlarmWheel(Modifier.weight(1f), (0..59).toList(), m, Int::toString) { m = it }
                }
            } else OutlinedTextField(digits, { if (it.length <= 4 && it.all(Char::isDigit)) digits = it },
                label = { Text("24-hour time (HHMM)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }, confirmButton = { TextButton(enabled = if (relative) h + m > 0 else valid, onClick = {
            if (relative) {
                val at = java.time.ZonedDateTime.now(zone).plusHours(h.toLong()).plusMinutes(m.toLong())
                setDays(emptySet())
                setWake(wake.copy(relativeTriggerMillis = at.toInstant().toEpochMilli(), scheduledDateEpochDay = at.toLocalDate().toEpochDay(), pausedUntilEpochDay = null))
                setTime(at.toLocalTime())
            } else setTime(LocalTime.of(typed.take(2).toInt(), typed.takeLast(2).toInt()))
            dialog = ""
        }) { Text("Set") } }, dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } })
        return
    }
    var query by rememberSaveable(dialog) { mutableStateOf("") }
    val choices = remember(dialog) { when (dialog) {
        "Repeat" -> listOf("Once", "Weekdays", "Weekends", "Every day")
        "Time zone" -> listOf("Device time zone") + ZoneId.getAvailableZoneIds().sorted()
        "Silence after" -> listOf("App default", "Never") + (1..60).map(Int::toString)
        "Repeat interval" -> (1..60).map(Int::toString)
        else -> listOf("Off", "1", "2", "3", "4", "5", "Indefinitely")
    } }
    AlertDialog(onDismissRequest = { dialog = "" }, title = { Text(dialog) }, text = {
        Column {
            if (dialog == "Time zone") OutlinedTextField(query, { query = it }, label = { Text("Find time zone") }, singleLine = true)
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(choices.filter { it.contains(query, ignoreCase = true) }) { value ->
                    TextButton(onClick = {
                        when (dialog) {
                            "Time zone" -> setWake(wake.copy(relativeTriggerMillis = null, timeZoneId = value.takeUnless { it == "Device time zone" }))
                            "Repeat" -> setDays(when (value) {
                                "Once" -> emptySet()
                                "Weekdays" -> com.wakr.app.core.Alarm.WEEKDAYS
                                "Weekends" -> com.wakr.app.core.Alarm.WEEKENDS
                                else -> java.time.DayOfWeek.entries.toSet()
                            })
                            "Silence after" -> setWake(wake.copy(silenceAfterMin = if (value == "Never") 0 else value.toIntOrNull()))
                            "Repeat interval" -> setWake(wake.copy(reRingIntervalMin = value.toInt()))
                            else -> setWake(wake.copy(repeatMissedCount = if (value == "Indefinitely") -1 else value.toIntOrNull() ?: 0))
                        }
                        dialog = ""
                    }) { Text(value) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { dialog = "" }) { Text("Close") } })
}
