package com.wakr.app.core

enum class AlarmSoundMode { ONE, SURPRISE, LOCAL_MUSIC }
enum class VibrationPattern { DEFAULT, SOS, PULSE, HEARTBEAT }
enum class MissionType { QR, NFC, FOCUS_TAP, MEMORY, PUZZLE, TYPING, MATH }
enum class MissionDifficulty { EASY, MEDIUM, HARD }
enum class ScanKind { BARCODE, NFC }

/** The barcode payload or NFC UID is matched exactly. The label is for display. */
data class ScanTarget(val kind: ScanKind, val value: String, val label: String)

/** Membership in an alarm and position in its list define ownership and execution order. */
data class AlarmMission(
    val id: Long,
    val type: MissionType,
    val difficulty: MissionDifficulty = MissionDifficulty.EASY,
    val roundsOrCount: Int = 1,
    val configJson: String = "{}",
    val target: ScanTarget? = null,
)

/** Null duration overrides inherit the live app settings, including on upgraded alarms. */
data class WakeOptions(
    val relativeTriggerMillis: Long? = null,
    val scheduledDateEpochDay: Long? = null,
    val pausedUntilEpochDay: Long? = null,
    val timeZoneId: String? = null,
    val soundMode: AlarmSoundMode = AlarmSoundMode.ONE,
    val musicUris: List<String> = emptyList(),
    val volumePercent: Int = 100,
    val fadeInSeconds: Int = 0,
    val vibrationPattern: VibrationPattern = VibrationPattern.DEFAULT,
    val flashEnabled: Boolean = false,
    val flashStrobeEnabled: Boolean = false,
    val voiceAnnounceEnabled: Boolean = false,
    val snoozeDurationMin: Int? = null,
    val maxSnoozes: Int? = 0,
    val shakeToSnooze: Boolean = false,
    val silenceAfterMin: Int? = null,
    val repeatMissedCount: Int = 0,
    val reRingIntervalMin: Int = 5,
    val deleteAfterDismiss: Boolean = false,
    val lockBeforeRingMin: Int = 0,
    val rebootSurvivalMax: Int = 0,
    val stillAwakeChecksCount: Int = 0,
    val stillAwakeDelayMin: Int = 5,
)

enum class WakeOutcome(val label: String) {
    DISMISSED("Dismissed"), EMERGENCY_STOP("Emergency stop"), MISSED("Missed"), STILL_AWAKE_FAILED("Still-awake check failed"),
}

/** Label and scheduled time are snapshots so history survives alarm edits and deletion. */
data class WakeUpLog(
    val id: Long,
    val alarmId: Long,
    val alarmLabel: String,
    val scheduledTriggerAt: Long,
    val firstRingAt: Long,
    val dismissedAt: Long?,
    val snoozeCount: Int,
    val stillAwakePassed: Boolean?,
    val wasCleanWakeUp: Boolean,
    val outcome: WakeOutcome,
    val missionMuteCount: Int? = null,
    val completedWakeChecks: Int? = null,
    val passedWakeChecks: Int? = null,
)
