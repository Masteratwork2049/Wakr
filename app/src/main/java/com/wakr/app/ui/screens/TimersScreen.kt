package com.wakr.app.ui.screens

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import com.wakr.app.core.ClockTimer
import com.wakr.app.data.DEFAULT_TIMER_PRESETS
import com.wakr.app.ui.timerReadoutGeometry
import com.wakr.app.ui.featheredVerticalViewport
import com.wakr.app.data.formatPresetLabel
import com.wakr.app.ui.glass
import com.wakr.app.ui.luminousElasticClickable
import com.wakr.app.ui.responsive.LocalAppWindowSizeInfo
import com.wakr.app.ui.responsive.TwoPaneLayout
import com.wakr.app.ui.responsive.ScrollPane
import androidx.compose.ui.text.rememberTextMeasurer
import com.wakr.app.ui.theme.ClockFace
import com.wakr.app.ui.theme.Numerals
import kotlinx.coroutines.delay
import java.time.Duration
import kotlin.math.sin

@Composable
internal fun TimersScreen(
    timers: List<ClockTimer>,
    draft: Duration,
    nowElapsedMillis: State<Long>,
    presets: List<Int> = DEFAULT_TIMER_PRESETS,
    onDigits: (String) -> Unit,
    onBackspace: () -> Unit,
    onResetDraft: () -> Unit,
    onPreset: (Int) -> Unit,
    onEditPreset: (Int, Int) -> Unit,
    onStart: (String) -> Unit,
    onPauseResume: (Long) -> Unit,
    onAddTen: (Long) -> Unit,
    onCancel: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val body = modifier.fillMaxSize().padding(contentPadding)
    if (timers.isNotEmpty()) {
        CountdownTimers(
            timers, nowElapsedMillis, presets, onPreset, onEditPreset, onPauseResume, onAddTen, onCancel, onRename,
            modifier = body.padding(horizontal = 16.dp),
            addTimerContent = { close ->
                AddCountdownDialog(draft, onDigits, onBackspace, onResetDraft, close, onStart)
            },
        )
    } else if (LocalAppWindowSizeInfo.current.isExpanded) {
        Box(body, contentAlignment = Alignment.Center) {
            ExpandedSetTimerBody(draft, presets, onDigits, onBackspace, onResetDraft, onPreset, onEditPreset) { onStart("") }
        }
    } else {
        Column(body.padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val readoutHeight = timerReadoutGeometry((maxWidth - 16.dp).coerceAtLeast(0.dp), formatTimer(draft)).capHeight + 24.dp
                val presetSpace = 96.dp + 24.dp * LocalDensity.current.fontScale
                val keyHeight = timerKeyHeight(maxHeight, readoutHeight, presetSpace, 70.dp)
                Box(Modifier.verticalScroll(rememberScrollState())) {
                    SetTimerBody(keyHeight, draft, presets, onDigits, onBackspace, onResetDraft, onPreset, onEditPreset)
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.width(170.dp).height(54.dp)) {
                WidePill(
                    text = "Start", icon = Icons.Rounded.PlayArrow, onClick = { onStart("") },
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                    fillWidth = true, height = 54.dp, compact = true, glow = false,
                )
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun AddCountdownDialog(
    draft: Duration,
    onDigits: (String) -> Unit,
    onBackspace: () -> Unit,
    onResetDraft: () -> Unit,
    onClose: () -> Unit,
    onStart: (String) -> Unit,
) {
    var name by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Add timer") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), label = { Text("Timer name") },
                )
                Text(formatTimer(draft), style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally))
                Keypad(keyHeight = 48.dp, onDigits = onDigits, onBackspace = onBackspace, onResetDraft = onResetDraft)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = !draft.isZero, onClick = { onStart(name); onClose() }) { Text("Start") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

private fun formatTimer(duration: Duration): String {
    val total = duration.seconds.coerceAtLeast(0)
    return "%02d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
}

private enum class TimerRollDirection { UP, DOWN }

@Composable
private fun UpwardSetupDigitCell(
    extraHeight: Dp = 52.dp,
    char: Char,
    width: Dp,
    capHeight: Dp,
    color: Color,
    numeralWidth: Float,
    staggerDelayMs: Int = 0,
) {
    var oldChar by remember { mutableStateOf<Char?>(null) }
    var newChar by remember { mutableStateOf(char) }
    val animProgress = remember { Animatable(1f) }

    LaunchedEffect(char) {
        if (char != newChar) {
            if (staggerDelayMs > 0) {
                delay(staggerDelayMs.toLong())
            }
            oldChar = newChar
            newChar = char
            animProgress.snapTo(0f)
            animProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing),
            )
        }
    }

    val progress = animProgress.value
    val density = LocalDensity.current
    val totalBoxHeight = capHeight + extraHeight
    val heightPx = with(density) { totalBoxHeight.toPx() }
    val isAnimating = progress < 1f && oldChar != null && oldChar != newChar

    Box(
        modifier = Modifier
            .width(width)
            .height(totalBoxHeight)
            .featheredVerticalViewport(active = isAnimating, fadeHeightFraction = 0.08f),
        contentAlignment = Alignment.Center,
    ) {
        if (char == ':') {
            Text(
                ":",
                fontFamily = ClockFace.family(opticalSize = 36f, width = 100f, weight = 550),
                fontSize = (capHeight.value * 0.65f).sp,
                color = color,
            )
        } else {
            // Outgoing digit: moves strictly UPWARDS from 0 to -heightPx while fading out and blurring
            if (progress < 1f && oldChar != null && oldChar != newChar && oldChar != ':') {
                val outgoingY = -progress * heightPx
                val outgoingAlpha = (1f - progress * 1.2f).coerceIn(0f, 1f)
                val outgoingBlurDp = (progress * 3.5f).dp

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(outgoingBlurDp)
                        .graphicsLayer {
                            translationY = outgoingY
                            alpha = outgoingAlpha
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Numerals(
                        text = oldChar.toString(),
                        capHeight = capHeight,
                        color = color,
                        width = numeralWidth,
                        weight = 600,
                        tracking = 0.004f,
                        slashedZero = false,
                        roundness = ClockFace.ROUNDED,
                    )
                }
            }

            // Incoming digit: moves UPWARDS with extended 18dp overshoot (Phase 1: 0ms..200ms UP; Phase 2: 200ms..480ms DOWN)
            val overshootPx = with(density) { 18.dp.toPx() } // Extended vertical overshoot (18dp)
            val incomingY = if (oldChar != null && oldChar != newChar) {
                if (progress < 0.42f) {
                    // Phase 1 (0ms -> 200ms): Travels UPWARDS from +heightPx past 0 to -18dp
                    val p1 = progress / 0.42f
                    heightPx - p1 * (heightPx + overshootPx)
                } else {
                    // Phase 2 (200ms -> 480ms): Settles smoothly and deliberately DOWNWARDS from -18dp to exact 0
                    val p2 = (progress - 0.42f) / 0.58f
                    -overshootPx * (1f + sin((p2 + 0.5f) * Math.PI.toFloat())) / 2f
                }
            } else {
                0f
            }

            val incomingAlpha = (progress * 1.4f).coerceIn(0f, 1f)
            val incomingBlurDp = ((1f - progress) * 3.5f).dp

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .blur(incomingBlurDp)
                    .graphicsLayer {
                        translationY = incomingY
                        alpha = incomingAlpha
                        scaleX = 1f
                        scaleY = 1f
                    },
                contentAlignment = Alignment.Center,
            ) {
                Numerals(
                    text = newChar.toString(),
                    capHeight = capHeight,
                    color = color,
                    width = numeralWidth,
                    weight = 600,
                    tracking = 0.008f,
                    slashedZero = false,
                    roundness = ClockFace.ROUNDED,
                )
            }
        }
    }
}

