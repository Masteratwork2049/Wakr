package com.wakr.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.wakr.app.core.TimerState
import com.wakr.app.data.ClockStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles exact alarm firings and timer expiries from `AlarmManager`.
 *
 * ## `goAsync`
 *
 * Reading DataStore suspends and `onReceive` runs on the main thread with a hard deadline. The
 * pending result keeps the process alive across the coroutine; `finish()` must be reached on every
 * path or the receiver leaks and the system eventually kills the app for it.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    ACTION_RESUME_RING -> startRing(app, intent.getLongExtra(EXTRA_ID, -1L), intent.getLongExtra(UpcomingAlarm.AT, -1L))
                    ACTION_FIRE -> fire(app, intent.getLongExtra(EXTRA_ID, -1L), intent.getLongExtra(UpcomingAlarm.AT, 0))
                    UpcomingAlarm.SHOW, UpcomingAlarm.SKIP -> UpcomingAlarm.receive(app, intent)
                    ACTION_TIMER_EXPIRED -> timerExpired(app)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun fire(context: Context, id: Long, at: Long) {
        if (at > System.currentTimeMillis() + 1000) return
        var firing: com.wakr.app.core.Alarm? = null
        val cleared = RuntimeAlarms.update(context) { list -> list.map { alarm ->
            if (alarm.id != id || !alarm.enabled || (at > 0 && !UpcomingAlarm.matches(alarm, at))) alarm
            else {
                firing = alarm
                val continuation = alarm.snoozedUntilMillis == at
                alarm.copy(ringingAt = at, firstRingAt = alarm.firstRingAt?.takeIf { continuation } ?: at,
                    snoozeCount = alarm.snoozeCount.takeIf { continuation } ?: 0,
                    missionIndex = alarm.missionIndex.takeIf { continuation } ?: 0,
                    rebootCount = alarm.rebootCount.takeIf { continuation } ?: 0,
                    missedRetries = if (continuation) alarm.missedRetries else 0, enabled = !alarm.isOneShot, snoozedUntilMillis = null, skippedOccurrenceMillis = at.takeIf { it > 0 } ?: alarm.skippedOccurrenceMillis,
                    skippedOccurrenceDate = if (at > 0) java.time.Instant.ofEpochMilli(at).atZone(alarm.scheduleZone(java.time.ZoneId.systemDefault())).toLocalDate()
                        else java.time.LocalDate.now())
            }
        } }
        val alarm = firing ?: return
        UpcomingAlarm.hide(context,id)
        cleared.firstOrNull { it.id == id }?.let { AlarmScheduler.schedule(context, it) }

        startRing(context, alarm.id, at)
    }

    private fun startRing(context: Context, id: Long, at: Long) {
        if (id < 0 || at <= 0) return
        ContextCompat.startForegroundService(context, Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_START).putExtra(EXTRA_ID, id).putExtra(UpcomingAlarm.AT, at))
    }

    private suspend fun timerExpired(context: Context) {
        val store = ClockStore(context)
        val timer = store.timerNow() ?: return
        if (timer.state != TimerState.RUNNING ||
            timer.remaining(android.os.SystemClock.elapsedRealtime()).toMillis() > 1000) return
        // Mark the timer as FINISHED so the full-screen finished screen is presented in the app
        store.putTimer(timer.copy(state = TimerState.FINISHED))
        Notifications.hideTimer(context)
        ContextCompat.startForegroundService(
            context,
            Intent(context, AlarmService::class.java).setAction(AlarmService.ACTION_START_TIMER),
        )
    }

    companion object {
        const val ACTION_RESUME_RING = "com.wakr.app.RESUME_RING"
        const val ACTION_FIRE = "com.wakr.app.FIRE"
        const val ACTION_TIMER_EXPIRED = "com.wakr.app.TIMER_EXPIRED"
        const val EXTRA_ID = "id"
    }
}
