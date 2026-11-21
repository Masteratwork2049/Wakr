package com.wakr.app.ui.missions

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import com.wakr.app.core.*
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.random.Random

internal val MissionType.title: String
    get() = when (this) {
        MissionType.QR -> "QR or barcode"
        MissionType.NFC -> "NFC tag"
        MissionType.FOCUS_TAP -> "Focus Tap"
        MissionType.MEMORY -> "Memory"
        MissionType.PUZZLE -> "Puzzle"
        MissionType.TYPING -> "Typing"
        MissionType.MATH -> "Math"
    }

internal val AlarmMission.puzzleMode: String
    get() = runCatching { JSONObject(configJson).optString("puzzle", "Numbers in order") }.getOrDefault("Numbers in order")

internal val AlarmMission.memoryBoardSize: Int
    get() = runCatching { JSONObject(configJson).optInt("boardSize", 3) }.getOrDefault(3).coerceIn(3, 5)

@Composable
internal fun MissionChallenge(mission: AlarmMission, seed: Long, allowScannerFallback: Boolean = false, onPass: () -> Unit) {
    val onComplete by rememberUpdatedState(onPass)
    key(mission.id, seed) {
        var completed by remember { mutableStateOf(false) }
        fun pass() {
            if (completed) return
            completed = true
            onComplete()
        }
        // Each challenge may emit instructions, feedback, and controls separately.
        // Keep them in a vertical layout even inside the ringing screen’s touch tracker.
        MissionContent {
            when (mission.type) {
                MissionType.MATH -> MathChallenge(mission, seed, ::pass)
                MissionType.MEMORY -> MemoryChallenge(mission, seed, ::pass)
                MissionType.FOCUS_TAP -> FocusChallenge(seed, ::pass)
                MissionType.PUZZLE -> PuzzleChallenge(mission, seed, ::pass)
                MissionType.TYPING -> TypingChallenge(seed, ::pass)
                MissionType.QR, MissionType.NFC -> {
                    val target = mission.target
                    var fallback by rememberSaveable { mutableStateOf(false) }
                    var mismatch by remember { mutableStateOf(false) }
                    when {
                        fallback -> MathChallenge(
                            mission.copy(difficulty = MissionDifficulty.HARD, roundsOrCount = 3), seed, ::pass,
                        )
                        target == null -> {
                            MissionScannerPrompt(
                                if (mission.type == MissionType.NFC) Icons.Outlined.Nfc else Icons.Outlined.QrCodeScanner,
                                "This mission needs a registered item.",
                            )
                            if (allowScannerFallback) TextButton(onClick = { fallback = true }) {
                                Text("Solve a math challenge")
                            }
                        }
                        else -> {
                            if (mismatch) Text("That item does not match. Scan ${target.label}.")
                            ScanMission(mission.type, onUnavailable = if (allowScannerFallback) ({ fallback = true }) else null) {
                                if (it == target.value) pass() else mismatch = true
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class MissionSpace(val minimumHeight: Dp, val weight: Int)

/** Content can grow into its panel while keeping a usable height when the panel must scroll. */
internal fun Modifier.missionSpace(minimumHeight: Dp, weight: Int = 1): Modifier =
    layoutId(MissionSpace(minimumHeight, weight)).heightIn(min = minimumHeight)

@Composable
private fun MissionContent(content: @Composable () -> Unit) {
    val panelHeight = LocalMissionChallengeHeight.current
    Layout(content = content, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val gap = 10.dp.roundToPx()
        val loose = constraints.copy(minHeight = 0)
        val growing = measurables.map { it.layoutId as? MissionSpace }
        val fixed = measurables.mapIndexed { index, item ->
            if (growing[index] == null) item.measure(loose) else null
        }
        val spacing = gap * (measurables.size - 1).coerceAtLeast(0)
        val minimum = growing.sumOf { it?.minimumHeight?.roundToPx() ?: 0 }
        val totalWeight = growing.sumOf { it?.weight ?: 0 }
        val extra = ((panelHeight - 32.dp).roundToPx() - fixed.sumOf { it?.height ?: 0 } - minimum - spacing)
            .coerceAtLeast(0)
        var remaining = extra
        var remainingWeight = totalWeight
        val sections = measurables.mapIndexed { index, item ->
            fixed[index] ?: run {
                val slot = requireNotNull(growing[index])
                val share = (remaining.toLong() * slot.weight / remainingWeight).toInt()
                remaining -= share
                remainingWeight -= slot.weight
                val height = (slot.minimumHeight.roundToPx() + share).coerceAtMost(loose.maxHeight)
                item.measure(Constraints.fixed(loose.maxWidth, height))
            }
        }
        layout(constraints.maxWidth, constraints.constrainHeight(sections.sumOf { it.height } + spacing)) {
            var top = 0
            for (section in sections) {
                section.placeRelative(0, top)
                top += section.height + gap
            }
        }
    }
}

@Composable
private fun MathChallenge(mission: AlarmMission, seed: Long, pass: () -> Unit) {
    var solved by rememberSaveable { mutableIntStateOf(0) }
    var digits by rememberSaveable { mutableStateOf("") }
    var incorrect by rememberSaveable { mutableStateOf(false) }
    val count = mission.roundsOrCount.coerceIn(1, 20)
    val problem = remember(solved) { mathProblem(mission.difficulty, Random(seed + solved)) }
    val panelHeight = LocalMissionChallengeHeight.current
    val colors = MaterialTheme.colorScheme
    Layout(modifier = Modifier.fillMaxWidth(), content = {
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = colors.surfaceContainerHighest) {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Problem ${(solved + 1).coerceAtMost(count)} of $count",
                    style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                Text(problem.expression, style = MaterialTheme.typography.displaySmall, color = colors.onSurface)
            }
        }
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = colors.surface) {
            Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (incorrect) "Try again" else "Answer", style = MaterialTheme.typography.labelLarge,
                    color = if (incorrect) colors.error else colors.onSurfaceVariant)
                Text(digits.ifEmpty { "—" }, style = MaterialTheme.typography.titleLarge)
            }
        }
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("Clear", "0", "Check")).forEach { keys ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                keys.forEach { key ->
                    FilledTonalButton(modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 52.dp),
                        enabled = solved < count && (key != "Check" || digits.isNotEmpty()),
                        colors = if (key == "Check") ButtonDefaults.filledTonalButtonColors(
                            containerColor = colors.primary, contentColor = colors.onPrimary,
                        ) else ButtonDefaults.filledTonalButtonColors(),
                        onClick = {
                        when (key) {
                            "Clear" -> { digits = ""; incorrect = false }
                            "Check" -> {
                                incorrect = digits.toIntOrNull() != problem.answer
                                if (!incorrect && ++solved == count) pass()
                                digits = ""
                            }
                            else -> if (digits.length < 4) { digits += key; incorrect = false }
                        }
                    }, contentPadding = PaddingValues(3.dp)) { Text(key) }
                }
            }
        }
    }) { measurables, constraints ->
        val gap = 6.dp.roundToPx()
        val loose = constraints.copy(minHeight = 0)
        val problem = measurables[0].measure(loose)
        val answer = measurables[1].measure(loose)
        val rows = measurables.drop(2)
        // MissionScreen applies 16 dp of padding above and below the challenge.
        val available = (panelHeight - 32.dp).roundToPx()
        val keypadHeight = (available - problem.height - answer.height - gap * (measurables.size - 1))
            .coerceAtLeast(52.dp.roundToPx() * rows.size)
        val keys = rows.mapIndexed { index, row ->
            val height = keypadHeight / rows.size + if (index < keypadHeight % rows.size) 1 else 0
            row.measure(loose.copy(minHeight = height.coerceAtMost(loose.maxHeight)))
        }
        val sections = listOf(problem, answer) + keys
        val height = sections.sumOf { it.height } + gap * (sections.size - 1)
        layout(constraints.maxWidth, constraints.constrainHeight(height)) {
            var top = 0
            for (section in sections) {
                section.placeRelative(0, top)
                top += section.height + gap
            }
        }
    }
}

