package com.wakr.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.wakr.app.R
import com.wakr.app.core.Alarm
import com.wakr.app.core.WakeOutcome
import com.wakr.app.core.WakeUpLog
import com.wakr.app.data.ClockStore
import com.wakr.app.data.encodeAlarms
import com.wakr.app.data.parseAlarms
import com.wakr.app.data.toJson
import com.wakr.app.data.toWakeUpLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class WakeCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                WakeChecks.handle(context.applicationContext, intent.getLongExtra("id", -1L), intent.action.orEmpty())
            } finally { pending.finish() }
        }
    }
}

/** Follow-up checks own a private alarm snapshot, so a temporary alarm can leave the list immediately. */
internal object WakeChecks {
    private const val PROMPT = "wakr.wake.prompt"
    private const val EXPIRE = "wakr.wake.expire"
    private const val CONFIRM = "wakr.wake.confirm"
    private val mutex = Mutex()

    private data class Check(val alarm: Alarm, val due: Long, val dismissed: Long, val remaining: Int, val prompted: Boolean = false, val passedCount: Int = 0) {
        fun encode() = JSONObject().put("alarm", encodeAlarms(listOf(alarm))).put("due", due)
            .put("dismissed", dismissed).put("remaining", remaining).put("prompted", prompted).put("passedCount", passedCount).toString()
    }

    private fun decode(raw: String): Check? = runCatching {
        val json = JSONObject(raw)
        Check(parseAlarms(json.getString("alarm")).single(), json.getLong("due"), json.getLong("dismissed"),
            json.getInt("remaining"), json.getBoolean("prompted"), json.optInt("passedCount"))
    }.getOrNull()

    internal fun log(alarm: Alarm, at: Long, dismissed: Long?, outcome: WakeOutcome, passed: Boolean? = null): WakeUpLog {
        val first = alarm.firstRingAt ?: at
        return WakeUpLog(first xor (alarm.id shl 32), alarm.id, alarm.label, first, first, dismissed,
            alarm.snoozeCount, passed, outcome == WakeOutcome.DISMISSED && alarm.snoozeCount == 0 && passed != false, outcome,
            missionMuteCount = alarm.missionMuteCount)
    }

