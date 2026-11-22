package com.wakr.app.alarm

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import com.wakr.app.core.missOccurrence
import com.wakr.app.core.Alarm
import com.wakr.app.core.AlarmSoundMode
import com.wakr.app.core.VibrationPattern
import com.wakr.app.core.canSnooze
import com.wakr.app.core.missionsRemaining
import com.wakr.app.core.WakeOutcome
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.speech.tts.TextToSpeech
import com.wakr.app.R
import com.wakr.app.data.ClockStore
import com.wakr.app.data.SILENT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The ringer.
 *
 * ## Why this one *is* a foreground service
 *
 * Unlike the timer and stopwatch notifications, which are a timestamp the platform renders, a
 * ringing alarm is genuine ongoing work: a looping `MediaPlayer` and a repeating vibration that
 * must keep running with the screen off and the app long since swapped out. That is what a
 * foreground service is for. It is started from an exact alarm's broadcast, which grants the
 * temporary allowlist that makes a background start legal.
 *
 * The declared type is `mediaPlayback`, an honest description of what it does; there is no
 * alarm-specific type in the enum.
 *
 * ## Audio
 *
 * `USAGE_ALARM` is the whole ballgame. It routes to the alarm stream, so the alarm sounds at alarm
 * volume rather than media volume, respecting system interruption policy. `setLooping` because an alarm that plays a four-second ringtone once is not one.
 *
 * ## Giving up
 *
 * [com.wakr.app.data.AlarmSettings.silenceAfterMinutes] is enforced here rather than by the
 * UI, because by then there may be no UI. When it expires the service stops itself, which is also
 * what makes the notification disappear.
 */
class AlarmService : Service() {

