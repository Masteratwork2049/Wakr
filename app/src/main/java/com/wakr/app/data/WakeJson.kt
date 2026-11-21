package com.wakr.app.data

import com.wakr.app.core.MUTE_DURATIONS_SECONDS
import com.wakr.app.core.AlarmMission
import com.wakr.app.core.AlarmSoundMode
import com.wakr.app.core.MissionDifficulty
import com.wakr.app.core.MissionType
import com.wakr.app.core.ScanKind
import com.wakr.app.core.ScanTarget
import com.wakr.app.core.VibrationPattern
import com.wakr.app.core.WakeOptions
import com.wakr.app.core.WakeOutcome
import com.wakr.app.core.WakeUpLog
import org.json.JSONArray
import org.json.JSONObject

internal fun <T> jsonArray(values: List<T>, encode: (T) -> JSONObject) =
    JSONArray().apply { values.forEach { put(encode(it)) } }

internal fun <T> JSONArray.records(decode: (JSONObject) -> T): List<T> =
    (0 until length()).mapNotNull { index ->
        optJSONObject(index)?.let { runCatching { decode(it) }.getOrNull() }
    }

private inline fun <reified T : Enum<T>> JSONObject.enum(key: String, default: T): T =
    enumValues<T>().firstOrNull { it.name == optString(key) } ?: default

internal fun JSONObject.longOrNull(key: String) = opt(key)?.toString()?.toLongOrNull()
private fun JSONObject.intOrNull(key: String) = opt(key)?.toString()?.toIntOrNull()

internal fun WakeOptions.toJson() = JSONObject()
    .put("relativeTriggerMillis", relativeTriggerMillis)
    .put("scheduledDateEpochDay", scheduledDateEpochDay)
    .put("pausedUntilEpochDay", pausedUntilEpochDay)
    .put("timeZoneId", timeZoneId)
    .put("soundMode", soundMode.name)
    .put("musicUris", JSONArray(musicUris))
    .put("volumePercent", volumePercent)
    .put("fadeInSeconds", fadeInSeconds)
    .put("vibrationPattern", vibrationPattern.name)
    .put("flashEnabled", flashEnabled)
    .put("flashStrobeEnabled", flashStrobeEnabled)
    .put("voiceAnnounceEnabled", voiceAnnounceEnabled)
    .put("snoozeDurationMin", snoozeDurationMin)
    .put("maxSnoozes", maxSnoozes ?: JSONObject.NULL)
    .put("shakeToSnooze", shakeToSnooze)
    .put("silenceAfterMin", silenceAfterMin)
    .put("repeatMissedCount", repeatMissedCount)
    .put("reRingIntervalMin", reRingIntervalMin)
    .put("deleteAfterDismiss", deleteAfterDismiss)
    .put("lockBeforeRingMin", lockBeforeRingMin)
    .put("rebootSurvivalMax", rebootSurvivalMax)
    .put("stillAwakeChecksCount", stillAwakeChecksCount)
    .put("stillAwakeDelayMin", stillAwakeDelayMin)
    .put("muteDurationSec", muteDurationSec)
    .put("emergencyStopEnabled", emergencyStopEnabled)
    .put("maxMutes", maxMutes ?: JSONObject.NULL)
    .put("muteGapSeconds", muteGapSeconds)
    .put("unlockMissions", jsonArray(unlockMissions, AlarmMission::toJson))

