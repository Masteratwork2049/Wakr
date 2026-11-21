package com.wakr.app.alarm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.key

import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import com.wakr.app.ui.screens.AlarmTimeReadout
import com.wakr.app.ui.screens.fittedAlarmTimeCap
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.coerceAtLeast
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.wakr.app.core.Alarm
import com.wakr.app.core.canSnooze
import com.wakr.app.core.ringMissions
import com.wakr.app.ui.missions.MissionChallenge
import com.wakr.app.ui.missions.title
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
                var completing by remember(id, at) { mutableStateOf(false) }
                var confirmation by remember(id, at) { mutableStateOf<RingConfirmation?>(null) }
                val scope = rememberCoroutineScope()
                val alarm by produceState<Alarm?>(null, id, at) {
                    while (true) {
                        val found = RuntimeAlarms.read(applicationContext).firstOrNull { it.id == id && it.ringingAt == at }
                        if (found != null) value = found
                        else if (!completing && confirmation == null) {
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
                val muteState by AlarmService.mute.collectAsStateWithLifecycle()
                val muteLabel = timedMuteLabel(current.wake.muteDurationSec, muteState?.takeIf { it.occurrence == (id to at) })
                val canOpenMission = mission != null || awaitingUnlock
                BackHandler(enabled = showMission && canOpenMission && !completing && confirmation == null) {
                    showMission = false
                }
                val lightBars = MaterialTheme.colorScheme.surface.luminance() > .5f
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightBars
                        isAppearanceLightNavigationBars = lightBars
                    }
                }
                suspend fun closeRing(actionName: String, origin: Offset, solvedMissionIndex: Int? = null) {
                    if (completing || confirmation != null) return
                    completing = true
                    try {
                        val receipt = withContext(NonCancellable + Dispatchers.IO) {
                            finishRing(applicationContext, id, at, actionName, solvedMissionIndex)
                        } ?: return
                        confirmation = RingConfirmation(receipt.snoozeMinutes, origin,
                            emergency = actionName == ClockActionReceiver.ACTION_EMERGENCY)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        android.util.Log.e("AlarmRingActivity", "Unable to complete alarm action", error)
                    } finally {
                        completing = false
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
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                                .then(if (confirmation != null) Modifier.clearAndSetSemantics {} else Modifier),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Box(Modifier.widthIn(max = 600.dp).fillMaxSize()) {
                                val snooze: ((Offset) -> Unit)? = if (current.canSnooze()) ({ origin ->
                                    scope.launch { closeRing(ClockActionReceiver.ACTION_SNOOZE, origin) }
                                }) else null
                                val snoozeMinutes = current.snoozeMinutes(settings.alarms.snoozeMinutes)
                                val emergency: ((Offset) -> Unit)? = if (current.wake.emergencyStopEnabled) ({ origin ->
                                    scope.launch { closeRing(ClockActionReceiver.ACTION_EMERGENCY, origin) }
                                }) else null
                                val mute: () -> Unit = { scope.launch { AlarmService.toggleMute(id, at) } }
                                val name = current.label.ifBlank { label.ifBlank { "Alarm" } }
                                if (showMission && canOpenMission) {
                                    val count = maxOf(current.ringMissions.size, current.privateMissionCount, 1)
                                    val step = current.missionIndex + 1
                                    MissionScreen(
                                        name,
                                        if (mission == null) "Wake-up missions" else "Mission $step of $count · ${mission.type.title}",
                                        progress = (step - 1f) / count,
                                        onBack = { if (!completing && confirmation == null) showMission = false },
                                        actions = {
                                            if (snooze != null) RingActionPill(
                                                "Snooze · $snoozeMinutes min", Icons.Outlined.Snooze, onAction = snooze,
                                            )
                                            AlarmSoundActions(muteLabel, mute, emergency)
                                        },
                                    ) {
                                        if (mission == null) {
                                            Text("Unlock the phone to continue your wake-up mission.")
                                            OutlinedButton(onClick = {
                                                getSystemService(android.app.KeyguardManager::class.java)
                                                    .requestDismissKeyguard(this@AlarmRingActivity, null)
                                            }) { Text("Unlock phone") }
                                        } else missionStates.SaveableStateProvider("mission:$id:$at:${current.missionIndex}") {
                                            MissionChallengeWithOrigin(mission, (current.firstRingAt ?: at) xor mission.id) { origin ->
                                                if (step == count) {
                                                    scope.launch { closeRing(ClockActionReceiver.ACTION_DISMISS, origin, current.missionIndex) }
                                                } else lifecycleScope.launch {
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
                                    AlarmRingingScreen(name, snoozeMinutes, snooze, canOpenMission, muteLabel, mute, emergency) { origin ->
                                        if (canOpenMission) showMission = true
                                        else scope.launch { closeRing(ClockActionReceiver.ACTION_DISMISS, origin) }
                                    }
                                }
                            }
                        }
                        confirmation?.let { result -> RingConfirmationOverlay(result, onFinished = { finish() }) }
                    }
                }
            }
        }
    }

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
    onSnooze: ((Offset) -> Unit)?,
    hasMission: Boolean,
    muteLabel: String?,
    onMute: () -> Unit,
    onEmergency: ((Offset) -> Unit)?,
    onPrimary: (Offset) -> Unit,
) {
    val now by rememberWallTicker(60_000L)
    val time = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
    val is24Hour = com.wakr.app.ui.rememberSystem24HourFormat()
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        val bodyHeight = maxHeight
        val widthCap = fittedAlarmTimeCap(maxWidth)
        SubcomposeLayout(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = bodyHeight)) { constraints ->
            val loose = Constraints.fixedWidth(constraints.maxWidth)
            val title = subcompose("title") {
                Text(label, style = MaterialTheme.typography.headlineMediumEmphasized, color = colors.onSurface,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }.single().measure(loose)
            val controls = subcompose("controls") {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RingActionPill(
                        if (hasMission) "Solve to dismiss" else "Dismiss", Icons.Outlined.Check,
                        primary = true, onAction = onPrimary,
                    )
                    if (onSnooze != null) RingActionPill(
                        "Snooze · $snoozeMinutes min", Icons.Outlined.Snooze, onAction = onSnooze,
                    )
                    AlarmSoundActions(muteLabel, onMute, onEmergency)
                }
            }.single().measure(loose)
            val gap = 12.dp.roundToPx()
            val available = constraints.minHeight - title.height - controls.height - gap * 2
            val cap = minOf(widthCap, (available - 4.dp.roundToPx()).toDp().coerceAtLeast(56.dp))
            val clock = subcompose("clock") {
                RingingClockReadout(time, is24Hour, cap, Modifier.fillMaxWidth())
            }.single().measure(loose)
            val occupied = title.height + clock.height + controls.height + gap * 2
            val height = maxOf(constraints.minHeight, occupied)
            val controlsTop = height - controls.height
            val clockTop = title.height + (controlsTop - title.height - clock.height) / 2
            layout(constraints.maxWidth, height) {
                title.placeRelative(0, 0)
                clock.placeRelative(0, clockTop)
                controls.placeRelative(0, controlsTop)
            }
        }
    }
}

