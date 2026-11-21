package com.wakr.app.alarm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.wakr.app.ui.screens.WidePill
import com.wakr.app.ui.theme.ClockFace
import com.wakr.app.ui.theme.ClockTheme
import com.wakr.app.ui.theme.Numerals
import java.time.LocalTime

/**
 * The screen a ringing alarm puts in front of you.
 *
 * ## Getting onto a locked phone
 *
 * `setShowWhenLocked` and `setTurnScreenOn` are the modern replacements for the deprecated window
 * flags, and they are the reason this appears over the keyguard with the display off. They are set
 * before `setContent` because the window attributes have to be right before the first frame.
 *
 * ## No back button
 *
 * Backing out of a ringing alarm would leave it ringing with no way to reach the controls except
 * the shade. The only exits are Snooze and Dismiss, which is the same contract every alarm clock
 * has had since they had a bell on top.
 */
class AlarmRingActivity : ComponentActivity() {

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    val snooze = if (current.canSnooze()) ({ closeRing(ClockActionReceiver.ACTION_SNOOZE) }) else null
                    if (mission != null || awaitingUnlock) {
                        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(current.label.ifBlank { label.ifBlank { "Alarm" } }, style = MaterialTheme.typography.headlineMedium)
                            if (mission == null) {
                                Text("Unlock the phone to continue your wake-up mission.")
                                androidx.compose.material3.FilledTonalButton(onClick = {
                                    getSystemService(android.app.KeyguardManager::class.java).requestDismissKeyguard(this@AlarmRingActivity, null)
                                }) { Text("Unlock phone") }
                            } else {
                                Text("Mission ${current.missionIndex + 1} of ${current.ringMissions.size} · ${mission.type.title}")
                                MissionChallenge(mission, (current.firstRingAt ?: at) xor mission.id, allowScannerFallback = true) {
                                    lifecycleScope.launch {
                                        val updated = RuntimeAlarms.update(applicationContext) { alarms -> alarms.map { candidate ->
                                            if (candidate.id == id && candidate.ringingAt == at && candidate.missionIndex == current.missionIndex) {
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
                            if (snooze != null) androidx.compose.material3.OutlinedButton(onClick = snooze) {
                                Text("Snooze ${current.wake.snoozeDurationMin ?: settings.alarms.snoozeMinutes} min")
                            }
                        }
                    } else {
                        Ringing(label = current.label.ifBlank { label }, snoozeMinutes = current.wake.snoozeDurationMin ?: settings.alarms.snoozeMinutes,
                            onSnooze = snooze, onDismiss = { closeRing(ClockActionReceiver.ACTION_DISMISS) })
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
private fun Ringing(
    label: String,
    snoozeMinutes: Int,
    onSnooze: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val now by rememberWallTicker(60_000L)
    val time = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
    val is24Hour = com.wakr.app.ui.rememberSystem24HourFormat()

    val formattedTime = if (is24Hour) {
        "%02d:%02d".format(time.hour, time.minute)
    } else {
        "%02d:%02d".format((time.hour % 12).takeIf { it != 0 } ?: 12, time.minute)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            label.ifBlank { "Alarm" },
            style = MaterialTheme.typography.headlineMediumEmphasized,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        // The same ultra-condensed face the grid uses, at the size a room-across glance needs.
        Numerals(
            text = formattedTime,
            capHeight = 132.dp,
            color = MaterialTheme.colorScheme.primary,
            width = ClockFace.CONDENSED,
            weight = ClockFace.WEIGHT_ON,
            tracking = ClockFace.CONDENSED_TRACKING,
        )
        Spacer(Modifier.height(56.dp))
        WidePill(
            text = "Dismiss",
            onClick = onDismiss,
            container = MaterialTheme.colorScheme.tertiaryContainer,
            content = MaterialTheme.colorScheme.onTertiaryContainer,
            height = 96.dp,
            modifier = Modifier.fillMaxWidth(),
        )
        if (onSnooze != null) {
            Spacer(Modifier.height(12.dp))
            WidePill(
                text = "Snooze $snoozeMinutes min",
                onClick = onSnooze,
                outlined = true,
                content = MaterialTheme.colorScheme.onSurface,
                height = 72.dp,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
