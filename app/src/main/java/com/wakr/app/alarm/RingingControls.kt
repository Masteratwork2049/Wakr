package com.wakr.app.alarm

import android.os.SystemClock
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val EMERGENCY_HOLD_MS = 10_000L

@Composable
internal fun RingActionPill(
    title: String,
    icon: ImageVector,
    primary: Boolean = false,
    enabled: Boolean = true,
    onAction: (Offset) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var center by remember { mutableStateOf(Offset.Zero) }
    val modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).onGloballyPositioned {
        center = it.localToRoot(Offset(it.size.width / 2f, it.size.height / 2f))
    }
    val onClick = { onAction(center) }
    if (primary) Button(onClick = onClick, modifier = modifier, enabled = enabled) {
        RingPillLabel(icon, title, LocalContentColor.current)
    } else OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        RingPillLabel(icon, title, if (enabled) colors.onSurface else LocalContentColor.current)
    }
}

/** The icon has its own leading slot, so the text stays centered over the whole pill. */
@Composable
internal fun RingPillLabel(icon: ImageVector, title: String, color: Color, subtitle: String? = null) {
    Box(Modifier.fillMaxWidth().heightIn(min = 32.dp), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.align(Alignment.CenterStart).size(22.dp))
        Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = color, textAlign = TextAlign.Center)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = color, textAlign = TextAlign.Center)
        }
    }
}

@Composable
internal fun EmergencyStopButton(onStop: () -> Unit) {
    val finish by rememberUpdatedState(onStop)
    var hold by remember { mutableFloatStateOf(0f) }
    var showAccessibleConfirmation by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth()
            .clip(CircleShape)
            .drawBehind {
                drawRect(colors.errorContainer)
                if (hold > 0f) drawRect(colors.onErrorContainer.copy(alpha = .18f), size = Size(size.width * hold, size.height))
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick(label = "Emergency stop confirmation") { showAccessibleConfirmation = true; true }
                stateDescription = if (hold > 0f) "${(hold * 100).toInt()} percent" else "Hold for 10 seconds"
            }
            .onKeyEvent {
                if (it.type == KeyEventType.KeyUp && (it.key == Key.Enter || it.key == Key.Spacebar)) {
                    showAccessibleConfirmation = true
                    true
                } else false
            }
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    coroutineScope {
                        val countdown = launch {
                            waitForEmergencyHold { hold = it }
                            finish()
                        }
                        try {
                            tryAwaitRelease()
                        } finally {
                            countdown.cancel()
                            hold = 0f
                        }
                    }
                })
            }
            .heightIn(min = 64.dp)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        RingPillLabel(Icons.Outlined.MedicalServices, "Emergency stop", colors.onErrorContainer, "Hold for 10 seconds")
    }
    if (showAccessibleConfirmation) {
        var progress by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(Unit) { waitForEmergencyHold { progress = it } }
        AlertDialog(
            onDismissRequest = { showAccessibleConfirmation = false },
            title = { Text("Emergency stop?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("This records an emergency stop in wake history.")
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } },
            confirmButton = {
                TextButton(enabled = progress >= 1f, onClick = { showAccessibleConfirmation = false; finish() }) {
                    Text("Stop alarm")
                }
            },
            dismissButton = { TextButton(onClick = { showAccessibleConfirmation = false }) { Text("Cancel") } },
        )
    }
}

private suspend fun waitForEmergencyHold(onProgress: (Float) -> Unit) {
    val started = SystemClock.elapsedRealtime()
    while (true) {
        val elapsed = SystemClock.elapsedRealtime() - started
        onProgress((elapsed.toFloat() / EMERGENCY_HOLD_MS).coerceIn(0f, 1f))
        if (elapsed >= EMERGENCY_HOLD_MS) return
        delay(32)
    }
}
