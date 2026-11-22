package com.wakr.app.ui.screens

import android.media.AudioManager
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val ALARM_STREAM = AudioManager.STREAM_ALARM
private const val FIX_VOLUME_FRACTION = 0.8f

internal class AlarmVolumeWarningState(private val audio: AudioManager) {
    var isLow by mutableStateOf(false)
        private set
    var fixFailed by mutableStateOf(false)
        private set

    init {
        refresh()
    }

    fun refresh() {
        val lowestAudible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            audio.getStreamMinVolume(ALARM_STREAM).coerceAtLeast(1)
        } else {
            1
        }
        isLow = audio.getStreamMaxVolume(ALARM_STREAM) > 0 &&
            (audio.isStreamMute(ALARM_STREAM) || audio.getStreamVolume(ALARM_STREAM) <= lowestAudible)
        if (!isLow) fixFailed = false
    }

    fun fix() {
        // Read again in case the user adjusted the alarm slider since the last refresh.
        refresh()
        if (!isLow) return
        try {
            val maxVolume = audio.getStreamMaxVolume(ALARM_STREAM)
            val target = (maxVolume * FIX_VOLUME_FRACTION).roundToInt().coerceIn(1, maxVolume)
            audio.setStreamVolume(ALARM_STREAM, target, AudioManager.FLAG_SHOW_UI)
            if (audio.isStreamMute(ALARM_STREAM)) {
                audio.adjustStreamVolume(ALARM_STREAM, AudioManager.ADJUST_UNMUTE, 0)
            }
        } catch (_: SecurityException) {
            // Android can reject stream changes under restricted audio policies.
        }
        refresh()
        fixFailed = isLow
    }
}

@Composable
internal fun rememberAlarmVolumeWarning(active: Boolean): AlarmVolumeWarningState? {
    val context = LocalContext.current
    val audio = remember(context) { context.getSystemService(AudioManager::class.java) } ?: return null
    val state = remember(audio) { AlarmVolumeWarningState(audio) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // The system sliders can change without Wakr losing focus. Read the actual stream while
    // Alarms is visible so the banner also follows manual changes on those devices.
    LaunchedEffect(state, lifecycle, active) {
        if (!active) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                state.refresh()
                delay(750)
            }
        }
    }
    return state
}

@Composable
internal fun AlarmVolumeWarning(state: AlarmVolumeWarningState, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.errorContainer,
        contentColor = colors.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(22.dp))
            Text(
                if (state.fixFailed) "Couldn't raise alarm volume" else "The alarm volume is low",
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleSmall,
            )
            OutlinedButton(
                onClick = state::fix,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onErrorContainer),
                border = BorderStroke(1.dp, colors.onErrorContainer.copy(alpha = .6f)),
            ) {
                Text("Fix")
            }
        }
    }
}
