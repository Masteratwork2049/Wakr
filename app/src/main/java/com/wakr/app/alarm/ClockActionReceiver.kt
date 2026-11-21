package com.wakr.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.wakr.app.core.withAddedSeconds
import com.wakr.app.core.ClockTimer
import com.wakr.app.core.finishUserOccurrence
import com.wakr.app.core.finishEmergencyOccurrence
import com.wakr.app.core.Alarm
import com.wakr.app.core.Lap
import com.wakr.app.core.Stopwatch
import com.wakr.app.core.TimerState
import com.wakr.app.data.ClockStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Duration

/**
 * The buttons on the notifications, and nothing else.
 *
 * Every one of these does exactly what the equivalent button in the app does, by writing the same
 * store, which is the reason the app needs no code to hear about it. `ClockStore` exposes flows,
 * the view model collects them, so pausing a timer from the shade updates the running screen
 * behind it with no broadcast, no binder and no event bus.
 */
class ClockActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val id = intent.getLongExtra(AlarmReceiver.EXTRA_ID, -1L)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            val store by lazy { ClockStore(app) }
            try {
                when (intent.action) {
                    ACTION_SNOOZE, ACTION_DISMISS, ACTION_EMERGENCY -> finishRing(
                        app, id, intent.getLongExtra(UpcomingAlarm.AT, -1), intent.action.orEmpty(),
                    )
                    ACTION_TIMER_TOGGLE -> timerToggle(app, store)
                    ACTION_TIMER_ADD -> timerAdd(app, store)
                    ACTION_TIMER_CANCEL -> {
                        AlarmService.stop(app)
                        store.putTimer(null)
                        TimerScheduler.sync(app, null)
                    }
                    ACTION_SW_TOGGLE -> stopwatchToggle(app, store)
                    ACTION_SW_LAP -> stopwatchLap(app, store)
                    ACTION_SW_RESET -> {
                        store.putStopwatch(Stopwatch())
                        Notifications.hideStopwatch(app)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun timerToggle(context: Context, store: ClockStore) {
        val t = store.timerNow() ?: return
        val now = SystemClock.elapsedRealtime()
        val next = when (t.state) {
            TimerState.RUNNING -> t.copy(state = TimerState.PAUSED, pausedRemaining = t.remaining(now))
            else -> t.copy(state = TimerState.RUNNING, deadlineElapsedMillis = now + t.pausedRemaining.toMillis())
        }
        store.putTimer(next)
        TimerScheduler.sync(context, next)
    }

    private suspend fun timerAdd(context: Context, store: ClockStore) {
        val t = store.timerNow() ?: return
        val next = t.withAddedSeconds(60)
        store.putTimer(next)
        TimerScheduler.sync(context, next)
    }

    private suspend fun stopwatchToggle(context: Context, store: ClockStore) {
        val sw = store.stopwatch.first()
        val now = SystemClock.elapsedRealtime()
        val next = if (sw.running) {
            sw.copy(running = false, accumulated = sw.elapsed(now))
        } else {
            sw.copy(running = true, startedAtElapsed = now)
        }
        store.putStopwatch(next)
        Notifications.showStopwatch(context, next)
    }

    private suspend fun stopwatchLap(context: Context, store: ClockStore) {
        val sw = store.stopwatch.first()
        if (!sw.running) return
        val total = sw.elapsed(SystemClock.elapsedRealtime())
        val previous = sw.laps.firstOrNull()?.total ?: Duration.ZERO
        val next = sw.copy(laps = listOf(Lap(sw.laps.size + 1, total.minus(previous), total)) + sw.laps)
        store.putStopwatch(next)
        Notifications.showStopwatch(context, next)
    }

    companion object {
        const val ACTION_SNOOZE = "com.wakr.app.SNOOZE"
        const val ACTION_EMERGENCY = "com.wakr.app.EMERGENCY_STOP"
        const val ACTION_DISMISS = "com.wakr.app.DISMISS"
        const val ACTION_TIMER_TOGGLE = "com.wakr.app.TIMER_TOGGLE"
        const val ACTION_TIMER_ADD = "com.wakr.app.TIMER_ADD"
        const val ACTION_TIMER_CANCEL = "com.wakr.app.TIMER_CANCEL"
        const val ACTION_SW_TOGGLE = "com.wakr.app.SW_TOGGLE"
        const val ACTION_SW_LAP = "com.wakr.app.SW_LAP"
        const val ACTION_SW_RESET = "com.wakr.app.SW_RESET"
    }
}

internal data class RingActionReceipt(val snoozeMinutes: Int?)

internal suspend fun finishRing(context: Context, id: Long, at: Long, action: String): RingActionReceipt? {
    val snooze = action == ClockActionReceiver.ACTION_SNOOZE
    val emergency = action == ClockActionReceiver.ACTION_EMERGENCY
    val fallback = RuntimeAlarms.settings(context).alarms.snoozeMinutes
    val now = System.currentTimeMillis()
    var handled: Alarm? = null
    val updated = RuntimeAlarms.update(context) { alarms -> alarms.mapNotNull { alarm ->
        if (alarm.id != id || alarm.ringingAt != at) return@mapNotNull alarm
        val due = now + alarm.snoozeMinutes(fallback) * 60_000L
        val result = if (emergency) alarm.finishEmergencyOccurrence(id, at)
            else alarm.finishUserOccurrence(id, at, due.takeIf { snooze })
        if (result !== alarm) handled = alarm
        result
    } }
    val previous = handled ?: return null
    AlarmService.stopOccurrence(id, at)
    val retained = updated.firstOrNull { it.id == id }
    if (retained == null) AlarmScheduler.cancel(context, id) else AlarmScheduler.schedule(context, retained)
    if (emergency) {
        WakeChecks.recordEmergency(context, previous, at, now)
    } else if (!snooze) WakeChecks.onDismissed(context, previous, at, now)
    return RingActionReceipt(previous.snoozeMinutes(fallback).takeIf { snooze })
}

internal fun Alarm.snoozeMinutes(fallback: Int): Int =
    (wake.snoozeDurationMin ?: fallback).coerceIn(1, 30)
