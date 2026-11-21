package com.wakr.app.ui.sheets

import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wakr.app.core.*
import com.wakr.app.data.jsonArray
import com.wakr.app.data.records
import com.wakr.app.data.toJson
import com.wakr.app.data.toMission
import com.wakr.app.ui.missions.*
import org.json.JSONArray
import org.json.JSONObject

internal val MissionDraftSaver = Saver<List<AlarmMission>, String>(
    save = { jsonArray(it, AlarmMission::toJson).toString() },
    restore = { JSONArray(it).records(JSONObject::toMission) },
)

@Composable
internal fun MissionOptions(
    wake: WakeOptions, missions: List<AlarmMission>,
    onWake: (WakeOptions) -> Unit, onMissions: (List<AlarmMission>) -> Unit,
    soundRows: (@Composable () -> Unit)? = null,
    scheduleRows: (@Composable () -> Unit)? = null,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var edit by remember { mutableStateOf<AlarmMission?>(null) }
    var preview by remember { mutableStateOf<AlarmMission?>(null) }
    var removed by remember { mutableStateOf<Pair<Int, AlarmMission>?>(null) }
    DifficultyOptions(wake, onWake)
    Spacer(Modifier.height(12.dp))
    val summary = when (missions.size) {
        0 -> "No missions"
        1 -> missions.single().type.title
        else -> "${missions.size} missions"
    }
    AlarmEditorSection("Wake-up missions", summary, Icons.Outlined.Psychology) {
        missions.forEachIndexed { index, mission ->
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Column(Modifier.padding(8.dp)) {
                    AlarmOptionRow(mission.type.title, Icons.Outlined.Edit, mission.target?.label ?: when (mission.type) {
                        MissionType.PUZZLE -> mission.puzzleMode
                        MissionType.MATH, MissionType.MEMORY -> "${mission.roundsOrCount} · ${mission.difficulty.name.lowercase().replaceFirstChar(Char::uppercase)}"
                        else -> null
                    }) { edit = mission }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { preview = mission }) { Text("Try it") }
                        Row {
                            IconButton(enabled = index > 0, onClick = {
                                onMissions(missions.toMutableList().apply { add(index - 1, removeAt(index)) })
                            }) { Icon(Icons.Outlined.ArrowUpward, "Move up") }
                            IconButton(enabled = index < missions.lastIndex, onClick = {
                                onMissions(missions.toMutableList().apply { add(index + 1, removeAt(index)) })
                            }) { Icon(Icons.Outlined.ArrowDownward, "Move down") }
                            IconButton(onClick = {
                                removed = index to mission
                                onMissions(missions.filter { it.id != mission.id })
                            }) { Icon(Icons.Outlined.Delete, "Remove mission") }
                        }
                    }
                }
            }
        }
        removed?.let { (index, mission) ->
            Snackbar(modifier = Modifier.padding(8.dp), action = {
                TextButton(onClick = {
                    onMissions(missions.toMutableList().apply { add(index.coerceAtMost(size), mission) })
                    removed = null
                }) { Text("Undo") }
            }) { Text("Mission removed") }
        }
        FilledTonalButton(onClick = { adding = true }, modifier = Modifier.padding(12.dp)) { Text("Add mission") }
    }
    Spacer(Modifier.height(12.dp))
    WakeBehaviorOptions(wake, onWake, soundRows, scheduleRows)
    if (adding) AlertDialog(onDismissRequest = { adding = false }, title = { Text("Add mission") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MissionType.entries.forEach { type ->
                    NavigateRow(type.title, null) {
                        adding = false
                        edit = AlarmMission(maxOf(missions.maxOfOrNull { it.id } ?: 0, removed?.second?.id ?: 0) + 1, type)
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } })
    edit?.let { mission ->
        MissionEditor(mission, onClose = { edit = null }) { updated ->
            onMissions(if (missions.any { it.id == updated.id }) missions.map { if (it.id == updated.id) updated else it }
                else missions + updated)
            edit = null
        }
    }
    preview?.let { mission ->
        val seed = remember(mission.id) { System.nanoTime() }
        var passed by remember(mission.id) { mutableStateOf(false) }
        AlarmFullscreenDialog(onClose = { preview = null }) {
            MissionScreen("Practice", mission.type.title, progress = null, onBack = { preview = null }) {
                if (passed) {
                    Text("Mission complete", style = MaterialTheme.typography.titleLarge)
                    FilledTonalButton(onClick = { preview = null }) { Text("Done") }
                } else MissionChallenge(mission, seed) { passed = true }
            }
        }
    }
}

