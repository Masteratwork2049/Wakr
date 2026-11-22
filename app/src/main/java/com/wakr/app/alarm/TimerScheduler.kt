package com.wakr.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.getSystemService
import com.wakr.app.core.ClockTimer
import com.wakr.app.core.TimerState

/** Keeps each timer's independently addressed wakeup and notification in sync with storage. */
object TimerScheduler {
    fun sync(context: Context, id: Long, timer: ClockTimer?) {
        val am = context.getSystemService<AlarmManager>() ?: return
        val callback = expiryIntent(context, id)
        am.cancel(callback)
        if (timer == null) {
            Notifications.hideTimer(context, id)
            return
        }
        if (timer.state == TimerState.RUNNING) {
            // Alarm-clock delivery avoids the per-app allow-while-idle quota for short timers.
            val delay = (timer.deadlineElapsedMillis - SystemClock.elapsedRealtime()).coerceAtLeast(250)
            if (AlarmScheduler.canScheduleExact(context)) {
                val show = Notifications.openApp(context, Notifications.TAB_TIMERS)
                am.setAlarmClock(AlarmManager.AlarmClockInfo(System.currentTimeMillis() + delay, show), callback)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delay, callback)
            }
        }
        Notifications.showTimer(context, timer)
    }

    fun removeLegacyTimer(context: Context) {
        val callback = PendingIntent.getBroadcast(context, 900,
            Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_TIMER_EXPIRED),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (callback != null) context.getSystemService<AlarmManager>()?.cancel(callback)
        Notifications.hideLegacyTimer(context)
    }

    private fun expiryIntent(context: Context, id: Long): PendingIntent = PendingIntent.getBroadcast(
        context, 900,
        Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_TIMER_EXPIRED)
            .setData(Uri.parse("wakr://timer/$id"))
            .putExtra(AlarmReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
