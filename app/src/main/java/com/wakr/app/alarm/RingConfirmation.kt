package com.wakr.app.alarm

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

internal const val RING_CONFIRMATION_HOLD_MS = 3_000L
private val RevealEasing = CubicBezierEasing(.15f, .55f, .25f, 1f)

internal data class RingConfirmation(val snoozeMinutes: Int?, val origin: Offset, val emergency: Boolean = false) {
    val message: String
        get() = when (snoozeMinutes) {
            null -> "Alarm dismissed"
            1 -> "Snoozed for 1 minute"
            else -> "Snoozed for $snoozeMinutes minutes"
        }
}

@Composable
internal fun RingConfirmationOverlay(result: RingConfirmation, onFinished: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val fill = when {
        result.emergency -> colors.errorContainer
        result.snoozeMinutes != null -> colors.secondaryContainer
        else -> colors.primaryContainer
    }
    val ink = when {
        result.emergency -> colors.onErrorContainer
        result.snoozeMinutes != null -> colors.onSecondaryContainer
        else -> colors.onPrimaryContainer
    }
    val motion = ValueAnimator.areAnimatorsEnabled()
    val spread = remember(result) { Animatable(0f) }
    val textAlpha = remember(result) { Animatable(0f) }
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    val finish by rememberUpdatedState(onFinished)

    LaunchedEffect(result) {
        coroutineScope {
            launch { spread.animateTo(1f, tween(if (motion) 440 else 140, easing = RevealEasing)) }
            delay(if (motion) 155 else 80)
            textAlpha.animateTo(1f, tween(170, easing = LinearOutSlowInEasing))
        }
        delay(RING_CONFIRMATION_HOLD_MS)
        finish()
    }

    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        },
    ) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { overlayOrigin = it.positionInRoot() }
            .drawWithCache {
                val origin = result.origin - overlayOrigin
                val center = Offset(origin.x.coerceIn(0f, size.width), origin.y.coerceIn(0f, size.height))
                val radius = hypot(maxOf(center.x, size.width - center.x), maxOf(center.y, size.height - center.y))
                onDrawBehind {
                    if (motion && spread.value < 1f) drawCircle(fill, radius = radius * spread.value, center = center)
                    else drawRect(fill, alpha = spread.value)
                }
            })
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.graphicsLayer { alpha = textAlpha.value }.semantics { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(result.message, color = ink, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                if (result.emergency) Text("Emergency", color = ink, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
