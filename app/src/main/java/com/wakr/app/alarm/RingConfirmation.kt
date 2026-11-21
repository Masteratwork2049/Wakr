package com.wakr.app.alarm

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

internal data class RingConfirmation(val snoozeMinutes: Int?, val origin: Offset) {
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
    val fill = if (result.snoozeMinutes != null) colors.secondaryContainer else colors.primaryContainer
    val ink = if (result.snoozeMinutes != null) colors.onSecondaryContainer else colors.onPrimaryContainer
    val motion = ValueAnimator.areAnimatorsEnabled()
    val spread = remember(result) { Animatable(0f) }
    val textAlpha = remember(result) { Animatable(0f) }
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    val finish by rememberUpdatedState(onFinished)

    LaunchedEffect(result) {
        launch {
            spread.animateTo(1f, tween(if (motion) 440 else 140, easing = FastOutSlowInEasing))
        }
        delay(if (motion) 210 else 100)
        textAlpha.animateTo(1f, tween(180, easing = LinearOutSlowInEasing))
        delay(650)
        finish()
    }

    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        },
    ) {
        Canvas(Modifier.fillMaxSize().onGloballyPositioned { overlayOrigin = it.positionInRoot() }) {
            val origin = result.origin - overlayOrigin
            val center = Offset(origin.x.coerceIn(0f, size.width), origin.y.coerceIn(0f, size.height))
            if (motion) {
                val radius = hypot(maxOf(center.x, size.width - center.x), maxOf(center.y, size.height - center.y))
                drawCircle(fill, radius = radius * spread.value, center = center)
            } else drawRect(fill, alpha = spread.value)
        }
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                result.message,
                Modifier.graphicsLayer { alpha = textAlpha.value }
                    .semantics { liveRegion = LiveRegionMode.Polite },
                color = ink,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