@Composable
private fun MemoryChallenge(mission: AlarmMission, seed: Long, pass: () -> Unit) {
    val board = mission.memoryBoardSize
    val cellNames = remember(board) { List(board * board) { "Row ${it / board + 1}, column ${it % board + 1}" } }
    val rounds = mission.roundsOrCount.coerceIn(1, 10)
    var round by rememberSaveable { mutableIntStateOf(0) }
    var entry by rememberSaveable { mutableIntStateOf(0) }
    var retry by rememberSaveable { mutableIntStateOf(0) }
    var lit by remember { mutableIntStateOf(-1) }
    var showing by rememberSaveable { mutableStateOf(true) }
    val sequence = remember(round, board) {
        val random = Random(seed + round)
        List(3 + mission.difficulty.ordinal + round) { random.nextInt(board * board) }
    }
    LaunchedEffect(round, retry) {
        if (!showing || round >= rounds) return@LaunchedEffect
        delay(500)
        sequence.forEach { cell ->
            lit = cell
            delay(500)
            lit = -1
            delay(180)
        }
        showing = false
    }

    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = colors.surfaceContainerHighest) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Round ${(round + 1).coerceAtMost(rounds)} of $rounds",
                style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            Text(
                when {
                    round >= rounds -> "Pattern complete"
                    showing -> "Watch the pattern"
                    else -> "Repeat the pattern"
                },
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                when {
                    round >= rounds -> "All rounds complete"
                    showing && lit >= 0 -> cellNames[lit]
                    showing && retry > 0 -> "Try again. Watch the replay."
                    showing -> "Watch the tiles light up in order."
                    else -> "$entry of ${sequence.size} tiles entered"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
    TileGrid(cellNames, highlight = lit, enabled = !showing && round < rounds, columns = board, showLabels = false) { index ->
        if (index != sequence[entry]) {
            entry = 0
            showing = true
            retry++
        } else if (++entry == sequence.size) {
            entry = 0
            retry = 0
            if (++round == rounds) pass() else showing = true
        }
    }
}

@Composable
private fun FocusChallenge(seed: Long, pass: () -> Unit) {
    var hit by rememberSaveable { mutableIntStateOf(0) }
    var frame by rememberSaveable { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(true) }
    val target = remember(frame) { Random(seed + frame).nextInt(9) }
    LaunchedEffect(frame) {
        visible = true
        delay(1500)
        visible = false
        delay(350)
        frame++
    }
    Text("Tap the diamond · ${hit.coerceAtMost(5)} of 5")
    TileGrid(List(9) { if (!visible) " " else if (it == target) "◆" else "●" }, enabled = visible) {
        if (it == target) {
            if (++hit == 5) pass()
        } else hit = 0
        frame++
    }
}

@Composable
private fun PuzzleChallenge(mission: AlarmMission, seed: Long, pass: () -> Unit) {
    val mode = mission.puzzleMode
    val random = remember { Random(seed) }
    when (mode) {
        "Sliding tiles" -> {
            var tiles by rememberSaveable { mutableStateOf(ArrayList(shuffledTiles(random))) }
            Text("Slide the tiles into order")
            TileGrid(tiles.map { if (it == 0) " " else it.toString() }) { index ->
                val gap = tiles.indexOf(0)
                if (adjacentTiles(index, gap)) {
                    tiles = ArrayList(tiles).also { it[gap] = it[index]; it[index] = 0 }
                    if (tiles == (1..8).toList() + 0) pass()
                }
            }
        }
        "Trace pattern" -> {
            val pattern = remember { listOf(0, 1, 4, 7, 8).let { if (random.nextBoolean()) it else it.map { cell -> 8 - cell } } }
            TracePattern(pattern, pass)
        }
        else -> {
            var expected by rememberSaveable { mutableIntStateOf(1) }
            val numbers = remember { (1..9).shuffled(random) }
            Text("Tap the numbers in order")
            TileGrid(numbers.map { if (it < expected) "✓" else it.toString() }) {
                if (numbers[it] == expected) {
                    if (++expected == 10) pass()
                } else expected = 1
            }
        }
    }
}

@Composable
private fun TracePattern(pattern: List<Int>, pass: () -> Unit) {
    var traced by remember { mutableStateOf(emptyList<Int>()) }
    val ink = MaterialTheme.colorScheme.primary
    val soft = MaterialTheme.colorScheme.outlineVariant
    val done by rememberUpdatedState(pass)
    Text("Trace the highlighted path in order from 1")
    val root = Modifier.fillMaxWidth().missionSpace(160.dp)
    Canvas(root.pointerInput(pattern) {
        fun hit(position: Offset) {
            if (position.x !in 0f..size.width.toFloat() || position.y !in 0f..size.height.toFloat()) return
            val col = ((position.x / size.width) * 3).toInt().coerceIn(0, 2)
            val row = ((position.y / size.height) * 3).toInt().coerceIn(0, 2)
            val cell = row * 3 + col
            if (cell == traced.lastOrNull()) return
            traced = if (cell == pattern.getOrNull(traced.size)) traced + cell else emptyList()
        }
        detectDragGestures(onDragStart = { traced = emptyList(); hit(it) }, onDragCancel = { traced = emptyList() },
            onDragEnd = { if (traced == pattern) done() else traced = emptyList() }) { change, _ -> hit(change.position) }
    }) {
        fun center(cell: Int) = Offset((cell % 3 + .5f) * size.width / 3, (cell / 3 + .5f) * size.height / 3)
        pattern.zipWithNext().forEach { (a, b) -> drawLine(soft, center(a), center(b), 4.dp.toPx()) }
        traced.zipWithNext().forEach { (a, b) -> drawLine(ink, center(a), center(b), 4.dp.toPx()) }
        repeat(9) { cell ->
            drawCircle(if (cell in pattern) ink else soft, if (cell == pattern.first()) 12.dp.toPx() else 7.dp.toPx(), center(cell))
        }
    }
    // Buttons provide the same ordered challenge for switch access and TalkBack.
    TileGrid(List(9) { cell -> if (cell in pattern) (pattern.indexOf(cell) + 1).toString() else "–" }) { cell ->
        traced = if (cell == pattern.getOrNull(traced.size)) traced + cell else emptyList()
        if (traced == pattern) pass()
    }
}

@Composable
private fun TypingChallenge(seed: Long, pass: () -> Unit) {
    val phrase = remember { listOf("A quiet morning begins", "One step starts the day", "The sky is getting brighter").random(Random(seed)) }
    var written by rememberSaveable { mutableStateOf("") }
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    val clipboard = LocalClipboard.current
    Text(phrase, style = MaterialTheme.typography.titleLarge)
    CompositionLocalProvider(LocalClipboard provides remember(clipboard) {
        object : androidx.compose.ui.platform.Clipboard by clipboard {
            override suspend fun getClipEntry(): ClipEntry? = null
        }
    }) {
        Surface(Modifier.fillMaxWidth().missionSpace(80.dp), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            BasicTextField(value = written, onValueChange = {
                if (it.length <= phrase.length && typingStepAllowed(written, it)) written = it
            },
                modifier = Modifier.fillMaxSize().padding(16.dp), textStyle = textStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrectEnabled = false),
                decorationBox = { field -> Box { if (written.isEmpty()) Text("Type the phrase"); field() } })
        }
    }
    FilledTonalButton(modifier = Modifier.fillMaxWidth(), enabled = written == phrase, onClick = pass) { Text("Check") }
}

@Composable
private fun TileGrid(
    labels: List<String>, highlight: Int = -1, enabled: Boolean = true, columns: Int = 3,
    showLabels: Boolean = true, onTap: (Int) -> Unit,
) {
    val rowGap = 4.dp
    val minimum = 48.dp * columns + rowGap * (columns - 1)
    Column(Modifier.fillMaxWidth().missionSpace(minimum), verticalArrangement = Arrangement.spacedBy(rowGap)) {
        repeat(columns) { row ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(columns) { col ->
                    val index = row * columns + col
                    FilledTonalButton(onClick = { onTap(index) }, enabled = enabled,
                        modifier = Modifier.weight(1f).fillMaxHeight().then(
                            if (showLabels) Modifier else Modifier.semantics { contentDescription = labels[index] },
                        ),
                        colors = if (index == highlight) ButtonDefaults.filledTonalButtonColors(
                            disabledContainerColor = MaterialTheme.colorScheme.primary,
                            disabledContentColor = MaterialTheme.colorScheme.onPrimary,
                        ) else ButtonDefaults.filledTonalButtonColors()) { if (showLabels) Text(labels[index]) }
                }
            }
        }
    }
}
