package com.wakr.app.alarm

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.wakr.app.core.ClockTimer
import com.wakr.app.core.MAX_TIMERS
import com.wakr.app.core.TimerState
import com.wakr.app.core.withAddedSeconds
import com.wakr.app.data.ClockStore
import java.time.Duration
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serializes timer writes and their platform effects across screens, broadcasts and recovery. */
internal object TimerRuntime {
    private val mutex = Mutex()

    suspend fun start(context: Context, duration: Duration, name: String): Boolean = transaction {
        if (duration.isZero || duration.isNegative) return@transaction false
        val store = ClockStore(context)
        val timers = store.timersNow()
        if (timers.size >= MAX_TIMERS) return@transaction false
        val nameOverride = name.trim()
        val timer = ClockTimer(store.nextId(), nameOverride, duration, TimerState.RUNNING,
            SystemClock.elapsedRealtime() + duration.toMillis(),
            usesDefaultLabel = nameOverride.isEmpty()).withCurrentDeadline(context)
        val saved = store.updateTimers { it + timer }.last()
        TimerScheduler.sync(context, saved.id, saved)
        true
    }

    suspend fun toggle(context: Context, id: Long) = change(context, id) { timer, now ->
        when (timer.state) {
            TimerState.RUNNING -> if (timer.deadlineElapsedMillis <= now) timer else
                timer.copy(state = TimerState.PAUSED, pausedRemaining = timer.remaining(now), wallDeadlineMillis = 0L)
            TimerState.PAUSED -> timer.copy(state = TimerState.RUNNING,
                deadlineElapsedMillis = now + timer.pausedRemaining.toMillis()).withCurrentDeadline(context)
            else -> timer
        }
    }

    suspend fun add(context: Context, id: Long, seconds: Long) = change(context, id) { timer, now ->
        when {
            timer.state == TimerState.PAUSED -> timer.withAddedSeconds(seconds, now)
            timer.state == TimerState.RUNNING && timer.deadlineElapsedMillis > now ->
                timer.withAddedSeconds(seconds, now).withCurrentDeadline(context)
            else -> timer
        }
    }

    suspend fun rename(context: Context, id: Long, name: String) = transaction {
        val store = ClockStore(context)
        val current = store.timersNow().find { it.id == id } ?: return@transaction
        val label = name.trim().take(40)
        val updated = current.copy(label = label, usesDefaultLabel = label.isEmpty())
        if (updated == current || updated.usesDefaultLabel && current.usesDefaultLabel) return@transaction
        val saved = store.updateTimers { timers ->
            timers.map { if (it.id == id) updated else it }
        }.first { it.id == id }
        Notifications.showTimer(context, saved)
        AlarmService.refreshTimerChime()
    }

    suspend fun cancel(context: Context, id: Long) = transaction {
        val store = ClockStore(context)
        val before = store.timersNow().associateBy { it.id }
        if (id !in before) return@transaction
        val remaining = store.updateTimers { timers -> timers.filterNot { it.id == id } }
        TimerScheduler.sync(context, id, null)
        remaining.filter { it.label != before[it.id]?.label }.forEach { Notifications.showTimer(context, it) }
        AlarmService.refreshTimerChime()
    }

    /** While the app is visible, wake at the countdown deadline without waiting for a broadcast. */
    suspend fun watchDeadlines(context: Context) {
        ClockStore(context).timers.map { timers ->
            timers.filter { it.state == TimerState.RUNNING }.minOfOrNull { it.deadlineElapsedMillis }
        }.distinctUntilChanged().collectLatest { deadline ->
            if (deadline == null) return@collectLatest
            var remaining = deadline - SystemClock.elapsedRealtime()
            while (remaining > 0L) {
                delay(remaining)
                remaining = deadline - SystemClock.elapsedRealtime()
            }
            expire(context, -1L)
        }
    }

    suspend fun expire(context: Context, id: Long) = transaction {
        val store = ClockStore(context)
        val now = SystemClock.elapsedRealtime()
        val expired = store.timersNow().filter {
            (it.id == id || id == -1L) && it.state == TimerState.RUNNING && it.deadlineElapsedMillis <= now
        }.map { it.copy(state = TimerState.FINISHED, pausedRemaining = Duration.ZERO, chimePending = true) }
        if (expired.isEmpty()) {
            store.timersNow().firstOrNull { it.id == id && it.state == TimerState.RUNNING }
                ?.let { TimerScheduler.sync(context, it.id, it) }
            return@transaction
        }
        val byId = expired.associateBy { it.id }
        store.updateTimers { timers -> timers.map { byId[it.id] ?: it } }
        try {
            ContextCompat.startForegroundService(context, Intent(context, AlarmService::class.java)
                .setAction(AlarmService.ACTION_START_TIMER))
        } catch (error: IllegalStateException) {
            android.util.Log.w("TimerRuntime", "Unable to start timer audio", error)
        } finally {
            expired.forEach { TimerScheduler.sync(context, it.id, it) }
        }
    }

    suspend fun silence(context: Context, id: Long) = change(context, id) { timer, _ ->
        timer.copy(chimePending = false)
    }

    suspend fun restore(context: Context) = transaction {
        val store = ClockStore(context)
        val bootCount = bootCount(context)
        val elapsed = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val timers = store.updateTimers { saved ->
            saved.map { timer ->
                val bootChanged = timer.bootCount >= 0 && bootCount >= 0 && timer.bootCount != bootCount &&
                    timer.wallDeadlineMillis > 0
                when {
                    timer.state == TimerState.FINISHED && bootChanged -> timer.copy(
                        deadlineElapsedMillis = elapsed + (timer.wallDeadlineMillis - wall).coerceAtMost(0),
                        bootCount = bootCount,
                    )
                    timer.state == TimerState.RUNNING -> {
                        val deadline = if (bootChanged) elapsed + timer.wallDeadlineMillis - wall else timer.deadlineElapsedMillis
                        timer.copy(deadlineElapsedMillis = deadline, wallDeadlineMillis = wall + deadline - elapsed,
                            bootCount = bootCount)
                    }
                    else -> timer
                }
            }
        }
        TimerScheduler.removeLegacyTimer(context)
        timers.forEach { TimerScheduler.sync(context, it.id, it) }
    }

    private suspend fun change(context: Context, id: Long, transform: (ClockTimer, Long) -> ClockTimer) = transaction {
        val store = ClockStore(context)
        val old = store.timersNow().find { it.id == id } ?: return@transaction
        val updated = transform(old, SystemClock.elapsedRealtime())
        if (updated == old) return@transaction
        store.updateTimers { timers -> timers.map { if (it.id == id) updated else it } }
        TimerScheduler.sync(context, id, updated)
    }

    private suspend fun <T> transaction(action: suspend () -> T): T =
        mutex.withLock { withContext(NonCancellable) { action() } }

    private fun ClockTimer.withCurrentDeadline(context: Context) = copy(
        wallDeadlineMillis = System.currentTimeMillis() + remaining(SystemClock.elapsedRealtime()).toMillis(),
        bootCount = bootCount(context),
    )

    private fun bootCount(context: Context) = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
}
