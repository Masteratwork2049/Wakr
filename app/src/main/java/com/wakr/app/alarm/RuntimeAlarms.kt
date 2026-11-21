package com.wakr.app.alarm

import android.content.Context
import android.os.UserManager
import com.wakr.app.core.Alarm
import com.wakr.app.core.WakeOptions
import com.wakr.app.core.ringMissions
import com.wakr.app.data.ClockStore
import com.wakr.app.data.ClockSettings
import com.wakr.app.data.encodeAlarms
import com.wakr.app.data.parseAlarms
import com.wakr.app.data.SILENT
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Only timing and ringer policy are available before first unlock. Labels, missions,
 * content URIs, city lists and wake history stay in credential-protected storage. */
internal object RuntimeAlarms {
    private val mutex = Mutex()
    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("alarm_schedule", Context.MODE_PRIVATE)
    fun unlocked(context: Context) = context.getSystemService(UserManager::class.java).isUserUnlocked

    fun mirror(context: Context, alarm: Alarm) {
        val w = alarm.wake
        val minimal = alarm.copy(label = "", soundUri = alarm.soundUri?.takeIf { it == SILENT },
            missions = emptyList(), privateMissionCount = maxOf(alarm.ringMissions.size, alarm.privateMissionCount), createdAt = null, wake = WakeOptions(
                relativeTriggerMillis = w.relativeTriggerMillis, scheduledDateEpochDay = w.scheduledDateEpochDay, pausedUntilEpochDay = w.pausedUntilEpochDay,
                timeZoneId = w.timeZoneId, deleteAfterDismiss = w.deleteAfterDismiss,
                repeatMissedCount = w.repeatMissedCount, reRingIntervalMin = w.reRingIntervalMin,
                silenceAfterMin = w.silenceAfterMin, flashEnabled = w.flashEnabled,
                flashStrobeEnabled = w.flashStrobeEnabled,
                snoozeDurationMin = w.snoozeDurationMin, maxSnoozes = w.maxSnoozes,
                rebootSurvivalMax = w.rebootSurvivalMax,
                lockBeforeRingMin = w.lockBeforeRingMin,
                muteDurationSec = w.muteDurationSec, emergencyStopEnabled = w.emergencyStopEnabled,
                volumePercent = w.volumePercent, fadeInSeconds = w.fadeInSeconds, vibrationPattern = w.vibrationPattern))
        prefs(context).edit().putString(alarm.id.toString(), encodeAlarms(listOf(minimal))).apply()
    }
    fun forget(context: Context, id: Long) = prefs(context).edit().remove(id.toString()).apply()

    private fun shadow(context: Context) = prefs(context).all.flatMap { (key, value) ->
        if (key.toLongOrNull() != null && value is String) parseAlarms(value) else emptyList()
    }

    private suspend fun reconcile(context: Context, store: ClockStore) {
        val prefs = prefs(context)
        val changes = prefs.all.filterKeys { it.startsWith("pending:") }
        if (changes.isEmpty()) return
        store.updateAlarms { alarms -> alarms.mapNotNull { a ->
            val raw = changes["pending:${a.id}"] as? String ?: return@mapNotNull a
            val result = parseAlarms(raw).singleOrNull() ?: return@mapNotNull null
            a.copy(enabled = result.enabled, snoozedUntilMillis = result.snoozedUntilMillis,
                skippedOccurrenceDate = result.skippedOccurrenceDate, skippedOccurrenceMillis = result.skippedOccurrenceMillis,
                ringingAt = result.ringingAt, missedRetries = result.missedRetries,
                snoozeCount = result.snoozeCount, missionIndex = result.missionIndex,
                firstRingAt = result.firstRingAt, rebootCount = result.rebootCount,
                missionMuteCount = result.missionMuteCount)
        } }
        prefs.edit().also { editor -> changes.keys.forEach(editor::remove) }.apply()
    }

    suspend fun read(context: Context): List<Alarm> = mutex.withLock {
        if (!unlocked(context)) shadow(context)
        else ClockStore(context).let { reconcile(context, it); it.alarmsNow() }
    }

    suspend fun update(context: Context, transform: (List<Alarm>) -> List<Alarm>): List<Alarm> = mutex.withLock {
        if (unlocked(context)) {
            val store = ClockStore(context)
            reconcile(context, store)
            store.updateAlarms(transform)
        } else {
            val before = shadow(context)
            val after = transform(before)
            val editor = prefs(context).edit()
            for (old in before) {
                val new = after.find { it.id == old.id }
                if (new == old) continue
                val value = encodeAlarms(listOfNotNull(new))
                editor.putString("pending:${old.id}", value)
                if (new == null) editor.remove(old.id.toString()) else editor.putString(old.id.toString(), value)
            }
            check(editor.commit()) { "Unable to save direct-boot alarm state" }
            after
        }
    }
    suspend fun settings(context: Context) = if (unlocked(context)) ClockStore(context).settingsNow() else ClockSettings()
}