@Composable
private fun TickerTimerReadout(
    text: String,
    capHeight: Dp = 72.dp,
    color: Color = MaterialTheme.colorScheme.onSurface,
    scale: Float = 1f,
    spacing: androidx.compose.ui.unit.Dp = 3.dp,
    digitWidth: androidx.compose.ui.unit.Dp = 56.dp,
    colonWidth: androidx.compose.ui.unit.Dp = 15.dp,
    numeralWidth: Float = ClockFace.TIMER_WIDTH,
    autoSize: Boolean = false,
    setupCellExtraHeight: Dp = 52.dp,
    rollDirection: TimerRollDirection = TimerRollDirection.UP,
    enableStagger: Boolean = false,
) {
    BoxWithConstraintsCompat(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        if (autoSize) {
            val geometry = timerReadoutGeometry(maxWidth, text)
            val computedDigitWidth = geometry.digitWidth
            val computedColonWidth = geometry.colonWidth
            val computedSpacing = geometry.spacing
            val computedCapHeight = geometry.capHeight

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics { contentDescription = text },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(computedSpacing),
            ) {
                text.forEachIndexed { index, char ->
                    val staggerIndex = if (rollDirection == TimerRollDirection.UP) index else (text.length - 1 - index).coerceAtLeast(0)
                    val staggerDelayMs = if (enableStagger) (staggerIndex * 38) else 0

                    if (rollDirection == TimerRollDirection.UP) {
                        UpwardSetupDigitCell(
                            extraHeight = setupCellExtraHeight,
                            char = char,
                            width = if (char == ':') computedColonWidth else computedDigitWidth,
                            capHeight = computedCapHeight,
                            color = color,
                            numeralWidth = numeralWidth,
                            staggerDelayMs = staggerDelayMs,
                        )
                    } else {
                        TickerCell(
                            char = char,
                            width = if (char == ':') computedColonWidth else computedDigitWidth,
                            capHeight = computedCapHeight,
                            key = index,
                            color = color,
                            numeralWidth = numeralWidth,
                            rollDirection = rollDirection,
                            staggerDelayMs = staggerDelayMs,
                        )
                    }
                }
            }
        } else {
            val density = LocalDensity.current
            val desiredWidth = with(density) {
                (text.count { it != ':' } * digitWidth.toPx()) +
                    (text.count { it == ':' } * colonWidth.toPx()) +
                    ((text.length - 1).coerceAtLeast(0) * spacing.toPx())
            }
            val availableWidth = with(density) { maxWidth.toPx() }
            val fitScale = if (desiredWidth > 0f) (availableWidth / desiredWidth).coerceAtMost(1f) else 1f

            Row(
                modifier = Modifier
                    .graphicsLayer {
                        val effective = scale
                        scaleX = effective
                        scaleY = effective
                    }
                    .clearAndSetSemantics { contentDescription = text },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing * fitScale),
            ) {
                text.forEachIndexed { index, char ->
                    val staggerIndex = if (rollDirection == TimerRollDirection.UP) index else (text.length - 1 - index).coerceAtLeast(0)
                    val staggerDelayMs = if (enableStagger) (staggerIndex * 38) else 0

                    if (rollDirection == TimerRollDirection.UP) {
                        UpwardSetupDigitCell(
                            extraHeight = setupCellExtraHeight,
                            char = char,
                            width = (if (char == ':') colonWidth else digitWidth) * fitScale,
                            capHeight = capHeight * fitScale,
                            color = color,
                            numeralWidth = numeralWidth,
                            staggerDelayMs = staggerDelayMs,
                        )
                    } else {
                        TickerCell(
                            char = char,
                            width = (if (char == ':') colonWidth else digitWidth) * fitScale,
                            capHeight = capHeight * fitScale,
                            key = index,
                            color = color,
                            numeralWidth = numeralWidth,
                            rollDirection = rollDirection,
                            staggerDelayMs = staggerDelayMs,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A compatibility wrapper that keeps the timer readout's measuring logic local without changing
 * the surrounding screen API.
 */
@Composable
private fun BoxWithConstraintsCompat(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment,
    content: @Composable androidx.compose.foundation.layout.BoxWithConstraintsScope.() -> Unit,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier,
        contentAlignment = contentAlignment,
        content = content,
    )
}

@Composable
private fun TickerCell(
    char: Char,
    width: androidx.compose.ui.unit.Dp,
    capHeight: androidx.compose.ui.unit.Dp,
    key: Int,
    color: Color,
    numeralWidth: Float,
    rollDirection: TimerRollDirection,
    staggerDelayMs: Int = 0,
) {
    androidx.compose.animation.AnimatedContent(
        modifier = Modifier
            .width(width)
            .height(capHeight + 52.dp),
        targetState = char,
        transitionSpec = {
            val oldDigit = initialState.digitToIntOrNull()
            val newDigit = targetState.digitToIntOrNull()
            if (oldDigit == null || newDigit == null) {
                fadeIn(tween(100)) togetherWith fadeOut(tween(70)) using SizeTransform(clip = false)
            } else {
                val incomingDirection = if (rollDirection == TimerRollDirection.UP) 1 else -1
                val outgoingDirection = -incomingDirection

                val springSpec = if (rollDirection == TimerRollDirection.UP) {
                    spring<IntOffset>(
                        stiffness = 420f,
                        dampingRatio = 1.0f,
                    )
                } else {
                    spring<IntOffset>(
                        stiffness = 380f,
                        dampingRatio = 0.68f, // Active timer ticker detent overshoot
                    )
                }

                val incoming = slideInVertically(
                    animationSpec = springSpec,
                    initialOffsetY = { it * incomingDirection },
                )

                val outgoing = slideOutVertically(
                    animationSpec = tween(
                        durationMillis = if (rollDirection == TimerRollDirection.UP) 160 else 180,
                        delayMillis = staggerDelayMs,
                        easing = FastOutSlowInEasing,
                    ),
                    targetOffsetY = { it * outgoingDirection },
                )

                incoming + fadeIn(tween(90, delayMillis = staggerDelayMs)) togetherWith
                    (outgoing + fadeOut(tween(70, delayMillis = staggerDelayMs))) using SizeTransform(clip = false)
            }
        },
        label = "timerTicker-$key",
    ) { value ->
        val cellBlurProgress by transition.animateFloat(
            label = "cellBlurProgress",
            transitionSpec = {
                tween(durationMillis = 180, delayMillis = staggerDelayMs, easing = FastOutSlowInEasing)
            },
        ) { targetState ->
            if (targetState == EnterExitState.Visible) 0f else 1f
        }
        val currentBlurDp = (cellBlurProgress * 3.5f).dp
        val isCellAnimating = cellBlurProgress > 0.01f

        Box(
            modifier = Modifier
                .fillMaxSize()
                .blur(currentBlurDp)
                .featheredVerticalViewport(active = isCellAnimating, fadeHeightFraction = 0.08f),
            contentAlignment = Alignment.Center,
        ) {
            if (value == ':') {
                Text(
                    ":",
                    fontFamily = ClockFace.family(opticalSize = 36f, width = 100f, weight = if (rollDirection == TimerRollDirection.DOWN) 750 else 500, roundness = ClockFace.ROUNDED),
                    fontSize = (capHeight.value * 0.65f).sp,
                    color = color,
                )
            } else {
                Numerals(
                    text = value.toString(),
                    capHeight = capHeight,
                    color = color,
                    width = numeralWidth,
                    weight = if (rollDirection == TimerRollDirection.DOWN) 750 else ClockFace.TIMER_WEIGHT,
                    tracking = 0.008f,
                    slashedZero = false,
                    roundness = ClockFace.ROUNDED,
                )
            }
        }
    }
}

@Composable
private fun ExpandedSetTimerBody(
    draft: Duration,
    presets: List<Int>,
    onDigits: (String) -> Unit,
    onBackspace: () -> Unit,
    onResetDraft: () -> Unit,
    onPreset: (Int) -> Unit,
    onEditPreset: (Int, Int) -> Unit,
    onStart: () -> Unit,
) {
    var isLongPressReset by remember { mutableStateOf(false) }

    LaunchedEffect(draft) {
        if (!draft.isZero) {
            isLongPressReset = false
        }
    }

    TwoPaneLayout(
        leftPane = {
            ScrollPane(Modifier.padding(vertical = 8.dp, horizontal = 8.dp)) {
                Keypad(
                    onDigits = { digits ->
                        isLongPressReset = false
                        onDigits(digits)
                    },
                    onBackspace = {
                        isLongPressReset = false
                        onBackspace()
                    },
                    onResetDraft = {
                        isLongPressReset = true
                        onResetDraft()
                    },
                )
                Spacer(Modifier.height(14.dp))
                Presets(
                    presets = presets,
                    onEditPreset = onEditPreset,
                    onPreset = { seconds ->
                        isLongPressReset = false
                        onPreset(seconds)
                    },
                )
            }
        },
        rightPane = {
            ScrollPane(Modifier.padding(vertical = 12.dp, horizontal = 16.dp)) {
                TickerTimerReadout(
                    text = formatTimer(draft),
                    autoSize = true,
                    numeralWidth = 82f,
                    rollDirection = TimerRollDirection.UP,
                    enableStagger = isLongPressReset,
                )
                Spacer(Modifier.height(32.dp))
                Box(
                    modifier = Modifier
                        .width(220.dp)
                        .height(56.dp),
                ) {
                    WidePill(
                        text = "Start",
                        icon = Icons.Rounded.PlayArrow,
                        onClick = onStart,
                        container = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .72f),
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                        height = 56.dp,
                        compact = true,
                        fillWidth = true,
                        glow = false,
                    )
                }
            }
        },
        leftWeight = 1.1f,
        rightWeight = 0.9f,
        paneSpacing = 24.dp,
        contentPaddingDp = 12.dp,
    )
}

@Composable
private fun SetTimerBody(
    keyHeight: Dp,
    draft: Duration,
    presets: List<Int>,
    onDigits: (String) -> Unit,
    onBackspace: () -> Unit,
    onResetDraft: () -> Unit,
    onPreset: (Int) -> Unit,
    onEditPreset: (Int, Int) -> Unit,
) {
    var isLongPressReset by remember { mutableStateOf(false) }

    LaunchedEffect(draft) {
        if (!draft.isZero) {
            isLongPressReset = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(4.dp))
        TickerTimerReadout(
            text = formatTimer(draft),
            autoSize = true,
            setupCellExtraHeight = 24.dp,
            numeralWidth = 82f,
            rollDirection = TimerRollDirection.UP,
            enableStagger = isLongPressReset,
        )
        Spacer(Modifier.height(6.dp))
        Keypad(
            keyHeight = keyHeight,
            onDigits = { digits ->
                isLongPressReset = false
                onDigits(digits)
            },
            onBackspace = {
                isLongPressReset = false
                onBackspace()
            },
            onResetDraft = {
                isLongPressReset = true
                onResetDraft()
            },
        )
        Spacer(Modifier.height(18.dp))
        Presets(
            presets = presets,
            onEditPreset = onEditPreset,
            onPreset = { seconds ->
                isLongPressReset = false
                onPreset(seconds)
            },
        )
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
internal fun Presets(
    presets: List<Int>,
    onPreset: (Int) -> Unit,
    onEditPreset: (Int, Int) -> Unit,
    compact: Boolean = false,
) {
    var editingIndex by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Int?>(null) }
    editingIndex?.let { index ->
        presets.getOrNull(index)?.let { seconds ->
            SinglePresetEditDialog(
                title = "Preset ${index + 1}", initialSeconds = seconds,
                onDismiss = { editingIndex = null },
                onConfirm = { updated -> onEditPreset(index, updated); editingIndex = null },
            )
        }
    }
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            "Presets",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val measurer = rememberTextMeasurer()
            val style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLargeEmphasized
            val required = with(density) { (presets.maxOfOrNull {
                measurer.measure(formatPresetLabel(it), style).size.width
            } ?: 0).toDp() } + if (compact) 12.dp else 24.dp
            if (compact && presets.isNotEmpty()) {
                val spacing = 4.dp
                val width = maxOf(48.dp, required, (maxWidth - spacing * (presets.size - 1)) / presets.size)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    presets.forEachIndexed { index, seconds ->
                        Box(Modifier.width(width)) {
                            PresetButton(seconds, onClick = { onPreset(seconds) },
                                onLongClick = { editingIndex = index }, compact = true)
                        }
                    }
                }
                return@BoxWithConstraints
            }
            val maxColumns = ((maxWidth + 6.dp) / (required + 6.dp)).toInt().coerceIn(1, presets.size.coerceAtLeast(1))
            val rows = (presets.size + maxColumns - 1) / maxColumns
            val columns = if (rows == 0) 1 else (presets.size + rows - 1) / rows
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                presets.chunked(columns).forEachIndexed { rowIndex, row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEachIndexed { columnIndex, seconds ->
                            val index = rowIndex * columns + columnIndex
                            Box(Modifier.weight(1f)) {
                                PresetButton(seconds, onClick = { onPreset(seconds) },
                                    onLongClick = { editingIndex = index })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetButton(seconds: Int, onClick: () -> Unit, onLongClick: () -> Unit, compact: Boolean = false) {
    val presetShape = RoundedCornerShape(27.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 48.dp else 54.dp)
            .luminousElasticClickable(shape = presetShape, onClick = onClick, onLongClick = onLongClick)
            .clip(presetShape)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .92f))
            .border(1.25.dp, MaterialTheme.colorScheme.primary.copy(alpha = .28f), presetShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            formatPresetLabel(seconds),
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLargeEmphasized,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun Keypad(keyHeight: Dp = 76.dp, onDigits: (String) -> Unit, onBackspace: () -> Unit, onResetDraft: () -> Unit) {
    val view = LocalView.current
    val rows = listOf("123", "456", "789")
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { c -> DigitKey(c.toString(), onDigits, Modifier.weight(1f), keyHeight) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DigitKey("00", onDigits, Modifier.weight(1f), keyHeight)
            DigitKey("0", onDigits, Modifier.weight(1f), keyHeight)
            KeyBox(
                height = keyHeight,
                modifier = Modifier.weight(1f),
                onClick = {
                    onBackspace()
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                },
                onLongClick = {
                    onResetDraft()
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                },
                container = MaterialTheme.colorScheme.surfaceContainerHigh,
                label = "Delete",
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.Backspace,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

@Composable
private fun DigitKey(digits: String, onDigits: (String) -> Unit, modifier: Modifier = Modifier, keyHeight: Dp = 76.dp) {
    val view = LocalView.current
    KeyBox(height = keyHeight, modifier = modifier, onClick = {
        onDigits(digits)
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }, label = digits) {
        Text(
            digits,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = ClockFace.family(opticalSize = 30f, width = ClockFace.NORMAL, weight = 500),
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun KeyBox(
    height: Dp = 76.dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    label: String,
    content: @Composable () -> Unit,
) {
    val buttonShape = RoundedCornerShape(38.dp)
    Box(
        modifier = modifier
            .height(height)
            .padding(2.dp)
            .luminousElasticClickable(
                shape = buttonShape,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .clip(buttonShape)
            .glass(buttonShape, container.copy(alpha = .88f), .84f, shadowElevation = 0.dp)
            .clearAndSetSemantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
