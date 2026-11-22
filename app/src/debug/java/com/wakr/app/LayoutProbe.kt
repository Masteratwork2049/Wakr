package com.wakr.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import com.wakr.app.alarm.Notifications
import com.wakr.app.alarm.TimerScheduler
import com.wakr.app.core.*
import com.wakr.app.data.ClockStore
import com.wakr.app.data.toClockTimer
import com.wakr.app.data.toTimerJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration

/** Explicit debug-only ADB fixture. Snapshot/restore Timer and Stopwatch, including laps. */
class LayoutProbe : Instrumentation() {
    private var args = Bundle()
    override fun onCreate(arguments: Bundle?) { args = arguments ?: Bundle(); super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        try {
            runBlocking {
                val ctx = targetContext
                val store = ClockStore(ctx)
                val backup = java.io.File(ctx.filesDir, "layout-probe-snapshot.json")
                suspend fun replaceTimers(timers: List<ClockTimer>) {
                    val previous = store.timersNow()
                    store.updateTimers { timers }
                    previous.filterNot { old -> timers.any { it.id == old.id } }.forEach {
                        TimerScheduler.sync(ctx, it.id, null)
                    }
                    timers.forEach { TimerScheduler.sync(ctx, it.id, it) }
                }
                val command = args.getString("command") ?: "status"
                if (command in listOf("timer", "stopwatch")) {
                    if (!backup.exists()) {
                        val sw = store.stopwatch.first()
                        val snapshot = JSONObject().put("timers", JSONArray().apply { store.timersNow().forEach { put(it.toTimerJson()) } })
                            .put("running", sw.running).put("started", sw.startedAtElapsed).put("accumulated", sw.accumulated.toMillis())
                            .put("laps", JSONArray().apply { sw.laps.forEach { put(JSONObject().put("index", it.index).put("split", it.split.toMillis()).put("total", it.total.toMillis())) } })
                        backup.writeText(snapshot.toString())
                    }
                    val seconds = (args.getString("seconds") ?: "3600").toLong().coerceIn(60, 3_600_000)
                    val duration = Duration.ofSeconds(seconds)
                    val timer = if (command == "timer") ClockTimer(-17017, "ADB layout probe", duration, TimerState.RUNNING,
                        SystemClock.elapsedRealtime() + duration.toMillis()) else null
                    replaceTimers(listOfNotNull(timer))
                    val sw = if (command == "stopwatch") Stopwatch(true, SystemClock.elapsedRealtime(), duration) else Stopwatch()
                    store.putStopwatch(sw)
                    if (sw.running) Notifications.showStopwatch(ctx, sw) else Notifications.hideStopwatch(ctx)
                } else if (command == "restore" && backup.exists()) {
                    val j = JSONObject(backup.readText())
                    val timers = j.optJSONArray("timers")?.let { rows ->
                        List(rows.length()) { rows.getJSONObject(it).toClockTimer() }
                    } ?: listOfNotNull(j.optJSONObject("timer")?.let {
                        it.put("paused", it.getLong("remaining")).toClockTimer()
                    })
                    val laps = j.getJSONArray("laps")
                    val sw = Stopwatch(j.getBoolean("running"), j.getLong("started"), Duration.ofMillis(j.getLong("accumulated")),
                        (0 until laps.length()).map { laps.getJSONObject(it).let { Lap(it.getInt("index"), Duration.ofMillis(it.getLong("split")), Duration.ofMillis(it.getLong("total"))) } })
                    replaceTimers(timers); store.putStopwatch(sw)
                    if (sw.running || !sw.accumulated.isZero) Notifications.showStopwatch(ctx, sw) else Notifications.hideStopwatch(ctx)
                    check(backup.delete())
                }
                result.putString("stream", "timer=${store.timersNow()}\nstopwatch=${store.stopwatch.first()}\n")
            }
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) { result.putString("stream", error.stackTraceToString()); finish(Activity.RESULT_CANCELED, result) }
    }
}
