package com.wakr.app.ui.sheets

import androidx.compose.runtime.Composable
import com.wakr.app.core.Alarm
import com.wakr.app.data.WeekStart

@Composable
fun NewAlarmPickerSheet(
    initial: Alarm,
    relative: Boolean = false,
    weekStart: WeekStart,
    onDismiss: () -> Unit,
    onSave: (Alarm) -> Unit,
) {
    AlarmEditPage(initial, weekStart, onDismiss, onSave, onDelete = null,
        creating = true, relative = relative)
}
