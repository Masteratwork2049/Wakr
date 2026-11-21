package com.wakr.app.ui.sheets

import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wakr.app.core.*
import com.wakr.app.data.jsonArray
import com.wakr.app.data.records
import com.wakr.app.data.toJson
import com.wakr.app.data.toMission
import com.wakr.app.ui.elasticClickable
import com.wakr.app.ui.missions.*
import org.json.JSONArray
import org.json.JSONObject

internal val MissionDraftSaver = Saver<List<AlarmMission>, String>(
    save = { jsonArray(it, AlarmMission::toJson).toString() },
    restore = { JSONArray(it).records(JSONObject::toMission) },
)

internal val AlarmDifficulty.label get() = when (this) {
    AlarmDifficulty.EASY -> "Easy"
    AlarmDifficulty.NORMAL -> "Normal"
    AlarmDifficulty.HARD -> "Hard"
    AlarmDifficulty.NO_MERCY -> "No Mercy"
    AlarmDifficulty.CUSTOM -> "Custom"
}

@Composable
internal fun MissionOptions(
    wake: WakeOptions, missions: List<AlarmMission>,
    onWake: (WakeOptions) -> Unit, onMissions: (List<AlarmMission>) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var edit by remember { mutableStateOf<AlarmMission?>(null) }
    var preview by remember { mutableStateOf<AlarmMission?>(null) }
    var removed by remember { mutableStateOf<Pair<Int, AlarmMission>?>(null) }
    Row(Modifier.fillMaxWidth().padding(8.dp).clip(CircleShape)
        .background(MaterialTheme.colorScheme.primaryContainer)) {
        AlarmOptionRow("Wake-up missions", Icons.Outlined.Psychology,
            if (missions.isEmpty()) "None" else missions.size.toString(),
            expanded = expanded, onClick = { expanded = !expanded })
    }
    AnimatedVisibility(expanded) {
        Column {
            ChoiceRow("Difficulty", wake.difficulty, AlarmDifficulty.entries, { it.label }) { onWake(wake.withDifficulty(it)) }
            if (wake.difficulty == AlarmDifficulty.EASY && missions.isNotEmpty()) {
                Text("Choose Normal, Hard, No Mercy, or Custom to run these missions.",
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            }
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
            TextButton(onClick = { adding = true }) { Text("+ Add mission") }
        }
    }
    WakeBehaviorOptions(wake, onWake)
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
            if (wake.difficulty == AlarmDifficulty.EASY) onWake(wake.withDifficulty(AlarmDifficulty.NORMAL))
            edit = null
        }
    }
    preview?.let { mission ->
        val seed = remember(mission.id) { System.nanoTime() }
        var passed by remember(mission.id) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { preview = null }, title = { Text(mission.type.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (passed) Text("Mission complete", style = MaterialTheme.typography.titleLarge)
                    else MissionChallenge(mission, seed) { passed = true }
                }
            }, confirmButton = { TextButton(onClick = { preview = null }) { Text("Close") } })
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
private fun WakeBehaviorOptions(wake: WakeOptions, save: (WakeOptions) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    fun custom(options: WakeOptions) = save(options.copy(difficulty = AlarmDifficulty.CUSTOM))
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
    AlarmOptionRow("Wake-up options", Icons.Outlined.Tune, expanded = expanded, onClick = { expanded = !expanded })
    AnimatedVisibility(expanded) {
        Column {
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
            AlarmOptionSwitchRow("Snooze", Icons.Outlined.Snooze, wake.snoozeEnabled) { custom(wake.copy(snoozeEnabled = it)) }
            if (wake.snoozeEnabled) {
                NumberChoice("Snooze duration", wake.snoozeDurationMin ?: 0, (0..30).toList(), { if (it == 0) "App default" else "$it min" }) { custom(wake.copy(snoozeDurationMin = it.takeIf { n -> n > 0 })) }
                NumberChoice("Snooze limit", wake.maxSnoozes ?: 0, (0..10).toList(), { if (it == 0) "Unlimited" else it.toString() }) { custom(wake.copy(maxSnoozes = it.takeIf { n -> n > 0 })) }
                AlarmOptionSwitchRow("Shake to snooze", Icons.Outlined.Vibration, wake.shakeToSnooze) { custom(wake.copy(shakeToSnooze = it)) }
            }
            NumberChoice("Pre-ring lock", wake.lockBeforeRingMin, listOf(0, 15, 30, 60), { if (it == 0) "Off" else "$it min" }) { custom(wake.copy(lockBeforeRingMin = it)) }
            AlarmOptionSwitchRow("Re-launch if left", Icons.Outlined.NotificationsActive, wake.relaunchOnLeave) { custom(wake.copy(relaunchOnLeave = it)) }
            NumberChoice("Reboot recovery", wake.rebootSurvivalMax, (0..5).toList(), { if (it == 0) "Off" else it.toString() }) { custom(wake.copy(rebootSurvivalMax = it)) }
            NumberChoice("Still-awake checks", wake.stillAwakeChecksCount, (0..2).toList(), { if (it == 0) "Off" else it.toString() }) { custom(wake.copy(stillAwakeChecksCount = it)) }
            if (wake.stillAwakeChecksCount > 0) NumberChoice("Still-awake delay", wake.stillAwakeDelayMin, (3..15).toList(), { "$it min" }) { custom(wake.copy(stillAwakeDelayMin = it)) }
        }
    }
}

@Composable
private fun NumberChoice(title: String, current: Int, values: List<Int>, label: (Int) -> String, select: (Int) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    NavigateRow(title, label(current)) { open = true }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            values.forEach { value -> NavigateRow(label(value), null) { select(value); open = false } }
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text("Cancel") } })
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
