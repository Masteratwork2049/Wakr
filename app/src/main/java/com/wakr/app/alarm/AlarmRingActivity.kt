package com.wakr.app.alarm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wakr.app.ui.missions.MissionScreen
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.wakr.app.core.Alarm
import com.wakr.app.core.canSnooze
import com.wakr.app.core.ringMissions
import com.wakr.app.ui.missions.MissionChallenge
import com.wakr.app.ui.missions.title
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wakr.app.data.ClockSettings
import com.wakr.app.data.ClockStore
import com.wakr.app.ui.rememberWallTicker
import com.wakr.app.ui.theme.ClockFace
import com.wakr.app.ui.theme.ClockTheme
import com.wakr.app.ui.theme.Numerals

/**
 * The screen a ringing alarm puts in front of you.
 *
 * ## Getting onto a locked phone
 *
 * `setShowWhenLocked` and `setTurnScreenOn` are the modern replacements for the deprecated window
 * flags, and they are the reason this appears over the keyguard with the display off. They are set
 * before `setContent` because the window attributes have to be right before the first frame.
 *
 * Back from a mission returns to the ringing controls. Closing the ring is an occurrence action
 * handled by [ClockActionReceiver], so interactivity and dismissal rules match the notification.
 */
class AlarmRingActivity : ComponentActivity() {

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        val id = intent.getLongExtra(AlarmReceiver.EXTRA_ID, -1L)
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        val at = intent.getLongExtra(UpcomingAlarm.AT, -1L)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        setContent {
            val settings by produceState(ClockSettings()) {
                while (!RuntimeAlarms.unlocked(applicationContext)) delay(350)
                ClockStore(applicationContext).settings.collect { value = it }
            }
            ClockTheme(settings.theme) {
                val alarm by produceState<Alarm?>(null, id, at) {
                    while (true) {
                        val found = RuntimeAlarms.read(applicationContext).firstOrNull { it.id == id && it.ringingAt == at }
                        value = found
                        if (found == null) {
                            finish()
                            break
                        }
                        delay(350)
                    }
                }
                val current = alarm ?: return@ClockTheme
                val mission = current.ringMissions.getOrNull(current.missionIndex)
                val awaitingUnlock = mission == null && current.missionIndex < current.privateMissionCount
                var showMission by rememberSaveable(id, at) { mutableStateOf(false) }
                val missionStates = rememberSaveableStateHolder()
                val mutedToken by AlarmService.muted.collectAsStateWithLifecycle()
                val muted = mutedToken == (id to at)
                val canOpenMission = mission != null || awaitingUnlock
                BackHandler(enabled = showMission && canOpenMission) { showMission = false }
                val lightBars = MaterialTheme.colorScheme.surface.luminance() > .5f
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightBars
                        isAppearanceLightNavigationBars = lightBars
                    }
                }
                val completing = remember(id, at) { mutableStateOf(false) }
                val closeRing = { actionName: String ->
                    if (!completing.value) {
                        completing.value = true
                        sendBroadcast(action(actionName, id))
                    }
                }
                LaunchedEffect(current.wake.voiceAnnounceEnabled) {
                    if (current.wake.voiceAnnounceEnabled) {
                        val attrs = window.attributes
                        val levels = listOf(.22f, .36f, .55f, .75f, 1f)
                        for (level in levels) {
                            attrs.screenBrightness = level
                            window.attributes = attrs
                            delay(4000)
                        }
                    }
                }
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.widthIn(max = 600.dp).fillMaxSize()) {
                            val snooze = if (current.canSnooze()) ({ closeRing(ClockActionReceiver.ACTION_SNOOZE) }) else null
                            val snoozeMinutes = current.wake.snoozeDurationMin ?: settings.alarms.snoozeMinutes
                            val emergency = if (canOpenMission) ({ closeRing(ClockActionReceiver.ACTION_EMERGENCY) }) else null
                            val mute = { AlarmService.toggleMute(id, at) }
                            val name = current.label.ifBlank { label.ifBlank { "Alarm" } }
                            if (showMission && canOpenMission) {
                                val count = maxOf(current.ringMissions.size, current.privateMissionCount, 1)
                                val step = current.missionIndex + 1
                                MissionScreen(
                                    name,
                                    if (mission == null) "Wake-up missions" else "Mission $step of $count · ${mission.type.title}",
                                    progress = (step - 1f) / count,
                                    onBack = { showMission = false },
                                    actions = {
                                        if (snooze != null) OutlinedButton(onClick = snooze) { Text("Snooze · $snoozeMinutes min") }
                                        AlarmSoundActions(muted, mute, emergency)
                                    },
                                ) {
                                    if (mission == null) {
                                        Text("Unlock the phone to continue your wake-up mission.")
                                        OutlinedButton(onClick = {
                                            getSystemService(android.app.KeyguardManager::class.java)
                                                .requestDismissKeyguard(this@AlarmRingActivity, null)
                                        }) { Text("Unlock phone") }
                                    } else missionStates.SaveableStateProvider("mission:$id:$at:${current.missionIndex}") {
                                        MissionChallenge(mission, (current.firstRingAt ?: at) xor mission.id, allowScannerFallback = true) {
                                            lifecycleScope.launch {
                                                val updated = RuntimeAlarms.update(applicationContext) { alarms -> alarms.map { candidate ->
                                                    if (candidate.id == id && candidate.ringingAt == at &&
                                                        candidate.missionIndex == current.missionIndex) {
                                                        candidate.copy(missionIndex = candidate.missionIndex + 1)
                                                    } else candidate
                                                } }
                                                updated.firstOrNull { it.id == id && it.ringingAt == at }?.let {
                                                    RuntimeAlarms.mirror(applicationContext, it)
                                                    AlarmService.refreshRing(id, at)
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                AlarmRingingScreen(name, snoozeMinutes, snooze, canOpenMission, muted, mute, emergency) {
                                    if (canOpenMission) showMission = true
                                    else closeRing(ClockActionReceiver.ACTION_DISMISS)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing || isChangingConfigurations) return
        val id = intent.getLongExtra(AlarmReceiver.EXTRA_ID, -1)
        val at = intent.getLongExtra(UpcomingAlarm.AT, -1)
        AlarmService.refreshRing(id, at, leaving = true)
    }

    private fun action(name: String, id: Long) =
        Intent(this, ClockActionReceiver::class.java)
            .setPackage(packageName)
            .setAction(name)
            .putExtra(UpcomingAlarm.AT, intent.getLongExtra(UpcomingAlarm.AT, -1))
            .putExtra(AlarmReceiver.EXTRA_ID, id)

    companion object {
        const val EXTRA_LABEL = "label"

        fun intent(context: Context, id: Long, label: String, at: Long): PendingIntent =
            PendingIntent.getActivity(
                context,
                id.toInt(),
                Intent(context, AlarmRingActivity::class.java)
                    .setData(android.net.Uri.parse("wakr://ring/$id/$at"))
                    .putExtra(UpcomingAlarm.AT, at)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra(AlarmReceiver.EXTRA_ID, id)
                    .putExtra(EXTRA_LABEL, label),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}

@Composable
internal fun AlarmRingingScreen(
    label: String,
    snoozeMinutes: Int,
    onSnooze: (() -> Unit)?,
    hasMission: Boolean,
    muted: Boolean,
    onMute: () -> Unit,
    onEmergency: (() -> Unit)?,
    onPrimary: () -> Unit,
) {
    val now by rememberWallTicker(60_000L)
    val time = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
    val is24Hour = com.wakr.app.ui.rememberSystem24HourFormat()
    val formattedTime = if (is24Hour) "%02d:%02d".format(time.hour, time.minute)
        else "%d:%02d".format((time.hour % 12).takeIf { it != 0 } ?: 12, time.minute)
    val meridiem = if (is24Hour) "" else if (time.hour < 12) "AM" else "PM"
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val bodyHeight = maxHeight
        val cap = (maxWidth * .3f).coerceIn(48.dp, 132.dp)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = bodyHeight)
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(label, style = MaterialTheme.typography.headlineMediumEmphasized, color = colors.onSurface)
                Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
                    color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Numerals(formattedTime, cap, colors.onPrimaryContainer, width = ClockFace.CONDENSED,
                            weight = ClockFace.WEIGHT_ON, tracking = ClockFace.CONDENSED_TRACKING)
                        if (meridiem.isNotEmpty()) Text(meridiem, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RingActionSlider(snoozeMinutes, onSnooze, if (hasMission) "Solve to dismiss" else "Dismiss", onPrimary)
                AlarmSoundActions(muted, onMute, onEmergency)
            }
        }
    }
}

@Composable
private fun AlarmSoundActions(muted: Boolean, onMute: () -> Unit, onEmergency: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = onMute) { Text(if (muted) "Unmute" else "Mute") }
        if (onEmergency != null) EmergencyStopButton(onEmergency)
    }
}
