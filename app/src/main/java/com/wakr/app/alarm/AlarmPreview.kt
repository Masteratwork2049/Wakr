package com.wakr.app.alarm

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wakr.app.core.Alarm
import com.wakr.app.core.canSnooze
import com.wakr.app.core.ringMissions
import com.wakr.app.data.ClockStore
import com.wakr.app.ui.missions.MissionChallenge
import com.wakr.app.ui.missions.MissionScreen
import com.wakr.app.ui.missions.title
import com.wakr.app.ui.sheets.AlarmFullscreenDialog
import kotlinx.coroutines.delay

/** A ringing UI and local playback session. No preview occurrence is written to the alarm store. */
@Composable
internal fun AlarmPreview(alarm: Alarm, onEnd: () -> Unit) {
    val context = LocalContext.current
    val end by rememberUpdatedState(onEnd)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val audio = remember { AlarmAudio(context) }
    val at = remember { System.currentTimeMillis() }
    val wake = alarm.wake
    val token = alarm.id to at
    var mute by remember { mutableStateOf<AlarmMute?>(null) }
    var index by remember { mutableIntStateOf(0) }
    var showMission by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<RingConfirmation?>(null) }
    val vibrator = remember { if (alarm.vibrate && !AlarmService.hasActiveAlarm()) alarmVibrator(context) else null }
    val fallbackSnooze by produceState(10) { value = ClockStore(context).settingsNow().alarms.snoozeMinutes }

    fun complete(result: RingConfirmation) {
        if (confirmation != null) return
        audio.stop()
        vibrator?.cancel()
        confirmation = result
    }

    DisposableEffect(lifecycle, audio) {
        var previewVibrating = false
        fun stopEffects() {
            audio.stop()
            if (previewVibrating) {
                vibrator?.cancel()
                previewVibrating = false
            }
        }
        val interrupt = {
            stopEffects()
            end()
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) interrupt()
        }
        lifecycle.addObserver(observer)
        AlarmService.stopPreview = interrupt
        if (!AlarmService.hasActiveAlarm()) {
            audio.play(alarmRingtone(context, alarm, at), wake.volumePercent, wake.fadeInSeconds)
            vibrator?.playAlarmPattern(alarmVibrationPattern(wake.vibrationPattern))
            previewVibrating = vibrator != null
        }
        onDispose {
            lifecycle.removeObserver(observer)
            if (AlarmService.stopPreview === interrupt) AlarmService.stopPreview = null
            stopEffects()
        }
    }
    LaunchedEffect(mute?.untilElapsedMs) {
        val deadline = mute?.untilElapsedMs ?: return@LaunchedEffect
        delay((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        if (confirmation == null) audio.resume()
        mute = mute?.ended(wake, SystemClock.elapsedRealtime())
    }
    val muteLabel = timedMuteLabel(wake, mute)
    val onMute = {
        val now = SystemClock.elapsedRealtime()
        val previous = mute
        if (confirmation == null) {
            if (previous?.untilElapsedMs != null) {
                mute = previous.ended(wake, now)
                audio.resume()
            } else if (wake.canBeginMute(previous, now)) {
                mute = AlarmMute(token, now + wake.muteDurationSec * 1_000L, (previous?.used ?: 0) + 1)
                audio.pause()
            }
        }
    }
    val snoozeMinutes = alarm.snoozeMinutes(fallbackSnooze)
    val snooze: ((Offset) -> Unit)? = if (alarm.canSnooze()) ({ complete(RingConfirmation(snoozeMinutes, it)) }) else null
    val emergency: ((Offset) -> Unit)? = if (wake.emergencyStopEnabled) ({
        complete(RingConfirmation(null, it, emergency = true))
    }) else null
    val mission = alarm.ringMissions.getOrNull(index)
    val label = alarm.label.ifBlank { "Alarm" }

    AlarmFullscreenDialog(onClose = onEnd) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (showMission && mission != null) {
                    val count = alarm.ringMissions.size
                    MissionScreen(label, "Mission ${index + 1} of $count · ${mission.type.title}", index.toFloat() / count,
                        onBack = { showMission = false },
                        actions = {
                            if (snooze != null) RingActionPill("Snooze · $snoozeMinutes min", Icons.Outlined.Snooze, onAction = snooze)
                            AlarmSoundActions(muteLabel, onMute, emergency)
                        },
                    ) {
                        key(index) {
                            MissionChallenge(mission, at xor mission.id, allowScannerFallback = true) {
                                if (index + 1 == count) complete(RingConfirmation(null, Offset.Zero))
                                else index++
                            }
                        }
                    }
                } else {
                    AlarmRingingScreen(label, snoozeMinutes, snooze, mission != null, muteLabel, onMute, emergency,
                        previewTime = alarm.time) { origin ->
                        if (mission != null) showMission = true else complete(RingConfirmation(null, origin))
                    }
                }
                confirmation?.let { RingConfirmationOverlay(it, onFinished = onEnd) }
            }
            FilledTonalButton(onClick = onEnd, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("End Preview")
            }
        }
    }
}
