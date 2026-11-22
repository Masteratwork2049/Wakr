package com.wakr.app.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import com.wakr.app.MainActivity
import com.wakr.app.R
import com.wakr.app.core.ClockTimer
import com.wakr.app.core.Stopwatch
import com.wakr.app.core.TimerState
import com.wakr.app.core.clockFormat

/**
 * Every channel and every notification the app posts.
 *
 * ## The timer and stopwatch notifications carry no service
 *
 * They do not need one. A countdown is a *deadline*, not a process: the remaining time is
 * `deadline − elapsedRealtime()`, correct whether or not this app has run since. So the ongoing
 * notification is posted once with `setUsesChronometer` and the system renders the ticking digits
 * itself, at zero cost to the app, for as long as the notification exists. Notifications outlive
 * the process that posted them. A foreground service would burn a wakelock to recompute a number
 * the platform can derive from a timestamp.
 *
 * `setChronometerCountDown(true)` is what makes the timer count *down* rather than up; it needs
 * API 24 and the app's floor is 26. The expiry itself is an exact alarm, so nothing has to be
 * awake to notice it.
 *
 * The ringer is the one thing that does take a foreground service, because looping audio is real
 * ongoing work (see [AlarmService]).
 */
object Notifications {

    const val CHANNEL_ALARM = "alarm"
    const val CHANNEL_TIMER = "timer"
    const val CHANNEL_STOPWATCH = "stopwatch"

    const val ID_RINGING = 1
    const val ID_TIMER = 2
    const val ID_STOPWATCH = 3

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService<NotificationManager>() ?: return

