package com.wakr.app.ui

import com.wakr.app.core.appendTimerDigits

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameMillis
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wakr.app.alarm.AlarmScheduler
import com.wakr.app.alarm.Notifications
import com.wakr.app.alarm.TimerRuntime
import com.wakr.app.core.Alarm
import com.wakr.app.core.ClockTimer
import com.wakr.app.core.Lap
import com.wakr.app.core.Stopwatch
import com.wakr.app.core.WorldCity
import com.wakr.app.core.preservingDismissal
import com.wakr.app.data.ClockSettings
import com.wakr.app.data.ClockStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.map
import com.wakr.app.core.isPreRingProtected
import com.wakr.app.core.missionsRemaining
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId

/**
 * A frame-paced clock, for anything that has to look continuous.
 *
 * `withFrameMillis` rather than a fixed `delay`: the stopwatch shows hundredths, and a counter
 * sampled on its own schedule beats against the display's refresh and visibly stutters. Tying the
 * read to Compose's own frame clock gives one sample per drawn frame, which is both the minimum
 * needed and the maximum useful. It also stops on its own when the composition leaves the screen.
 */
@Composable
fun rememberElapsedTicker(active: Boolean = true): State<Long> =
    produceState(initialValue = SystemClock.elapsedRealtime(), active) {
        while (active) {
            withFrameMillis { }
            value = SystemClock.elapsedRealtime()
        }
    }

/**
 * A wall-clock ticker at [periodMillis].
 *
 * The world clock and the alarm list change once a second at most, and waking every frame to
 * re-derive six time zones would burn battery for nothing. It re-aligns to the second boundary
 * each tick so the displayed seconds turn over when they should rather than drifting.
 */
@Composable
fun rememberWallTicker(periodMillis: Long = 1000L): State<Long> =
    produceState(initialValue = System.currentTimeMillis(), periodMillis) {
        while (true) {
            val now = System.currentTimeMillis()
            value = now
            delay(periodMillis - (now % periodMillis))
        }
    }

/**
 * The whole app's state, read from and written to [ClockStore].
 *
 * Nothing is held in memory as the source of truth. Every mutation writes the store and the flows
 * come back around. That is what makes a notification button and an on-screen button the same
 * operation, and what makes the app correct after being killed. The cost is one extra hop per
 * press, which for a clock is free.
 *
 * Scheduling is a *consequence* of a write, not a parallel path: `AlarmScheduler` and
 * `TimerScheduler` are called with the value that was just persisted, so the store and
 * `AlarmManager` cannot disagree.
 */
class ClockViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ClockStore(app)
    private val ctx get() = getApplication<Application>()

    private val initialSettings: ClockSettings = runBlocking {
        store.settingsNow()
    }

    val settings: StateFlow<ClockSettings> =
        store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, initialSettings)
    val alarms: StateFlow<List<Alarm>> =
        store.alarms.map { list -> list.filterNot { it.isWakeCheckRetry } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val wakeHistory = store.wakeHistory
    val cities: StateFlow<List<WorldCity>> =
        store.cities.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val timers: StateFlow<List<ClockTimer>> =
        store.timers.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _stopwatch = MutableStateFlow(Stopwatch())
    val stopwatch: StateFlow<Stopwatch> = _stopwatch.asStateFlow()

    private val _isSettingsLoaded = MutableStateFlow(false)
    val isSettingsLoaded: StateFlow<Boolean> = _isSettingsLoaded.asStateFlow()

    val homeZone: ZoneId = ZoneId.systemDefault()

    init {
        viewModelScope.launch {
            store.settings.collect {
                _isSettingsLoaded.value = true
            }
        }
        viewModelScope.launch {
            store.stopwatch.collect { sw ->
                _stopwatch.value = sw
            }
        }
        // The first run has never scheduled anything, and a reinstall wipes AlarmManager. Doing it
        // on launch is cheap and covers both without a separate first-run flag.
        viewModelScope.launch {
            Notifications.ensureChannels(ctx)
            AlarmScheduler.scheduleAll(ctx, com.wakr.app.alarm.RuntimeAlarms.read(ctx))
            TimerRuntime.restore(ctx)
        }
    }

    /* ── Settings ───────────────────────────────────────────────────────────────────────────── */

    /**
     * Returns Unit, not the Job, so it can be passed where `((ClockSettings) -> ClockSettings) ->
     * Unit` is expected. Nothing else has to be notified: silence-after, snooze length and the
     * timer sound are all read from the store at the moment they are needed, by whichever receiver
     * or service needs them.
     */
    fun updateSettings(block: (ClockSettings) -> ClockSettings) {
        viewModelScope.launch { store.update(block) }
    }

    /* ── Alarms ─────────────────────────────────────────────────────────────────────────────── */

    private data class AlarmPermit(
        val occurrence: Long, val expiresAt: Long, val missions: List<com.wakr.app.core.AlarmMission>, val window: Int,
    )
    private val alarmPermits = mutableMapOf<Long, AlarmPermit>()

    fun needsPreRingChallenge(alarm: Alarm): Boolean {
        val now = java.time.ZonedDateTime.now()
        if (!alarm.isPreRingProtected(now)) return false
        val permit = alarmPermits[alarm.id]
        return permit == null || permit.expiresAt < System.currentTimeMillis() ||
            permit.occurrence != alarm.nextFire(now)?.toInstant()?.toEpochMilli() ||
            permit.missions != alarm.wake.unlockMissions || permit.window != alarm.wake.lockBeforeRingMin
    }

    fun authorizePreRingChanges(alarms: List<Alarm>) {
        val now = java.time.ZonedDateTime.now()
        val expiresAt = System.currentTimeMillis() + 120_000
        alarmPermits.entries.removeAll { it.value.expiresAt < System.currentTimeMillis() }
        alarms.forEach { alarm ->
            alarm.nextFire(now)?.toInstant()?.toEpochMilli()?.let { alarmPermits[alarm.id] = AlarmPermit(it, expiresAt, alarm.wake.unlockMissions, alarm.wake.lockBeforeRingMin) }
        }
    }

    private fun canChange(alarm: Alarm): Boolean =
        !(alarm.ringingAt != null && alarm.missionsRemaining) && !needsPreRingChallenge(alarm)

    fun pauseOrResumeAll() = viewModelScope.launch {
        val updated = store.updateAlarms { alarms ->
            val visible = alarms.filterNot { it.isWakeCheckRetry }
            val pause = visible.none { it.pausedByGroup }
            if (pause && visible.any { it.enabled && !canChange(it) }) alarms
            else alarms.map { if (it.isWakeCheckRetry) it else it.copy(pausedByGroup = pause && it.enabled) }
        }
        AlarmScheduler.scheduleAll(ctx, updated)
    }

    fun skipNext(id: Long) = viewModelScope.launch {
        val now = java.time.ZonedDateTime.now()
        val updated = store.updateAlarms { alarms -> alarms.map { alarm ->
            val next = if (alarm.id == id && canChange(alarm)) alarm.nextFire(now) else null
            if (next == null) alarm else alarm.copy(enabled = !alarm.isOneShot, snoozedUntilMillis = null,
                skippedOccurrenceMillis = next.toInstant().toEpochMilli(),
                skippedOccurrenceDate = next.withZoneSameInstant(alarm.scheduleZone(now.zone)).toLocalDate())
        } }
        updated.firstOrNull { it.id == id }?.let { AlarmScheduler.schedule(ctx, it) }
    }

    fun toggleAlarm(id: Long) = viewModelScope.launch {
        val next = store.updateAlarms { list -> list.map {
            if (it.id == id && canChange(it)) it.copy(ringingAt = null, missedRetries = 0, pausedByGroup = false, enabled = !it.enabled, snoozedUntilMillis = null, skippedOccurrenceMillis = null, skippedOccurrenceDate = null) else it
        } }
        next.firstOrNull { it.id == id }?.let { a ->
            if (a.enabled) AlarmScheduler.schedule(ctx, a) else AlarmScheduler.cancel(ctx, id)
        }
    }

    /** Insert or replace. A null [Alarm.id] of 0 means "new", which is what the sheet sends. */
    fun saveAlarm(draft: Alarm, onSaved: (Alarm) -> Unit = {}) = viewModelScope.launch {
        val id = if (draft.id == 0L) store.nextId() else draft.id
        val saved = draft.copy(id = id, createdAt = if (draft.id == 0L) System.currentTimeMillis() else draft.createdAt)
        var applied: Alarm? = null
        store.updateAlarms { current ->
            val existing = current.firstOrNull { it.id == id }
            if (existing != null && !canChange(existing)) return@updateAlarms current
            val alarm = saved.preservingDismissal(existing)
            applied = alarm
            (if (existing == null) current + alarm else current.map { if (it.id == id) alarm else it })
                .sortedWith(compareBy({ it.time.hour }, { it.time.minute }))
        }
        val alarm = applied ?: return@launch
        AlarmScheduler.schedule(ctx, alarm)
        onSaved(alarm)
    }

    fun deleteAlarm(id: Long) = deleteAlarms(setOf(id))

    fun deleteAlarms(ids: Set<Long>) = viewModelScope.launch {
        val removed = mutableSetOf<Long>()
        store.updateAlarms { alarms ->
            alarms.filterNot { alarm ->
                (alarm.id in ids && canChange(alarm)).also { if (it) removed += alarm.id }
            }
        }
        removed.forEach { AlarmScheduler.cancel(ctx, it) }
    }

    /* ── World clock ────────────────────────────────────────────────────────────────────────── */

    var newlyAddedCity by mutableStateOf<String?>(null)
        private set

    fun finishCityEntrance() { newlyAddedCity = null }

    fun addCity(city: WorldCity) = viewModelScope.launch {
        try {
            store.updateCities { current ->
                if (current.none { it.id == city.id }) {
                    newlyAddedCity = city.id
                    current + city
                } else current
            }
        } catch (error: Exception) {
            newlyAddedCity = null
            throw error
        }
    }

    fun removeCities(ids: Set<String>) = viewModelScope.launch {
        store.updateCities { current -> current.filterNot { it.id in ids } }
    }

    fun moveCity(id: String, anchorId: String, afterAnchor: Boolean) = viewModelScope.launch {
        store.updateCities { current ->
            val city = current.firstOrNull { it.id == id } ?: return@updateCities current
            val others = current.filterNot { it.id == id }
            val anchor = others.indexOfFirst { it.id == anchorId }
            if (anchor < 0) current else others.toMutableList().apply {
                add(anchor + if (afterAnchor) 1 else 0, city)
            }
        }
    }

    /* ── Timer ──────────────────────────────────────────────────────────────────────────────── */

    /**
     * Digits fill from the right the way every phone keypad works: "2", "5" reads as 25 seconds,
     * then a third digit pushes it into minutes. Kept in memory rather than the store, because a
     * half-typed duration is not state worth surviving a reboot.
     */
    var timerDigits: String by mutableStateOf("")
        private set

    val draftDuration: Duration
        get() {
            val d = timerDigits.padStart(6, '0')
            return Duration.ofHours(d.substring(0, 2).toLong())
                .plusMinutes(d.substring(2, 4).toLong())
                .plusSeconds(d.substring(4, 6).toLong())
        }

    fun pressTimerDigits(digits: String) {
        timerDigits = appendTimerDigits(timerDigits, digits)
    }

    fun backspace() {
        timerDigits = timerDigits.dropLast(1)
    }

    fun resetTimerDraft() {
        timerDigits = ""
    }

    /** Select one of the quick timer presets shown beneath the keypad and start it immediately. */
    fun setTimerPreset(totalSeconds: Int) {
        if (totalSeconds <= 0) return
        viewModelScope.launch {
            val seconds = totalSeconds.coerceAtMost(99 * 3600 + 59 * 60 + 59)
            if (TimerRuntime.start(ctx, Duration.ofSeconds(seconds.toLong()), "")) timerDigits = ""
        }
    }

    /** The winder. Sets whole minutes, which is what a spring-wound dial can actually express. */
    fun windToMinutes(minutes: Int) {
        timerDigits = if (minutes <= 0) "" else "%d%02d00".format(minutes / 60, minutes % 60).trimStart('0')
    }

    fun startTimer(label: String = "") = viewModelScope.launch {
        val duration = draftDuration
        if (TimerRuntime.start(ctx, duration, label)) timerDigits = ""
    }

    fun pauseOrResumeTimer(id: Long) = viewModelScope.launch { TimerRuntime.toggle(ctx, id) }

    fun addTenSeconds(id: Long) = viewModelScope.launch { TimerRuntime.add(ctx, id, 10) }

    fun renameTimer(id: Long, name: String) = viewModelScope.launch { TimerRuntime.rename(ctx, id, name) }

    fun cancelTimer(id: Long) = viewModelScope.launch { TimerRuntime.cancel(ctx, id) }

    /* ── Stopwatch ──────────────────────────────────────────────────────────────────────────── */

    fun toggleStopwatch() {
        val sw = _stopwatch.value
        val now = SystemClock.elapsedRealtime()
        val next = if (sw.running) {
            sw.copy(running = false, accumulated = sw.elapsed(now))
        } else {
            sw.copy(running = true, startedAtElapsed = now)
        }
        _stopwatch.value = next
        viewModelScope.launch {
            store.putStopwatch(next)
            Notifications.showStopwatch(ctx, next)
        }
    }

    fun lap() {
        val sw = _stopwatch.value
        if (!sw.running) return
        val total = sw.elapsed(SystemClock.elapsedRealtime())
        val previous = sw.laps.firstOrNull()?.total ?: Duration.ZERO
        // Newest first: the list is read from the top and the freshest lap is the one you took.
        val next = sw.copy(laps = listOf(Lap(sw.laps.size + 1, total.minus(previous), total)) + sw.laps)
        _stopwatch.value = next
        viewModelScope.launch {
            store.putStopwatch(next)
            Notifications.showStopwatch(ctx, next)
        }
    }

    fun resetStopwatch() {
        val next = Stopwatch()
        _stopwatch.value = next
        viewModelScope.launch {
            store.putStopwatch(next)
            Notifications.hideStopwatch(ctx)
        }
    }

    /** Seeds the first-time alarm picker with a sensible tomorrow-morning one-shot. */
    fun blankAlarm(): Alarm = Alarm(
        id = 0L,
        time = LocalTime.of(7, 0),
        label = "",
        days = emptySet<DayOfWeek>(),
        wake = com.wakr.app.core.WakeOptions(repeatMissedCount = settings.value.alarms.defaultMissedAlarmRepeats),
    )
}
