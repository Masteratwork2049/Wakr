package com.wakr.app.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.wakr.app.core.ClockTimer
import com.wakr.app.core.MAX_TIMERS
import com.wakr.app.core.TimerState
import com.wakr.app.ui.theme.ClockFace
import java.util.Locale

@Composable
internal fun CountdownTimers(
    timers: List<ClockTimer>,
    now: State<Long>,
    presets: List<Int>,
    onPreset: (Int) -> Unit,
    onEditPreset: (Int, Int) -> Unit,
    onPauseResume: (Long) -> Unit,
    onAddTen: (Long) -> Unit,
    onCancel: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    modifier: Modifier,
    addTimerContent: @Composable (() -> Unit) -> Unit,
) {
    val ids = timers.map { it.id }
    var selectedId by rememberSaveable { mutableStateOf(timers.last().id) }
    var knownIds by remember { mutableStateOf(ids) }
    LaunchedEffect(ids) {
        val added = ids.lastOrNull { it !in knownIds }
        selectedId = added ?: selectedId.takeIf { it in ids } ?: ids.last()
        knownIds = ids
    }
    val selected = timers.firstOrNull { it.id == selectedId } ?: timers.last()
    val hasRoom = timers.size < MAX_TIMERS
    var adding by rememberSaveable { mutableStateOf(false) }
    if (adding && hasRoom) addTimerContent { adding = false }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    timers.firstOrNull { it.id == editingId }?.let { timer ->
        EditTimerNameDialog(timer, { editingId = null }) { name ->
            onRename(timer.id, name)
            editingId = null
        }
    }

    BoxWithConstraints(modifier) {
        val controlsMaxHeight = maxHeight * .72f
        val wideControls = maxWidth >= 400.dp
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            CountdownDialLayout(timers, now, selected.id, { selectedId = it }, Modifier.weight(1f))
            Column(
                Modifier.widthIn(max = 510.dp).fillMaxWidth().heightIn(max = controlsMaxHeight)
                    .verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val finished by remember(selected, now) { derivedStateOf {
                    selected.state == TimerState.FINISHED ||
                        selected.state == TimerState.RUNNING && selected.remaining(now.value).isZero
                } }
                Crossfade(selected.label, animationSpec = tween(220), label = "Selected timer name") { name ->
                    Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (finished) {
                        Button(onClick = { onCancel(selected.id) }, modifier = Modifier.weight(1f)) { Text("Dismiss") }
                    } else {
                        FilledTonalButton(onClick = { onPauseResume(selected.id) }, modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text(if (selected.state == TimerState.RUNNING) "Pause" else "Resume", maxLines = 1)
                        }
                        OutlinedButton(onClick = { onAddTen(selected.id) }, modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp)) { Text("+10 s", maxLines = 1) }
                        OutlinedButton(onClick = { onCancel(selected.id) }, modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Cancel", maxLines = 1) }
                    }
                }
                val presetRow: @Composable () -> Unit = {
                    Presets(presets, { if (hasRoom) onPreset(it) }, onEditPreset, compact = true)
                }
                val nameAndAdd: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { editingId = selected.id }, modifier = Modifier.fillMaxWidth()) {
                            Text("Edit name")
                        }
                        OutlinedButton(onClick = { adding = true }, enabled = hasRoom, modifier = Modifier.fillMaxWidth()) {
                            Text(if (hasRoom) "Add timer" else "Add timer · 4 of 4")
                        }
                    }
                }
                if (wideControls) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f).align(Alignment.CenterVertically)) { presetRow() }
                        Box(Modifier.width(170.dp)) { nameAndAdd() }
                    }
                } else {
                    presetRow()
                    nameAndAdd()
                }
            }
        }
    }
}

@Composable
private fun EditTimerNameDialog(timer: ClockTimer, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable(timer.id) { mutableStateOf(if (timer.usesDefaultLabel) "" else timer.label) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 420.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Edit name") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Timer name") },
                )
                Text("Leave blank to use the timer number.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CountdownDialLayout(
    timers: List<ClockTimer>,
    now: State<Long>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth().padding(top = 8.dp).clipToBounds()) {
        val rows = if (timers.size <= 2) 1 else 2
        val columns = if (timers.size == 1) 1 else 2
        val gap = minOf(12.dp, maxWidth / 8, maxHeight / 8)
        val faceSize = minOf(410.dp, (maxWidth - gap * (columns - 1)) / columns,
            (maxHeight - gap * (rows - 1)) / rows).coerceAtLeast(0.dp)
        if (faceSize <= 0.dp) return@BoxWithConstraints
        val centerX = (maxWidth - faceSize) / 2
        val rowTop = (maxHeight - faceSize * rows - gap * (rows - 1)) / 2
        val motion = MaterialTheme.motionScheme.defaultEffectsSpec<androidx.compose.ui.unit.Dp>()
        timers.forEachIndexed { index, timer ->
            key(timer.id) {
                val centered = columns == 1 || timers.size == 3 && index == 2
                val targetX = if (centered) centerX else centerX + (faceSize + gap) / 2 * if (index % 2 == 0) -1 else 1
                val targetY = rowTop + if (index < 2) 0.dp else faceSize + gap
                val x by animateDpAsState(targetX, motion, label = "Timer horizontal position")
                val y by animateDpAsState(targetY, motion, label = "Timer vertical position")
                val diameter by animateDpAsState(faceSize, motion, label = "Timer diameter")
                var entered by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { entered = true }
                val reveal by animateFloatAsState(if (entered) 1f else 0f,
                    tween(190, delayMillis = if (index == 0) 0 else 75, easing = FastOutSlowInEasing),
                    label = "Timer dial entry")
                val picked = timer.id == selectedId
                StopwatchDial(
                    modifier = Modifier.offset { androidx.compose.ui.unit.IntOffset(x.roundToPx(), y.roundToPx()) }
                        .size(diameter)
                        .graphicsLayer {
                            alpha = reveal
                            scaleX = .9f + .1f * reveal
                            scaleY = scaleX
                        }
                        .semantics { selected = picked },
                    turns = { -timer.counterclockwiseProgress(now.value).toFloat() },
                    onClick = { onSelect(timer.id) },
                    selected = picked,
                ) {
                    val seconds by remember(timer, now) { derivedStateOf {
                        (timer.remaining(now.value).toMillis() + 999L) / 1_000L
                    } }
                    val readout = remember(seconds) { countdownText(seconds) }
                    val fontSize = with(LocalDensity.current) { (diameter * if (seconds >= 3_600) .115f else .135f).toSp() }
                    Column(Modifier.padding(horizontal = diameter * .12f), horizontalAlignment = Alignment.CenterHorizontally) {
                        val family = ClockFace.family(opticalSize = 34f, width = 100f, weight = 500, roundness = ClockFace.ROUNDED)
                        Text(
                            readout,
                            fontFamily = family,
                            fontSize = fontSize,
                            lineHeight = fontSize,
                            style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Crossfade(
                            if (seconds <= 0) "Time’s up" else timer.label,
                            animationSpec = tween(220),
                            label = "Timer name",
                        ) { name ->
                            Text(
                                name,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = with(LocalDensity.current) { (diameter * .09f).toSp().value.coerceAtMost(12f).sp },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun countdownText(seconds: Long): String =
    if (seconds >= 3_600) String.format(Locale.getDefault(), "%d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60)
