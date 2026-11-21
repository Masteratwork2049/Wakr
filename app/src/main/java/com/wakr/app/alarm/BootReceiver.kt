package com.wakr.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wakr.app.data.ClockStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Reschedules all active alarms and timer notifications following device boot or system clock changes.
 *
 * ## Android 15 (API 35+) Compliance
 *
 * This receiver handles `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED`, and
 * `MY_PACKAGE_REPLACED`.
 *
 * To comply with Android 15 foreground service launch restrictions, this receiver contains **no references or
 * calls to `startForegroundService()`**. Its sole purpose is to re-arm alarms via `AlarmManager.setAlarmClock()`,
 * which gives each alarm an exact time callback with system-granted execution permissions when it later fires.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                reschedule(app, intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun reschedule(context: Context, recovering: Boolean) {
        val recover = mutableSetOf<Long>()
        val alarms = if (!recovering) RuntimeAlarms.read(context) else RuntimeAlarms.update(context) { list ->
            list.map { alarm ->
                if (alarm.ringingAt != null && alarm.rebootCount < alarm.wake.rebootSurvivalMax) {
                    recover += alarm.id
                    alarm.copy(rebootCount = alarm.rebootCount + 1)
                } else if (alarm.ringingAt != null) alarm.copy(ringingAt = null) else alarm
            }
        }
        Notifications.ensureChannels(context)
        AlarmScheduler.scheduleAll(context, alarms)
        alarms.filter { it.id in recover }.forEach { AlarmScheduler.recoverRinging(context, it) }
        if (!RuntimeAlarms.unlocked(context)) return
        WakeChecks.restore(context)
        val store = ClockStore(context)
        // A reboot resets elapsedRealtime, so any stored deadline is meaningless. Rebuild the
        // timer's notification from what survived and let the store's own clamp decide.
        store.timerNow()?.let { TimerScheduler.sync(context, it) }
    }
}