internal fun JSONObject.toWakeOptions() = WakeOptions(
    relativeTriggerMillis = longOrNull("relativeTriggerMillis"),
    scheduledDateEpochDay = longOrNull("scheduledDateEpochDay"),
    pausedUntilEpochDay = longOrNull("pausedUntilEpochDay"),
    timeZoneId = optString("timeZoneId").takeIf { !isNull("timeZoneId") && it.isNotEmpty() },
    soundMode = enum("soundMode", AlarmSoundMode.ONE),
    musicUris = optJSONArray("musicUris")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(),
    volumePercent = optInt("volumePercent", 100),
    fadeInSeconds = optInt("fadeInSeconds", 0),
    vibrationPattern = enum("vibrationPattern", VibrationPattern.DEFAULT),
    flashEnabled = optBoolean("flashEnabled", false),
    flashStrobeEnabled = optBoolean("flashStrobeEnabled", false),
    voiceAnnounceEnabled = optBoolean("voiceAnnounceEnabled", false),
    snoozeDurationMin = intOrNull("snoozeDurationMin"),
    maxSnoozes = if (optBoolean("snoozeEnabled", true)) intOrNull("maxSnoozes") else 0,
    shakeToSnooze = optBoolean("shakeToSnooze", false),
    silenceAfterMin = intOrNull("silenceAfterMin"),
    repeatMissedCount = optInt("repeatMissedCount", 0),
    reRingIntervalMin = optInt("reRingIntervalMin", 5),
    deleteAfterDismiss = optBoolean("deleteAfterDismiss", false),
    lockBeforeRingMin = optInt("lockBeforeRingMin", 0),
    rebootSurvivalMax = optInt("rebootSurvivalMax", 0),
    stillAwakeChecksCount = optInt("stillAwakeChecksCount", 0),
    stillAwakeDelayMin = optInt("stillAwakeDelayMin", 5),
    muteDurationSec = optInt("muteDurationSec", 30).takeIf { it in MUTE_DURATIONS_SECONDS } ?: 30,
    emergencyStopEnabled = optBoolean("emergencyStopEnabled", false),
    maxMutes = when {
        !has("maxMutes") -> 1
        isNull("maxMutes") -> null
        else -> optInt("maxMutes", 1).coerceIn(0, 5)
    },
    muteGapSeconds = optInt("muteGapSeconds", 30).takeIf { it == 30 || it == 45 || it >= 60 && it % 60 == 0 } ?: 30,
    unlockMissions = optJSONArray("unlockMissions")?.records(JSONObject::toMission).orEmpty(),
)

internal fun ScanTarget.toJson() = JSONObject()
    .put("kind", kind.name).put("value", value).put("label", label)

internal fun JSONObject.toScanTarget() = ScanTarget(
    kind = ScanKind.valueOf(getString("kind")),
    value = getString("value"),
    label = getString("label"),
)

internal fun AlarmMission.toJson() = JSONObject()
    .put("id", id)
    .put("type", type.name)
    .put("difficulty", difficulty.name)
    .put("roundsOrCount", roundsOrCount)
    .put("config", configJson)
    .put("target", target?.toJson())

internal fun JSONObject.toMission() = AlarmMission(
    id = getLong("id"),
    type = MissionType.valueOf(getString("type")),
    difficulty = enum("difficulty", MissionDifficulty.EASY),
    roundsOrCount = optInt("roundsOrCount", 1),
    configJson = optString("config", "{}"),
    target = optJSONObject("target")?.toScanTarget(),
)

internal fun WakeUpLog.toJson() = JSONObject()
    .put("id", id)
    .put("alarmId", alarmId)
    .put("alarmLabel", alarmLabel)
    .put("scheduledTriggerAt", scheduledTriggerAt)
    .put("firstRingAt", firstRingAt)
    .put("dismissedAt", dismissedAt)
    .put("snoozeCount", snoozeCount)
    .put("stillAwakePassed", stillAwakePassed)
    .put("wasCleanWakeUp", wasCleanWakeUp)
    .put("outcome", outcome.name)
    .put("missionMuteCount", missionMuteCount)
    .put("completedWakeChecks", completedWakeChecks)
    .put("passedWakeChecks", passedWakeChecks)

internal fun JSONObject.toWakeUpLog() = WakeUpLog(
    id = getLong("id"),
    alarmId = getLong("alarmId"),
    alarmLabel = getString("alarmLabel"),
    scheduledTriggerAt = getLong("scheduledTriggerAt"),
    firstRingAt = getLong("firstRingAt"),
    dismissedAt = longOrNull("dismissedAt"),
    snoozeCount = getInt("snoozeCount"),
    stillAwakePassed = if (isNull("stillAwakePassed")) null else getBoolean("stillAwakePassed"),
    wasCleanWakeUp = getBoolean("wasCleanWakeUp"),
    outcome = WakeOutcome.valueOf(getString("outcome")),
    missionMuteCount = intOrNull("missionMuteCount"),
    completedWakeChecks = intOrNull("completedWakeChecks"),
    passedWakeChecks = intOrNull("passedWakeChecks"),
)