    private val sound by lazy { AlarmAudio(this) }
    private var vibrator: Vibrator? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val starts = Mutex()
    private var active = false
    private var occurrence: Pair<Long, Long>? = null
    private var timerChimeId: Long? = null
    private var torchId: String? = null
    private var silenceJob: Job? = null
    private var volumeGuard: Job? = null
    private var soundUnlockJob: Job? = null
    private var effectsJob: Job? = null
    private var muteExpiryJob: Job? = null
    private var muteOptions = com.wakr.app.core.WakeOptions()
    private var tts: TextToSpeech? = null
    private var shakeListener: SensorEventListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifications.ensureChannels(this)
        when (intent?.action) {
            ACTION_START -> startRinging(intent.getLongExtra(AlarmReceiver.EXTRA_ID, -1L), intent.getLongExtra(UpcomingAlarm.AT, 0), startId)
            ACTION_START_TIMER -> startTimerChime(startId)
            else -> stopSelf(startId)
        }
        // Not sticky: a restarted ringer with no intent has no idea which alarm it is, and an
        // alarm that resurrects itself after the user killed the app is a bug report.
        return START_NOT_STICKY
    }

    private fun startRinging(id: Long, at: Long, startId: Int) {
        scope.launch {
            starts.withLock {
                val alarm = RuntimeAlarms.read(this@AlarmService).firstOrNull { it.id == id }
                if (alarm == null || (at > 0 && alarm.ringingAt != at)) {
                    // A stale start must neither resurrect a deleted alarm nor stop another ringer.
                    if (!active) stopSelf(startId)
                    return@launch
                }
                if (occurrence == (id to at) && active) return@launch
                val settings = RuntimeAlarms.settings(this@AlarmService)
                stopPreview?.invoke()
                resetMute()
                muteOptions = alarm.wake
                timerChimeId = null
                occurrence = id to at
                current = this@AlarmService
                val unlocked = RuntimeAlarms.unlocked(this@AlarmService)
                val uri = if (unlocked) selectRingSound(alarm, at)
                    else alarm.ringSoundUri ?: alarm.soundUri ?: bundledFallback()
                sound.onStarted = null
                if (unlocked && alarm.ringSoundUri == null) rememberPlayedSound(alarm, at, uri)
                val pattern = alarmVibrationPattern(alarm.wake.vibrationPattern)
                ring(ringingNotification(this@AlarmService, alarm, at), uri, alarm.vibrate, pattern,
                    alarm.wake.silenceAfterMin ?: settings.alarms.silenceAfterMinutes,
                    alarm.wake.volumePercent, alarm.wake.fadeInSeconds)
                startEffects(alarm, at)
                soundUnlockJob?.cancel()
                if (!unlocked) soundUnlockJob = scope.launch {
                    while (!RuntimeAlarms.unlocked(this@AlarmService)) delay(250)
                    starts.withLock resumeSound@{
                        if (occurrence != (alarm.id to at) || !active) return@resumeSound
                        val restored = RuntimeAlarms.read(this@AlarmService)
                            .firstOrNull { it.id == alarm.id && it.ringingAt == at } ?: return@resumeSound
                        val restoredUri = selectRingSound(restored, at)
                        if (restoredUri != uri) {
                            if (restored.ringSoundUri == null) rememberPlayedSound(restored, at, restoredUri)
                            val muted = muteState.value?.untilElapsedMs != null
                            sound.play(restoredUri, restored.wake.volumePercent, restored.wake.fadeInSeconds)
                            if (muted) sound.pause()
                            if (restoredUri == SILENT) volumeGuard?.cancel()
                            else if (uri == SILENT) monitorAlertVolume()
                        }
                    }
                }
            }
        }
    }

    private fun bundledFallback() = "android.resource://$packageName/${R.raw.alarm_fallback}"

    private suspend fun selectRingSound(alarm: Alarm, at: Long): String {
        alarm.ringSoundUri?.let { return it }
        val selected = alarmRingtone(this, alarm, at) ?: runCatching {
            RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)?.toString()
        }.getOrNull() ?: bundledFallback()
        return RuntimeAlarms.rememberRingSound(this, alarm.id, at, null, selected) ?: selected
    }

    private fun rememberPlayedSound(alarm: Alarm, at: Long, selected: String) {
        sound.onStarted = { actual ->
            if (actual != selected && occurrence == (alarm.id to at)) {
                scope.launch { RuntimeAlarms.rememberRingSound(this@AlarmService, alarm.id, at, selected, actual) }
            }
        }
    }

    private fun startTimerChime(startId: Int) {
        scope.launch {
            starts.withLock {
                if (occurrence != null && active) return@withLock
                playNextTimerOrStop(startId)
            }
        }
    }

    private suspend fun playNextTimerOrStop(startId: Int? = null) {
        val pending = if (RuntimeAlarms.unlocked(this)) ClockStore(this).timersNow().filter { it.chimePending }
        else emptyList()
        val ringingTimer = pending.firstOrNull { it.id == timerChimeId }
        if (active && ringingTimer != null) {
            foreground(Notifications.timerChimeSummary(this, ringingTimer, openFullScreen = false))
            return
        }
        val next = pending.minWithOrNull(timerAlertOrder)
        if (next == null) {
            active = false
            volumeGuard?.cancel()
            if (startId == null) stopSelf() else stopSelf(startId)
            return
        }
        val settings = ClockStore(this).settingsNow()
        stopPreview?.invoke()
        resetMute()
        occurrence = null
        timerChimeId = next.id
        current = this
        stopEffects()
        soundUnlockJob?.cancel()
        sound.onStarted = null
        ring(Notifications.timerChimeSummary(this, next), settings.timers.soundUri,
            settings.timers.vibrate, TIMER_PATTERN, settings.alarms.silenceAfterMinutes)
        if (mainActivityVisible) launchAlertIfAllowed(this, TimerRingActivity.screenIntent(this, next.id))
    }

    private fun ring(notification: android.app.Notification, uri: String?, shouldVibrate: Boolean,
                     pattern: LongArray, silenceMinutes: Int, volume: Int = 100, fadeSeconds: Int = 0) {
        silenceJob?.cancel()
        sound.stop()
        vibrator?.cancel()
        foreground(notification)
        active = true
        if (uri != SILENT) sound.play(uri, volume, fadeSeconds)
        volumeGuard?.cancel()
        if (uri != SILENT) monitorAlertVolume()
        if (shouldVibrate) vibrate(pattern)
        // This timeout belongs to the active ringer, not a pending replacement's load job.
        silenceJob = scope.launch {
            if (silenceMinutes <= 0) return@launch
            delay(silenceMinutes * 60_000L)
            val token = occurrence
            val timerId = timerChimeId
            if (timerId != null) TimerRuntime.silence(this@AlarmService, timerId)
            starts.withLock {
                if (occurrence != token || timerChimeId != timerId) return@launch
                if (token != null) {
                    var before: Alarm? = null
                    val alarms = RuntimeAlarms.update(this@AlarmService) { list -> list.map { alarm ->
                        if (alarm.id == token.first && alarm.ringingAt == token.second) {
                            before = alarm
                            alarm.missOccurrence(token.second, System.currentTimeMillis())
                        } else alarm
                    } }
                    alarms.firstOrNull { it.id == token.first }?.let { AlarmScheduler.schedule(this@AlarmService, it) }
                    before?.takeUnless { it.isWakeCheckRetry }?.let { missed ->
                        if (RuntimeAlarms.unlocked(this@AlarmService)) {
                            ClockStore(this@AlarmService).putWakeLog(WakeChecks.log(missed, token.second, null, WakeOutcome.MISSED))
                        }
                    }
                }
                occurrence = null
                playNextTimerOrStop()
            }
        }
    }

    private fun monitorAlertVolume() {
        val audio = getSystemService(AudioManager::class.java)
        val stream = AudioManager.STREAM_ALARM
        var last = audio.getStreamVolume(stream)
        var wasMuted = audio.isStreamMute(stream)
        volumeGuard = scope.launch {
            while (active) {
                delay(120)
                val volume = audio.getStreamVolume(stream)
                val muted = audio.isStreamMute(stream)
                val target = alertVolumeFloor(audio.getStreamMaxVolume(stream))
                val loweredBelowFloor = volume < last && volume < target
                val mutedDuringAlert = muted && !wasMuted
                if (target > 0 && (loweredBelowFloor || mutedDuringAlert)) {
                    try {
                        if (muted) audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                        if (audio.getStreamVolume(stream) < target) audio.setStreamVolume(stream, target, 0)
                    } catch (blocked: SecurityException) {
                        android.util.Log.d("AlarmService", "Android restricted alarm volume adjustment", blocked)
                    }
                }
                last = audio.getStreamVolume(stream)
                wasMuted = audio.isStreamMute(stream)
            }
        }
    }

    private fun foreground(n: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(Notifications.ID_RINGING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(Notifications.ID_RINGING, n)
        }
    }

    private fun vibrate(pattern: LongArray) {
        vibrator = alarmVibrator(this)?.also { it.playAlarmPattern(pattern) }
    }

    private fun startEffects(alarm: Alarm, at: Long) {
        stopEffects()
        if (alarm.wake.flashEnabled && !alarm.wake.flashStrobeEnabled) setFlash(true)
        if (alarm.wake.flashStrobeEnabled) {
            effectsJob = scope.launch {
                try {
                    while (true) {
                        setFlash(true)
                        delay(350)
                        setFlash(false)
                        delay(650)
                    }
                } finally { setFlash(false) }
            }
        }
        if (alarm.wake.voiceAnnounceEnabled) {
            tts = TextToSpeech(this) { status ->
                val engine = tts
                if (engine != null && status == TextToSpeech.SUCCESS && occurrence == (alarm.id to at)) {
                    val availableVoice = runCatching { engine.voices }.getOrNull().orEmpty()
                        .firstOrNull { !it.isNetworkConnectionRequired && it.locale.language == java.util.Locale.getDefault().language }
                    if (availableVoice != null) {
                        engine.voice = availableVoice
                        scope.launch {
                            while (tts === engine && occurrence == (alarm.id to at)) {
                                val time = android.text.format.DateFormat.getTimeFormat(this@AlarmService).format(java.util.Date())
                                if (muteState.value?.untilElapsedMs == null) {
                                    engine.speak(listOf(time, alarm.label).filter { it.isNotBlank() }.joinToString(". "),
                                        TextToSpeech.QUEUE_FLUSH, null, "alarm:$at")
                                }
                                delay(20_000)
                            }
                        }
                    }
                }
            }.apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            }
        }
        if (alarm.wake.shakeToSnooze && alarm.canSnooze()) {
            val manager = getSystemService(SensorManager::class.java)
            val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (sensor != null) {
                val listener = object : SensorEventListener {
                    var lastShake = Long.MIN_VALUE / 2
                    var triggered = false
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                    override fun onSensorChanged(event: SensorEvent) {
                        val (x, y, z) = event.values
                        if (triggered || x * x + y * y + z * z < 26f * 26f) return
                        val now = android.os.SystemClock.elapsedRealtime()
                        val gap = now - lastShake
                        if (gap < 120) return
                        lastShake = now
                        if (gap <= 850) {
                            triggered = true
                            sendBroadcast(Intent(this@AlarmService, ClockActionReceiver::class.java)
                                .setAction(ClockActionReceiver.ACTION_SNOOZE)
                                .putExtra(AlarmReceiver.EXTRA_ID, alarm.id).putExtra(UpcomingAlarm.AT, at))
                        }
                    }
                }
                shakeListener = listener
                manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
            }
        }
    }

    private fun stopEffects() {
        effectsJob?.cancel()
        effectsJob = null
        setFlash(false)
        tts?.shutdown()
        tts = null
        shakeListener?.let { getSystemService(SensorManager::class.java)?.unregisterListener(it) }
        shakeListener = null
    }

    private fun setFlash(enabled: Boolean) {
        val cameras = getSystemService(android.hardware.camera2.CameraManager::class.java) ?: return
        runCatching {
            torchId?.let { cameras.setTorchMode(it, false) }
            torchId = null
            if (enabled) {
                val id = cameras.cameraIdList.firstOrNull {
                    cameras.getCameraCharacteristics(it)[android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE] == true
                }
                if (id != null) {
                    cameras.setTorchMode(id, true)
                    torchId = id
                }
            }
        }
    }

    private fun resetMute() {
        muteExpiryJob?.cancel()
        muteExpiryJob = null
        muteState.value = null
        muteOptions = com.wakr.app.core.WakeOptions()
    }

    private fun endMute(token: Pair<Long, Long>) {
        muteExpiryJob?.cancel()
        muteExpiryJob = null
        muteState.value = muteState.value?.takeIf { it.occurrence == token }
            ?.ended(muteOptions, android.os.SystemClock.elapsedRealtime())
        sound.resume()
    }

    override fun onDestroy() {
        if (current === this) {
            resetMute()
            current = null
        }
        stopEffects()
        active = false
        silenceJob?.cancel()
        sound.stop()
        vibrator?.cancel()
        scope.cancel()
        NotificationManagerCompat.from(this).cancel(Notifications.ID_RINGING)
        super.onDestroy()
    }

    companion object {
        private var current: AlarmService? = null
        private val muteState = MutableStateFlow<AlarmMute?>(null)
        internal val mute = muteState.asStateFlow()

        suspend fun toggleMute(id: Long, at: Long) = kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
            val token = id to at
            val service = current?.takeIf { it.occurrence == token && it.active } ?: return@withContext
            val existing = muteState.value?.takeIf { it.occurrence == token }
            if (existing?.untilElapsedMs != null) {
                service.endMute(token)
                return@withContext
            }
            val now = android.os.SystemClock.elapsedRealtime()
            if (!service.muteOptions.canBeginMute(existing, now)) return@withContext
            val duration = service.muteOptions.muteDurationSec * 1_000L
            muteState.value = AlarmMute(token, now + duration, (existing?.used ?: 0) + 1)
            service.sound.pause()
            service.tts?.stop()
            service.muteExpiryJob = service.scope.launch {
                delay(duration)
                if (current === service && service.occurrence == token) service.endMute(token)
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                try {
                    RuntimeAlarms.update(service) { alarms ->
                        alarms.map { alarm ->
                            if (alarm.id == id && alarm.ringingAt == at && alarm.missionsRemaining) {
                                alarm.copy(missionMuteCount = (alarm.missionMuteCount ?: 0) + 1)
                            } else alarm
                        }
                    }
                } catch (error: Exception) {
                    android.util.Log.w("AlarmService", "Unable to record mission mute", error)
                }
            }
        }

        suspend fun stopOccurrence(id: Long, at: Long) = kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
            val service = current ?: return@withContext
            service.starts.withLock {
                if (service.occurrence != (id to at)) return@withLock
                service.occurrence = null
                service.playNextTimerOrStop()
            }
        }

        internal suspend fun refreshTimerChime() = kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
            val service = current ?: return@withContext
            service.scope.launch {
                service.starts.withLock {
                    if (service.occurrence == null && service.timerChimeId != null) service.playNextTimerOrStop()
                }
            }
            Unit
        }

        const val ACTION_START = "com.wakr.app.RING"
        const val ACTION_START_TIMER = "com.wakr.app.RING_TIMER"

        /** Half a second on, half off: insistent without being a klaxon. */
        private val TIMER_PATTERN = longArrayOf(0, 300, 200, 300, 900)

        internal var stopPreview: (() -> Unit)? = null

        internal var mainActivityVisible = false

        internal fun isAlarmRinging(id: Long, at: Long) =
            current?.let { it.active && it.occurrence == (id to at) } == true

        internal fun isTimerChiming(id: Long) =
            current?.let { it.active && it.occurrence == null && it.timerChimeId == id } == true

        fun hasActiveAlarm(): Boolean = current?.let { it.active && it.occurrence != null } == true

        fun stop(context: Context) {
            context.stopService(Intent(context, AlarmService::class.java))
        }

        fun refreshRing(id: Long, at: Long) {
            val service = current ?: return
            if (service.occurrence != (id to at)) return
            service.scope.launch {
                val alarm = RuntimeAlarms.read(service).firstOrNull { it.id == id && it.ringingAt == at } ?: return@launch
                runCatching { NotificationManagerCompat.from(service).notify(Notifications.ID_RINGING, ringingNotification(service, alarm, at)) }
            }
        }

        private fun ringingNotification(context: Context, alarm: Alarm, at: Long): android.app.Notification {
            val label = alarm.label.ifBlank { "Alarm" }
            val id = alarm.id
            val open = AlarmRingActivity.intent(context, id, label, at)
            return NotificationCompat.Builder(context, Notifications.CHANNEL_ALARM)
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle(label)
                .setContentText(if (alarm.missionsRemaining) "Complete your wake-up missions" else "Alarm")
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setFullScreenIntent(open, true)
                .setContentIntent(open)
                .apply {
                    if (alarm.canSnooze()) addAction(0, "Snooze", Notifications.broadcast(context, ClockActionReceiver.ACTION_SNOOZE, 10, id, at))
                    if (alarm.missionsRemaining) addAction(0, "Open missions", open)
                    else {
                        setDeleteIntent(Notifications.broadcast(context, ClockActionReceiver.ACTION_DISMISS, 12, id, at))
                        addAction(0, "Dismiss", Notifications.broadcast(context, ClockActionReceiver.ACTION_DISMISS, 11, id, at))
                    }
                }.build()
        }


    }
}