        // No sound on the channel: the ringer plays its own looping audio, and a channel sound
        // would fire a second, one-shot copy over the top of it.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A ringing alarm"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TIMER, "Timers", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A running timer"
                setSound(null, null)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STOPWATCH, "Stopwatch", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A running stopwatch"
                setSound(null, null)
            }
        )
    }

    /* ── Timer ──────────────────────────────────────────────────────────────────────────────── */

    fun showTimer(context: Context, timer: ClockTimer) {
        if (timer.state == TimerState.FINISHED) {
            post(context, ID_TIMER, finishedTimer(context, timer), tag = timerTag(timer.id))
            return
        }
        val running = timer.state == TimerState.RUNNING
        val b = NotificationCompat.Builder(context, CHANNEL_TIMER)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(timer.label.ifBlank { "Timer" })
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(context, TAB_TIMERS))
            .addAction(
                0,
                if (running) "Pause" else "Resume",
                timerAction(context, ClockActionReceiver.ACTION_TIMER_TOGGLE, timer.id),
            )
            .addAction(0, "+1 min", timerAction(context, ClockActionReceiver.ACTION_TIMER_ADD, timer.id))
            .addAction(0, "Cancel", timerAction(context, ClockActionReceiver.ACTION_TIMER_CANCEL, timer.id))

        if (running) {
            // The platform ticks this. `when` is the moment it reaches zero.
            b.setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setWhen(System.currentTimeMillis() + timer.remaining(SystemClock.elapsedRealtime()).toMillis())
                .setShowWhen(true)
        } else {
            b.setUsesChronometer(false)
                .setShowWhen(false)
                .setContentText("Paused · ${timer.pausedRemaining.clockFormat()}")
        }
        post(context, ID_TIMER, b.build(), tag = timerTag(timer.id))
    }

    private fun finishedTimer(context: Context, timer: ClockTimer): android.app.Notification {
        val stop = timerAction(context, ClockActionReceiver.ACTION_TIMER_CANCEL, timer.id)
        return NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(timer.label)
            .setContentText("Time’s up")
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setGroup("timer-alerts")
            .setOnlyAlertOnce(true)
            .setDeleteIntent(stop)
            .setContentIntent(openExpiredTimer(context, timer.id))
            .addAction(0, "Dismiss", stop)
            .build()
    }

    internal fun timerChimeSummary(context: Context, timer: ClockTimer, openFullScreen: Boolean = true): android.app.Notification {
        val showFullScreen = openFullScreen && canPresentTimerAlert(context)
        return NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(timer.label)
            .setContentText("Time’s up")
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setGroup("timer-alerts")
            .setGroupSummary(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setOnlyAlertOnce(!showFullScreen)
            .apply {
                if (showFullScreen) setFullScreenIntent(TimerRingActivity.pendingIntent(context, timer.id, automatic = true), true)
            }
            .setContentIntent(openExpiredTimer(context, timer.id))
            .addAction(0, "Dismiss", timerAction(context, ClockActionReceiver.ACTION_TIMER_CANCEL, timer.id))
            .build()
    }

    private fun openExpiredTimer(context: Context, id: Long): PendingIntent =
        if (canPresentTimerAlert(context)) TimerRingActivity.pendingIntent(context, id)
        else openApp(context, TAB_TIMERS)

    internal fun timerAction(context: Context, action: String, id: Long): PendingIntent =
        PendingIntent.getBroadcast(context, 20,
            Intent(context, ClockActionReceiver::class.java).setAction(action)
                .setData(android.net.Uri.parse("wakr://timer/$id")).putExtra(AlarmReceiver.EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun timerTag(id: Long) = "timer-$id"

    fun hideTimer(context: Context, id: Long) {
        NotificationManagerCompat.from(context).cancel(timerTag(id), ID_TIMER)
    }

    fun hideLegacyTimer(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_TIMER)
    }

    /* ── Stopwatch ──────────────────────────────────────────────────────────────────────────── */

    fun showStopwatch(context: Context, sw: Stopwatch) {
        val b = NotificationCompat.Builder(context, CHANNEL_STOPWATCH)
            .setSmallIcon(R.drawable.ic_stat_stopwatch)
            .setContentTitle("Stopwatch")
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(context, TAB_STOPWATCH))
            .addAction(
                0,
                if (sw.running) "Stop" else "Start",
                broadcast(context, ClockActionReceiver.ACTION_SW_TOGGLE, 30),
            )
            .addAction(
                0,
                if (sw.running) "Lap" else "Reset",
                broadcast(
                    context,
                    if (sw.running) ClockActionReceiver.ACTION_SW_LAP else ClockActionReceiver.ACTION_SW_RESET,
                    31,
                ),
            )

        if (sw.running) {
            // Counting up: `when` is the instant it started, which the platform subtracts from now.
            b.setUsesChronometer(true)
                .setWhen(System.currentTimeMillis() - sw.elapsed(SystemClock.elapsedRealtime()).toMillis())
                .setShowWhen(true)
        } else {
            b.setUsesChronometer(false)
                .setShowWhen(false)
                .setContentText(sw.accumulated.clockFormat(withHours = true))
        }
        post(context, ID_STOPWATCH, b.build())
    }

    fun hideStopwatch(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_STOPWATCH)
    }

    /* ── Plumbing ───────────────────────────────────────────────────────────────────────────── */

    const val TAB_ALARMS = "alarms"
    const val TAB_TIMERS = "timers"
    const val TAB_STOPWATCH = "stopwatch"
    const val TAB_MORNING = "morning"
    const val EXTRA_TAB = "tab"

    fun openApp(context: Context, tab: String): PendingIntent = PendingIntent.getActivity(
        context,
        tab.hashCode(),
        Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_TAB, tab),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun broadcast(context: Context, action: String, requestCode: Int, id: Long = 0L, at: Long? = null): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, ClockActionReceiver::class.java).setAction(action)
                .apply { if (at != null) { data = android.net.Uri.parse("wakr://ring/$id/$at"); putExtra(UpcomingAlarm.AT, at) } }
                .putExtra(AlarmReceiver.EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * Posts, unless the user said no.
     *
     * `POST_NOTIFICATIONS` is a runtime permission from API 33 and every one of these calls sits
     * on a path that must not throw, whether a receiver, a service or a coroutine with no UI.
     * Checking here once is cheaper than a try/catch at every call site and it fails the way it
     * should: the alarm still rings, it is just not announced.
     */
    private fun post(context: Context, id: Int, n: android.app.Notification, tag: String? = null) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        try { nm.notify(tag, id, n) }
        catch (_: SecurityException) { /* Permission may be revoked between check and post. */ }
        catch (error: RuntimeException) { android.util.Log.w("ClockNotification", "Unable to post notification", error) }
    }
}
