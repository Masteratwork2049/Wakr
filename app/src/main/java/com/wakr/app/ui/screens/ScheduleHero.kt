package com.wakr.app.ui.screens

import android.content.*
import android.os.BatteryManager
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.wakr.app.core.*
import com.wakr.app.ui.LocalAlarmCards24Hour
import com.wakr.app.ui.rememberWallTicker
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun ScheduleHero(alarms: List<Alarm>, pause: () -> Unit, add: () -> Unit, edit: (Alarm) -> Unit) {
    val tick by rememberWallTicker(60_000L)
    val now = Instant.ofEpochMilli(tick).atZone(ZoneId.systemDefault())
    val next = remember(alarms, now) { alarms.mapNotNull { a -> a.nextFire(now)?.let { ScheduledAlarm(a, it) } }.minByOrNull { it.at } }
    val use24 = LocalAlarmCards24Hour.current
    val clock = remember(use24) { DateTimeFormatter.ofPattern(if (use24) "HH:mm" else "h:mm a") }
    val calendar = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    val context = LocalContext.current
    var low by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 0)
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                low = level >= 0 && scale > 0 && level * 100 < scale * 20 && intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == 0
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }
    NextAlarmCard(next, clock, calendar, use24, true, add, edit) {
        val ink = MaterialTheme.colorScheme.onPrimaryContainer
        if (next != null) {
            val difficulty = next.alarm.wake.difficulty.name.lowercase().replace('_', ' ')
            val day = if (next.at.toLocalDate() == now.toLocalDate().plusDays(1)) " · Tomorrow" else ""
            val missions = next.alarm.missions.size
            val missionText = if (missions > 0) " · $missions missions" else ""
            Text("In " + humanUntil(now, next.at) + day + " · " + difficulty.replaceFirstChar(Char::uppercase) + missionText,
                color = ink, style = MaterialTheme.typography.labelLarge)
        }
        if (low) Text("Battery below 20% and unplugged", color = ink)
        val paused = alarms.any { it.pausedByGroup }
        if (paused || alarms.any { it.enabled }) FilledTonalButton(onClick = pause) {
            Text(if (paused) "Resume alarms" else "Pause all alarms")
        }
    }
}