    private fun preferences(c: Context) = c.getSharedPreferences("wake_checks", Context.MODE_PRIVATE)
    private fun idIntent(c: Context, id: Long, action: String) = PendingIntent.getBroadcast(c, 0,
        Intent(c, WakeCheckReceiver::class.java).setAction(action).setData(Uri.parse("wakr://check/$id/$action")).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun tag(id: Long) = "wake-check:$id"
    private fun id(check: Check) = log(check.alarm, check.dismissed, check.dismissed, WakeOutcome.DISMISSED).id

    private fun schedule(c: Context, check: Check) {
        val manager = c.getSystemService(AlarmManager::class.java)
        val action = idIntent(c, id(check), if (check.prompted) EXPIRE else PROMPT)
        val due = maxOf(check.due, System.currentTimeMillis() + 500)
        if (AlarmScheduler.canScheduleExact(c)) manager.setAlarmClock(AlarmManager.AlarmClockInfo(due, Notifications.openApp(c, Notifications.TAB_MORNING)), action)
        else manager.setWindow(AlarmManager.RTC_WAKEUP, due, 60_000, action)
    }

    suspend fun onDismissed(c: Context, alarm: Alarm, at: Long, now: Long): Unit = mutex.withLock {
        if (alarm.isWakeCheckRetry || !RuntimeAlarms.unlocked(c)) return
        ClockStore(c).putWakeLog(log(alarm, at, now, WakeOutcome.DISMISSED))
        if (alarm.wake.stillAwakeChecksCount <= 0) return
        save(c, Check(alarm, now + alarm.wake.stillAwakeDelayMin.coerceIn(3, 15) * 60_000, now,
            alarm.wake.stillAwakeChecksCount))
    }

    suspend fun recordEmergency(c: Context, alarm: Alarm, at: Long, now: Long) = mutex.withLock {
        val entry = log(alarm, at, now, WakeOutcome.EMERGENCY_STOP)
        if (RuntimeAlarms.unlocked(c)) {
            ClockStore(c).putWakeLog(entry)
        } else {
            c.createDeviceProtectedStorageContext().getSharedPreferences("emergency_stops", Context.MODE_PRIVATE)
                .edit().putString(entry.id.toString(), entry.copy(alarmLabel = "").toJson().toString()).commit()
        }
        Unit
    }

    private fun save(c: Context, check: Check) {
        preferences(c).edit().putString(id(check).toString(), check.encode()).apply()
        schedule(c, check)
    }

    suspend fun restore(c: Context) = mutex.withLock {
        preferences(c).all.values.mapNotNull { (it as? String)?.let(::decode) }.forEach { schedule(c, it) }
        val emergency = c.createDeviceProtectedStorageContext().getSharedPreferences("emergency_stops", Context.MODE_PRIVATE)
        val entries = emergency.all
        val store = ClockStore(c)
        for ((key, raw) in entries) {
            runCatching { JSONObject(raw as String).toWakeUpLog() }.getOrNull()?.let { store.putWakeLog(it) }
            emergency.edit().remove(key).apply()
        }
    }

    suspend fun handle(c: Context, token: Long, action: String): Unit = mutex.withLock {
        val prefs = preferences(c)
        val check = prefs.getString(token.toString(), null)?.let(::decode) ?: return
        val manager = NotificationManagerCompat.from(c)
        val now = System.currentTimeMillis()
        if (action == PROMPT && !check.prompted) {
            val confirm = idIntent(c, token, CONFIRM)
            Notifications.ensureChannels(c)
            val notification = NotificationCompat.Builder(c, Notifications.CHANNEL_ALARM)
                .setSmallIcon(R.drawable.ic_stat_alarm).setContentTitle("Still awake?")
                .setContentText(check.alarm.label.ifBlank { "Alarm" }).setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM).setContentIntent(confirm)
                .addAction(0, "I'm awake", confirm).setTimeoutAfter(60_000).build()
            runCatching { manager.notify(tag(token), 0, notification) }
            save(c, check.copy(due = now + 60_000, prompted = true))
            return
        }
        if (!check.prompted || (action != EXPIRE && action != CONFIRM)) return
        manager.cancel(tag(token), 0)
        val confirmed = action == CONFIRM && now < check.due
        val passedCount = check.passedCount + if (confirmed) 1 else 0
        val hasMore = confirmed && check.remaining > 1
        if (hasMore) {
            save(c, check.copy(due = now + check.alarm.wake.stillAwakeDelayMin.coerceIn(3, 15) * 60_000,
                remaining = check.remaining - 1, prompted = false, passedCount = passedCount))
        } else {
            prefs.edit().remove(token.toString()).apply()
        }
        val outcome = if (confirmed) WakeOutcome.DISMISSED else WakeOutcome.STILL_AWAKE_FAILED
        val store = ClockStore(c)
        store.putWakeLog(log(check.alarm, check.dismissed, check.dismissed, outcome, confirmed.takeUnless { hasMore })
            .copy(completedWakeChecks = check.passedCount + 1, passedWakeChecks = passedCount))
        if (!confirmed) {
            val due = now + 1800
            val retry = check.alarm.copy(id = store.nextId(), enabled = true, pausedByGroup = false, days = emptySet(),
                ringingAt = null, firstRingAt = null, missionMuteCount = null, missionIndex = 0, missedRetries = 0, snoozeCount = 0,
                rebootCount = 0, skippedOccurrenceDate = null, skippedOccurrenceMillis = null, snoozedUntilMillis = null,
                isWakeCheckRetry = true, wake = check.alarm.wake.copy(relativeTriggerMillis = due, scheduledDateEpochDay = null,
                    pausedUntilEpochDay = null, deleteAfterDismiss = true, volumePercent = 100, fadeInSeconds = 0,
                    maxSnoozes = 0, stillAwakeChecksCount = 0, silenceAfterMin = 0))
            RuntimeAlarms.update(c) { it + retry }
            AlarmScheduler.schedule(c, retry)
        }
    }
}
