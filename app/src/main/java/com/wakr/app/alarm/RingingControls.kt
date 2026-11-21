package com.wakr.app.alarm

import android.animation.ValueAnimator
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import kotlin.math.abs
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
import androidx.compose.ui.draw.clip
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
    var pressed by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var committing by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val snooze by rememberUpdatedState(onSnooze)
    val primary by rememberUpdatedState(onPrimary)
    val direction by rememberUpdatedState(primaryText)
    val motion = ValueAnimator.areAnimatorsEnabled()
    val thumbScale by animateFloatAsState(
        targetValue = if (motion && (pressed || dragging)) .91f else 1f,
        animationSpec = spring(stiffness = 550f, dampingRatio = .78f),
        label = "ringSliderPress",
    )
    val armed by remember(onSnooze != null) {
        derivedStateOf { ringDirection(fraction, snooze != null) }
    }
    fun returnToCenter() {
        if (!motion) {
            fraction = 0f
        } else reset = scope.launch {
            animate(fraction, 0f, animationSpec = spring(stiffness = 480f, dampingRatio = .82f)) { value, _ ->
                fraction = value
            }
        }
    }
    fun settle(target: RingDirection) {
        if (committing || target == RingDirection.SNOOZE && snooze == null) return
        committing = true
        reset?.cancel()
        scope.launch {
            val edge = if (target == RingDirection.SNOOZE) -1f else 1f
            if (motion) animate(fraction, edge, animationSpec = tween(130, easing = FastOutSlowInEasing)) { value, _ ->
                fraction = value
            } else fraction = edge
            if (target == RingDirection.SNOOZE) snooze?.invoke() else primary()
            committing = false
            returnToCenter()
        }
    }
    val settleAction by rememberUpdatedState<(RingDirection) -> Unit>(::settle)
    val resetAction by rememberUpdatedState<() -> Unit>(::returnToCenter)

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
                            if (onSnooze != null) add(CustomAccessibilityAction(snoozeText) { settle(RingDirection.SNOOZE); true })
                            add(CustomAccessibilityAction(primaryText) { settle(RingDirection.PRIMARY); true })
                        }
                    }
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyUp) false
                        else when (event.key) {
                            Key.DirectionLeft -> if (snooze == null) false else { settle(RingDirection.SNOOZE); true }
                            Key.DirectionRight, Key.Enter -> { settle(RingDirection.PRIMARY); true }
                            else -> false
                        }
                    }
                    .focusable()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            pressed = true
                            try {
                                waitForUpOrCancellation()
                            } finally { pressed = false }
                        }
                    }
                    .pointerInput(pixels) {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                if (!committing) {
                                    reset?.cancel()
                                    dragging = true
                                }
                            },
                            onDragCancel = {
                                dragging = false
                                if (!committing) resetAction()
                            },
                            onDragEnd = {
                                if (dragging) {
                                    dragging = false
                                    val target = ringDirection(fraction, snooze != null)
                                    if (target != null) settleAction(target) else resetAction()
                                }
                            },
                        ) { change, delta ->
                            change.consume()
                            if (dragging) {
                                val previous = ringDirection(fraction, snooze != null)
                                fraction = (fraction + delta / pixels).coerceIn(if (snooze == null) -.12f else -1f, 1f)
                                val target = ringDirection(fraction, snooze != null)
                                if (target != null && target != previous) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                        }
                    },
                shape = CircleShape,
                color = colors.surfaceContainerHighest,
            ) {
                Box(Modifier.fillMaxWidth().heightIn(min = 76.dp).drawBehind {
                    val position = fraction
                    if (position > 0f || position < 0f && onSnooze != null) {
                        drawRect(colors.primary.copy(alpha = abs(position) * .26f),
                            topLeft = Offset(if (position > 0f) size.width / 2 else 0f, 0f),
                            size = Size(size.width / 2, size.height))
                    }
                }, contentAlignment = Alignment.Center) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(snoozeText, Modifier.weight(1f).clearAndSetSemantics {}, textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelLarge,
                            color = when {
                                onSnooze == null -> colors.outline
                                armed == RingDirection.SNOOZE -> colors.onSurface
                                else -> colors.onSurfaceVariant
                            },
                            fontWeight = if (armed == RingDirection.SNOOZE) androidx.compose.ui.text.font.FontWeight.Bold else null)
                        Spacer(Modifier.width(62.dp))
                        Text(primaryText, Modifier.weight(1f).clearAndSetSemantics {}, textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelLarge, color = colors.onSurface,
                            fontWeight = if (armed == RingDirection.PRIMARY) androidx.compose.ui.text.font.FontWeight.Bold else null)
                    }
                    Surface(
                        Modifier.size(56.dp).graphicsLayer {
                            translationX = pixels * fraction
                            scaleX = thumbScale
                            scaleY = thumbScale
                        },
                        shape = CircleShape, color = colors.primary, contentColor = colors.onPrimary,
                    ) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.SwapHoriz, null) }
                    }
                }
            }
        }
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