@Composable
private fun MissionChallengeWithOrigin(
    mission: com.wakr.app.core.AlarmMission,
    seed: Long,
    onPass: (Offset) -> Unit,
) {
    var bounds by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var release by remember { mutableStateOf<Offset?>(null) }
    Box(Modifier.fillMaxWidth().onGloballyPositioned { bounds = it }.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val up = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.previousPressed && !it.pressed }
                if (up != null) release = bounds?.takeIf { it.isAttached }?.localToRoot(up.position)
            }
        }
    }) {
        MissionChallenge(mission, seed, allowScannerFallback = true) {
            val center = bounds?.takeIf { it.isAttached }?.let {
                it.localToRoot(Offset(it.size.width / 2f, it.size.height / 2f))
            }
            onPass(release ?: center ?: Offset.Zero)
        }
    }
}

@Composable
internal fun RingingClockReadout(
    time: java.time.LocalTime,
    use24h: Boolean,
    cap: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val spokenTime = time.format(java.time.format.DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h:mm a"))
    AlarmTimeReadout(time = time, use24h = use24h, capHeight = cap, ink = MaterialTheme.colorScheme.onSurface,
        weight = ClockFace.WEIGHT_OFF, modifier = modifier.clearAndSetSemantics { contentDescription = spokenTime })
}

@Composable
private fun timedMuteLabel(duration: Int, mute: AlarmMute?): String? {
    if (duration == 0) return null
    if (mute == null) return "Mute · ${duration}s"
    val until = mute.untilElapsedMs ?: return null
    return key(until) {
        val remaining by produceState((until - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L), until) {
            while (true) {
                value = (until - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                if (value == 0L) break
                delay((value - 1) % 1_000 + 1)
            }
        }
        "Unmute · ${(remaining + 999) / 1_000}s"
    }
}

@Composable
private fun AlarmSoundActions(muteLabel: String?, onMute: () -> Unit, onEmergency: ((Offset) -> Unit)?) {
    var lastLabel by remember { mutableStateOf("Mute") }
    SideEffect { if (muteLabel != null) lastLabel = muteLabel }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AnimatedVisibility(visible = muteLabel != null, enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()) {
            val title = muteLabel ?: lastLabel
            RingActionPill(
                title = title,
                icon = if (title.startsWith("Unmute")) Icons.AutoMirrored.Outlined.VolumeUp else Icons.AutoMirrored.Outlined.VolumeOff,
                onAction = { onMute() },
            )
        }
        if (onEmergency != null) EmergencyStopButton(onEmergency)
    }
}