@Composable
private fun MissionEditor(initial: AlarmMission, onClose: () -> Unit, save: (AlarmMission) -> Unit) {
    var mission by remember(initial) { mutableStateOf(initial) }
    var scan by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf(initial.target?.label.orEmpty()) }
    val usesScanner = initial.type == MissionType.QR || initial.type == MissionType.NFC
    AlertDialog(onDismissRequest = onClose, title = { Text(initial.type.title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (initial.type == MissionType.MATH || initial.type == MissionType.MEMORY) {
                ChoiceRow("Challenge difficulty", mission.difficulty, MissionDifficulty.entries,
                    { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { mission = mission.copy(difficulty = it) }
                val math = initial.type == MissionType.MATH
                NumberChoice(if (math) "Problems" else "Rounds", mission.roundsOrCount, (1..if (math) 20 else 10).toList(), Int::toString) {
                    mission = mission.copy(roundsOrCount = it)
                }
                if (math) Text("Example · " + mathProblem(mission.difficulty, kotlin.random.Random(12)).expression)
            }
            if (initial.type == MissionType.PUZZLE) {
                ChoiceRow("Puzzle type", mission.puzzleMode, listOf("Sliding tiles", "Numbers in order", "Trace pattern"), { it }) {
                    mission = mission.copy(configJson = JSONObject().put("puzzle", it).toString())
                }
            }
            if (usesScanner) {
                OutlinedTextField(label, { label = it.take(60) }, label = { Text("Item name") }, singleLine = true)
                if (scan) ScanMission(initial.type) {
                    val kind = if (initial.type == MissionType.NFC) ScanKind.NFC else ScanKind.BARCODE
                    mission = mission.copy(target = ScanTarget(kind, it, ""))
                    scan = false
                } else {
                    if (mission.target != null) Text("Item registered")
                    TextButton(onClick = { scan = true }) { Text("Register item") }
                }
            }
        }
    }, confirmButton = {
        TextButton(enabled = !usesScanner || mission.target != null && label.isNotBlank(), onClick = {
            save(mission.copy(target = mission.target?.copy(label = label.trim())))
        }) { Text("Save") }
    }, dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}

@Composable
private fun WakeBehaviorOptions(
    wake: WakeOptions,
    save: (WakeOptions) -> Unit,
    soundRows: (@Composable () -> Unit)?,
    scheduleRows: (@Composable () -> Unit)?,
) {
    val context = LocalContext.current
    val audio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val retained = uris.filter { uri ->
                runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
            }
            if (retained.isNotEmpty()) save(wake.copy(musicUris = retained.map(Uri::toString), soundMode = AlarmSoundMode.LOCAL_MUSIC))
        }
    }
    val scope = rememberCoroutineScope()
    val latestWake by rememberUpdatedState(wake)
    var folderMessage by remember { mutableStateOf<String?>(null) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            val tracks = withContext(Dispatchers.IO) { readMusicFolder(context, uri) }
            folderMessage = if (tracks.isEmpty()) "No readable audio files in that folder." else null
            if (tracks.isNotEmpty()) save(latestWake.copy(soundMode = AlarmSoundMode.LOCAL_MUSIC, musicUris = tracks))
        }
    }
    val soundSummary = when (wake.soundMode) {
        AlarmSoundMode.ONE -> "One sound"
        AlarmSoundMode.SURPRISE -> "Surprise me"
        AlarmSoundMode.LOCAL_MUSIC -> "Random from my music"
    }
    AlarmEditorSection("Sound and vibration", "$soundSummary · ${wake.volumePercent}%", Icons.Outlined.VolumeUp) {
        soundRows?.invoke()
        ChoiceRow("Sound mode", wake.soundMode, AlarmSoundMode.entries, {
            when (it) {
                AlarmSoundMode.ONE -> "One sound"
                AlarmSoundMode.SURPRISE -> "Surprise me"
                AlarmSoundMode.LOCAL_MUSIC -> "Random from my music"
            }
        }) {
            save(wake.copy(soundMode = it))
        }
        if (wake.soundMode == AlarmSoundMode.LOCAL_MUSIC) {
            NavigateRow("Choose local tracks", "${wake.musicUris.size} selected") { audio.launch(arrayOf("audio/*")) }
            NavigateRow("Choose local folder", null) { folder.launch(null) }
        }
        folderMessage?.let { Text(it, Modifier.padding(horizontal = 16.dp)) }
        NumberChoice("Alarm volume", wake.volumePercent, (10..100 step 5).toList(), { "$it%" }) { save(wake.copy(volumePercent = it)) }
        NumberChoice("Volume fade-in", wake.fadeInSeconds, listOf(0, 5, 10, 15, 30, 45, 60, 90, 120), { if (it == 0) "Off" else "$it sec" }) { save(wake.copy(fadeInSeconds = it)) }
        ChoiceRow("Vibration pattern", wake.vibrationPattern, VibrationPattern.entries, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) {
            save(wake.copy(vibrationPattern = it))
        }
        if (context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_FLASH)) {
            AlarmOptionSwitchRow("Flashlight strobe", Icons.Outlined.FlashlightOn, wake.flashStrobeEnabled) { save(wake.copy(flashStrobeEnabled = it)) }
        }
        AlarmOptionSwitchRow("Voice and screen light", Icons.Outlined.RecordVoiceOver, wake.voiceAnnounceEnabled) { save(wake.copy(voiceAnnounceEnabled = it)) }
    }
    Spacer(Modifier.height(12.dp))
    AlarmEditorSection("Snooze", snoozeLimitLabel(wake.maxSnoozes), Icons.Outlined.Snooze) {
        // -1 is the picker value for an unlimited count; storage uses null.
        NumberChoice("Snooze limit", wake.maxSnoozes ?: -1, (0..10).toList() + -1,
            { snoozeLimitLabel(it.takeUnless { count -> count == -1 }) }) { selected ->
            save(wake.copy(maxSnoozes = selected.takeUnless { it == -1 }))
        }
        if (wake.maxSnoozes != 0) {
            NumberChoice("Snooze duration", wake.snoozeDurationMin ?: 0, (0..30).toList(),
                { if (it == 0) "App default" else "$it min" }) {
                save(wake.copy(snoozeDurationMin = it.takeIf { minutes -> minutes > 0 }))
            }
            AlarmOptionSwitchRow("Shake to snooze", Icons.Outlined.Vibration, wake.shakeToSnooze) {
                save(wake.copy(shakeToSnooze = it))
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    val scheduleSummary = wake.timeZoneId ?: "Device time zone"
    AlarmEditorSection("Scheduling and protection", scheduleSummary, Icons.Outlined.Tune) {
        scheduleRows?.invoke()
    }
}

private fun snoozeLimitLabel(limit: Int?): String = when (limit) {
    null -> "Unlimited"
    0 -> "No snooze"
    else -> limit.toString()
}

private fun preRingLockLabel(minutes: Int): String = when {
    minutes <= 0 -> "Off"
    minutes == 60 -> "1 hour"
    minutes % 60 == 0 -> "${minutes / 60} hours"
    else -> "$minutes min"
}

@Composable
private fun DifficultyOptions(wake: WakeOptions, save: (WakeOptions) -> Unit) {
    val checks = wake.stillAwakeChecksCount
    val protection = listOfNotNull(
        preRingLockLabel(wake.lockBeforeRingMin).takeIf { wake.lockBeforeRingMin > 0 }?.let { "$it pre-ring lock" },
        when (checks) {
            0 -> null
            1 -> "1 awake check"
            else -> "$checks awake checks"
        },
    ).joinToString(" · ").ifEmpty { "Ring duration and protection" }
    AlarmEditorSection("Difficulty", protection, Icons.Outlined.Tune) {
        NumberChoice("Silence after", wake.silenceAfterMin ?: -1, (-1..60).toList(),
            { if (it < 0) "App default" else if (it == 0) "Never" else "$it min" }) {
            save(wake.copy(silenceAfterMin = it.takeIf { minutes -> minutes >= 0 }))
        }
        NumberChoice("Pre-ring lock", wake.lockBeforeRingMin, listOf(0, 15, 30, 60), ::preRingLockLabel,
            custom = CustomNumber("Hours", 1..Int.MAX_VALUE / 60, unitsPerEntry = 60)) {
            save(wake.copy(lockBeforeRingMin = it))
        }
        NumberChoice("Reboot recovery", wake.rebootSurvivalMax, (0..5).toList(), { if (it == 0) "Off" else it.toString() }) {
            save(wake.copy(rebootSurvivalMax = it))
        }
        NumberChoice("Still-awake checks", checks, (0..3).toList(), { if (it == 0) "Off" else it.toString() },
            custom = CustomNumber("Number of checks (4 or more)", 4..Int.MAX_VALUE)) {
            save(wake.copy(stillAwakeChecksCount = it))
        }
        if (checks > 0) NumberChoice("Still-awake delay", wake.stillAwakeDelayMin, (3..15).toList(), { "$it min" }) {
            save(wake.copy(stillAwakeDelayMin = it))
        }
    }
}

private data class CustomNumber(val label: String, val range: IntRange, val unitsPerEntry: Int = 1)

@Composable
private fun NumberChoice(
    title: String,
    current: Int,
    values: List<Int>,
    label: (Int) -> String,
    custom: CustomNumber? = null,
    select: (Int) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    var enteringCustom by rememberSaveable { mutableStateOf(false) }
    NavigateRow(title, label(current)) { open = true }
    if (!open) return
    fun close() {
        enteringCustom = false
        open = false
    }
    var input by rememberSaveable { mutableStateOf("") }
    val activeCustom = custom?.takeIf { enteringCustom }
    val customValue = input.toIntOrNull()?.takeIf { value -> activeCustom?.range?.contains(value) == true }
    AlertDialog(
        onDismissRequest = ::close,
        title = { Text(title) },
        text = {
            if (activeCustom != null) {
                Column(
                    Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(activeCustom.label, style = MaterialTheme.typography.labelLarge)
                    Text(input.ifEmpty { "—" }, style = MaterialTheme.typography.headlineMedium)
                    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "Clear", "0", "Delete digit")
                    keys.chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { key ->
                                FilledTonalButton(
                                    onClick = {
                                        input = when (key) {
                                            "Clear" -> ""
                                            "Delete digit" -> input.dropLast(1)
                                            else -> if (input.length < 10) input + key else input
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(4.dp),
                                ) {
                                    if (key == "Delete digit") Icon(Icons.Outlined.Backspace, key)
                                    else Text(key)
                                }
                            }
                        }
                    }
                }
            } else Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                values.forEach { value -> NavigateRow(label(value), null) { select(value); close() } }
                if (custom != null) NavigateRow("Custom", null) {
                    input = (current / custom.unitsPerEntry).coerceIn(custom.range).toString()
                    enteringCustom = true
                }
            }
        },
        confirmButton = {
            if (activeCustom == null) TextButton(onClick = ::close) { Text("Cancel") }
            else TextButton(enabled = customValue != null, onClick = {
                customValue?.let { select(it * activeCustom.unitsPerEntry) }
                close()
            }) { Text("Set") }
        },
        dismissButton = {
            if (activeCustom != null) TextButton(onClick = { enteringCustom = false }) { Text("Back") }
        },
    )
}

private fun readMusicFolder(context: android.content.Context, folder: Uri): List<String> = runCatching {
    val resolver = context.contentResolver
    resolver.takePersistableUriPermission(folder, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE)
    val directories = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(folder)))
    val visited = mutableSetOf<String>()
    val audio = mutableListOf<String>()
    while (directories.isNotEmpty() && visited.size < 256 && audio.size < 1500) {
        val id = directories.removeFirst()
        if (!visited.add(id)) continue
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, id)
        resolver.query(children, columns, null, null, null)?.use { rows ->
            while (rows.moveToNext() && audio.size < 1500) {
                val child = rows.getString(0)
                val mime = rows.getString(1).orEmpty()
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) directories.addLast(child)
                if (mime.startsWith("audio/")) audio += DocumentsContract.buildDocumentUriUsingTree(folder, child).toString()
            }
        }
    }
    audio.distinct()
}.getOrDefault(emptyList())
