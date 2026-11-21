package com.wakr.app.ui.sheets

import com.wakr.app.core.asDuplicate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.wakr.app.core.Alarm
import com.wakr.app.data.WeekStart
import com.wakr.app.data.order
import java.time.LocalTime

@Composable
fun AlarmEditPage(
    initial: Alarm,
    weekStart: WeekStart,
    onDismiss: () -> Unit,
    onSave: (Alarm) -> Unit,
    onDelete: ((Long) -> Unit)?,
    onSkip: ((Long) -> Unit)? = null,
) {
    val context = LocalContext.current
    val is24Hour = com.wakr.app.ui.rememberSystem24HourFormat()

    var hour by rememberSaveable(initial.id) { mutableStateOf(initial.time.hour) }
    var minute by rememberSaveable(initial.id) { mutableStateOf(initial.time.minute) }
    var pm by rememberSaveable(initial.id) { mutableStateOf(initial.time.hour >= 12) }
    var editingHour by rememberSaveable(initial.id) { mutableStateOf(true) }
    var label by rememberSaveable(initial.id) { mutableStateOf(initial.label) }
    var days by remember(initial.id) { mutableStateOf(initial.days) }
    var vibrate by rememberSaveable(initial.id) { mutableStateOf(initial.vibrate) }
    var soundUri by rememberSaveable(initial.id) { mutableStateOf(initial.soundUri) }

    var wake by androidx.compose.runtime.saveable.rememberSaveable(initial.id, stateSaver = com.wakr.app.ui.sheets.WakeDraftSaver) { mutableStateOf(initial.wake) }
    var missions by androidx.compose.runtime.saveable.rememberSaveable(initial.id,
        stateSaver = com.wakr.app.ui.sheets.MissionDraftSaver) { mutableStateOf(initial.missions) }
    val openSoundSelector = rememberAlarmSoundSelector(soundUri) { soundUri = it }

    val soundLabel by androidx.compose.runtime.produceState(initialValue = "Default", key1 = soundUri, key2 = context) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.wakr.app.ui.sheets.alarmSoundTitle(context, soundUri)
        }
    }

    AlarmFullscreenDialog(onDismiss) {
        AlarmPickerSheetContent(
            wake = wake, setWake = { wake = it }, setDays = { days = it },
            missions = missions, onMissions = { missions = it },
            fullscreen = true,
            title = "Edit Alarm",
            hour = hour,
            minute = minute,
            is24Hour = is24Hour,
            pm = pm,
            editingHour = editingHour,
            label = label,
            days = days,
            weekOrder = weekStart.order(systemFirstDay()),
            vibrate = vibrate,
            soundLabel = soundLabel,
            showDelete = onDelete != null,
            onHourChange = { hour = it },
            onMinuteChange = { minute = it },
            onPmChange = { pm = it },
            onEditingHourChange = { editingHour = it },
            onLabelChange = { label = it },
            onToggleDay = { day -> days = if (day in days) days - day else days + day },
            onVibrateChange = { vibrate = it },
            onSoundClick = openSoundSelector,
            onCancel = onDismiss,
            onSave = { duplicate ->
                val saveHour = if (is24Hour) hour else if (pm) (hour % 12) + 12 else hour % 12
                val updated = initial.copy(
                    missions = missions,
                    wake = wake.forAlarmSave(days, LocalTime.of(saveHour, minute)),
                    time = LocalTime.of(saveHour, minute),
                    label = label.trim(),
                    days = days,
                    vibrate = vibrate,
                    soundUri = soundUri,
                ).let { if (duplicate) it.asDuplicate() else it.copy(enabled = true, pausedByGroup = false) }
                onSave(updated)
            },
            onSkip = onSkip?.takeIf { initial.nextFire(java.time.ZonedDateTime.now()) != null }
                ?.let { skip -> { skip(initial.id) } },
            onDelete = {
                onDelete?.invoke(initial.id)
                onDismiss()
            },
        )
    }
}
