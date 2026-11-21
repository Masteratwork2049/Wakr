package com.wakr.app.alarm

import android.os.SystemClock
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val EMERGENCY_HOLD_MS = 10_000L

internal enum class RingDirection { SNOOZE, PRIMARY }

internal fun ringDirection(fraction: Float, snoozeAvailable: Boolean): RingDirection? = when {
    fraction >= .7f -> RingDirection.PRIMARY
    fraction <= -.7f && snoozeAvailable -> RingDirection.SNOOZE
    else -> null
}

@Composable
internal fun RingActionSlider(
    snoozeMinutes: Int,
    onSnooze: (() -> Unit)?,
    primaryText: String,
    onPrimary: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val snoozeText = if (onSnooze == null) "Snooze unavailable" else "Snooze · $snoozeMinutes min"
    var fraction by remember { mutableFloatStateOf(0f) }
    var reset by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val snooze by rememberUpdatedState(onSnooze)
    val primary by rememberUpdatedState(onPrimary)
    val direction by rememberUpdatedState(primaryText)

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val travel = (maxWidth - 72.dp) / 2
            val pixels = with(androidx.compose.ui.platform.LocalDensity.current) { travel.toPx().coerceAtLeast(1f) }
            Surface(
                modifier = Modifier.fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Alarm slider"
                        stateDescription = "$snoozeText. $direction."
                        customActions = buildList {
                            if (onSnooze != null) add(CustomAccessibilityAction(snoozeText) { snooze?.invoke(); true })
                            add(CustomAccessibilityAction(primaryText) { primary(); true })
                        }
                    }
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyUp) false
                        else when (event.key) {
                            Key.DirectionLeft -> snooze?.let { it(); true } ?: false
                            Key.DirectionRight, Key.Enter -> { primary(); true }
                            else -> false
                        }
                    }
                    .focusable()
                    .pointerInput(pixels) {
                        fun returnToCenter() {
                            reset = scope.launch {
                                animate(fraction, 0f, animationSpec = spring(stiffness = 480f, dampingRatio = .82f)) { value, _ ->
                                    fraction = value
                                }
                            }
                        }
                        detectHorizontalDragGestures(
                            onDragStart = { reset?.cancel() },
                            onDragCancel = ::returnToCenter,
                            onDragEnd = {
                                when (ringDirection(fraction, snooze != null)) {
                                    RingDirection.SNOOZE -> snooze?.invoke()
                                    RingDirection.PRIMARY -> primary()
                                    null -> Unit
                                }
                                returnToCenter()
                            },
                        ) { change, delta ->
                            change.consume()
                            fraction = (fraction + delta / pixels).coerceIn(if (snooze == null) -.12f else -1f, 1f)
                        }
                    },
                shape = CircleShape,
                color = colors.surfaceContainerHighest,
            ) {
                Box(Modifier.fillMaxWidth().heightIn(min = 76.dp), contentAlignment = Alignment.Center) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(snoozeText, Modifier.weight(1f).clearAndSetSemantics {}, textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (onSnooze == null) colors.outline else colors.onSurfaceVariant)
                        Spacer(Modifier.width(62.dp))
                        Text(primaryText, Modifier.weight(1f).clearAndSetSemantics {}, textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
                    }
                    Surface(
                        Modifier.size(56.dp).graphicsLayer { translationX = pixels * fraction },
                        shape = CircleShape, color = colors.primary, contentColor = colors.onPrimary,
                    ) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.SwapHoriz, null) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun EmergencyStopButton(onStop: () -> Unit) {
    val finish by rememberUpdatedState(onStop)
    var hold by remember { mutableFloatStateOf(0f) }
    var showAccessibleConfirmation by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth()
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Emergency stop", style = MaterialTheme.typography.labelLarge, color = colors.error)
        Text("Hold for 10 seconds", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        if (hold > 0f) LinearProgressIndicator(progress = { hold }, modifier = Modifier.fillMaxWidth(), color = colors.error)
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