internal fun alertVolumeFloor(max: Int): Int = ((max.coerceAtLeast(0).toLong() * 4 + 4) / 5).toInt()

internal fun alarmRingtone(context: Context, alarm: Alarm, at: Long): String? = when (alarm.wake.soundMode) {
    AlarmSoundMode.ONE -> alarm.soundUri
    AlarmSoundMode.SURPRISE -> {
        val sounds = listOf(R.raw.alarm_fallback, R.raw.alarm_sunrise, R.raw.alarm_daybreak)
        val day = java.time.Instant.ofEpochMilli(alarm.firstRingAt ?: at)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
        "android.resource://${context.packageName}/${sounds[Math.floorMod(day, sounds.size.toLong()).toInt()]}"
    }
    AlarmSoundMode.LOCAL_MUSIC -> alarm.wake.musicUris.randomOrNull() ?: alarm.soundUri
}

internal fun alarmVibrationPattern(pattern: VibrationPattern): LongArray = when (pattern) {
    VibrationPattern.DEFAULT -> longArrayOf(0, 500, 500)
    VibrationPattern.SOS -> longArrayOf(0, 180, 130, 180, 130, 180, 280, 550, 130, 550, 130, 550, 500)
    VibrationPattern.PULSE -> longArrayOf(0, 220, 220, 550, 330)
    VibrationPattern.HEARTBEAT -> longArrayOf(0, 120, 130, 180, 500)
}


internal fun alarmVibrator(context: Context): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService<VibratorManager>()?.defaultVibrator
    else {
        @Suppress("DEPRECATION")
        context.getSystemService<Vibrator>()
    }

internal fun Vibrator.playAlarmPattern(pattern: LongArray) {
    val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    vibrate(VibrationEffect.createWaveform(pattern, 0), attributes)
}
