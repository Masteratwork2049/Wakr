package com.wakr.app.alarm

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
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
    private var torchId: String? = null
    private var silenceJob: Job? = null
    private var effectsJob: Job? = null
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
                val settings = RuntimeAlarms.settings(this@AlarmService)
                mutedOccurrence.value = null
                occurrence = id to at
                current = this@AlarmService
                val resource = listOf(R.raw.alarm_fallback, R.raw.alarm_sunrise, R.raw.alarm_daybreak)
                val day = java.time.Instant.ofEpochMilli(alarm.firstRingAt ?: at)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
                val uri = when (alarm.wake.soundMode) {
                    AlarmSoundMode.ONE -> alarm.soundUri
                    AlarmSoundMode.SURPRISE -> "android.resource://$packageName/${resource[Math.floorMod(day, resource.size.toLong()).toInt()]}"
                    AlarmSoundMode.LOCAL_MUSIC -> alarm.wake.musicUris.randomOrNull() ?: alarm.soundUri
                }
                val pattern = when (alarm.wake.vibrationPattern) {
                    VibrationPattern.DEFAULT -> ALARM_PATTERN
                    VibrationPattern.SOS -> longArrayOf(0, 180, 130, 180, 130, 180, 280, 550, 130, 550, 130, 550, 500)
                    VibrationPattern.PULSE -> longArrayOf(0, 220, 220, 550, 330)
                    VibrationPattern.HEARTBEAT -> longArrayOf(0, 120, 130, 180, 500)
                }
                ring(ringingNotification(this@AlarmService, alarm, at), uri, alarm.vibrate, pattern,
                    alarm.wake.silenceAfterMin ?: settings.alarms.silenceAfterMinutes,
                    alarm.wake.volumePercent, alarm.wake.fadeInSeconds)
                startEffects(alarm, at)
            }
        }
    }

    private fun startTimerChime(startId: Int) {
        scope.launch {
            starts.withLock {
                val store = ClockStore(this@AlarmService)
                if (store.timerNow()?.state != com.wakr.app.core.TimerState.FINISHED) {
                    if (!active) stopSelf(startId)
                    return@launch
                }
                val settings = store.settingsNow()
                mutedOccurrence.value = null
                occurrence = null
                stopEffects()
                ring(timerNotification(this@AlarmService), settings.timers.soundUri,
                    settings.timers.vibrate, TIMER_PATTERN, settings.alarms.silenceAfterMinutes)
            }
        }
    }

    private fun ring(notification: android.app.Notification, uri: String?, shouldVibrate: Boolean,
                     pattern: LongArray, silenceMinutes: Int, volume: Int = 100, fadeSeconds: Int = 0) {
        silenceJob?.cancel()
        sound.stop()
        vibrator?.cancel()
        foreground(notification)
        active = true
        if (uri != SILENT) sound.play(uri, volume, fadeSeconds)
        if (shouldVibrate) vibrate(pattern)
        // This timeout belongs to the active ringer, not a pending replacement's load job.
        silenceJob = scope.launch {
            if (silenceMinutes <= 0) return@launch
            delay(silenceMinutes * 60_000L)
            val token = occurrence
            if (token != null) starts.withLock {
                if (occurrence != token) return@launch
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
            stopSelf()
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
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService<VibratorManager>()?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService<Vibrator>()
        }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        // Index 0 restarts the pattern from its first element, i.e. repeat forever.
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0), attrs)
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
                                if (mutedOccurrence.value != (alarm.id to at)) {
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

    override fun onDestroy() {
        if (current === this) {
            current = null
            mutedOccurrence.value = null
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
        private val mutedOccurrence = MutableStateFlow<Pair<Long, Long>?>(null)
        internal val muted = mutedOccurrence.asStateFlow()

        suspend fun toggleMute(id: Long, at: Long) {
            val token = id to at
            val service = current?.takeIf { it.occurrence == token && it.active } ?: return
            if (mutedOccurrence.value == token) {
                mutedOccurrence.value = null
                service.sound.resume()
            } else {
                mutedOccurrence.value = token
                service.sound.pause()
                service.tts?.stop()
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
        }

        suspend fun stopOccurrence(id: Long, at: Long) = kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
            current?.takeIf { it.occurrence == (id to at) }?.stopSelf()
        }

        const val ACTION_START = "com.wakr.app.RING"
        const val ACTION_START_TIMER = "com.wakr.app.RING_TIMER"

        /** Half a second on, half off: insistent without being a klaxon. */
        private val ALARM_PATTERN = longArrayOf(0, 500, 500)
        private val TIMER_PATTERN = longArrayOf(0, 300, 200, 300, 900)

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

        private fun timerNotification(context: Context) =
            NotificationCompat.Builder(context, Notifications.CHANNEL_ALARM)
                .setSmallIcon(R.drawable.ic_stat_timer)
                .setContentTitle("Timer")
                .setContentText("Time's up")
                .setDeleteIntent(Notifications.broadcast(context, ClockActionReceiver.ACTION_TIMER_CANCEL, 25))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setContentIntent(Notifications.openApp(context, Notifications.TAB_TIMERS))
                .addAction(0, "Stop", Notifications.broadcast(context, ClockActionReceiver.ACTION_TIMER_CANCEL, 24))
                .build()
    }
}
