package com.wakr.app.data

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.wakr.app.core.*
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WakeStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private suspend fun open(
        migrations: List<DataMigration<Preferences>>,
        block: suspend (androidx.datastore.core.DataStore<Preferences>) -> Unit,
    ) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val prefs = PreferenceDataStoreFactory.create(migrations = migrations, scope = scope) {
            File(folder.root, "clock.preferences_pb")
        }
        try { block(prefs) } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    private val legacy = """[{"id":81,"time":23400,"days":[1,5],"label":"Early shift","enabled":false,"vibrate":false,"sound":"content://tone/81","snoozed":1234,"skippedOccurrence":321,"skippedDate":"2026-09-17"}]"""
    private val original = Alarm(81, LocalTime.of(6, 30), "Early shift",
        setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), false, false, "content://tone/81",
        1234, 321, LocalDate.parse("2026-09-17"), wake = WakeOptions(maxSnoozes = null))

    @Test fun citiesSharingOneTimeZoneKeepSeparateIdentitiesAcrossRestarts() = runBlocking {
        val zone = java.time.ZoneId.of("America/Chicago")
        val first = WorldCity(zone, "Springfield", "Illinois, United States", "geonames:4250542", 39.8, -89.64)
        val second = WorldCity(zone, "Springfield", "Missouri, United States", "geonames:4409896", 37.22, -93.3)
        open(emptyList()) { prefs ->
            val store = ClockStore(prefs)
            store.putCities(emptyList())
            store.updateCities { it + listOf(first, second, second) }
            assertEquals(listOf(first, second), store.cities.first())
        }
        open(emptyList()) { prefs ->
            val store = ClockStore(prefs)
            assertEquals(listOf(first, second), store.cities.first())
            store.updateCities { cities -> cities.filterNot { it.id == first.id } }
            assertEquals(listOf(second), store.cities.first())
        }
    }

    @Test fun savedAutomaticTimerNamesFollowTheirPositionAndCustomNamesRemainIntact() = runBlocking {
        val duration = java.time.Duration.ofSeconds(90)
        val first = ClockTimer(101, "Timer 1", duration, TimerState.RUNNING, 100_000, usesDefaultLabel = true)
        val shifted = ClockTimer(104, "Timer 4", duration, TimerState.PAUSED, pausedRemaining = duration.minusSeconds(8),
            usesDefaultLabel = true)
        val custom = ClockTimer(105, "Timer 4", duration, TimerState.FINISHED)
        val formerLast = ClockTimer(106, "Timer 2", duration, TimerState.RUNNING, 110_000, usesDefaultLabel = true)
        open(emptyList()) { prefs ->
            prefs.edit { values ->
                values[stringPreferencesKey("timers")] = org.json.JSONArray().apply {
                    put(first.toTimerJson().apply { remove("usesDefaultLabel") })
                    put(shifted.toTimerJson().apply { remove("usesDefaultLabel") })
                    put(custom.toTimerJson())
                    put(formerLast.toTimerJson().apply { remove("usesDefaultLabel") })
                }.toString()
            }
            val store = ClockStore(prefs)
            assertEquals(listOf(first, shifted.copy(label = "Timer 2"), custom, formerLast.copy(label = "Timer 4")), store.timersNow())
            val remaining = store.updateTimers { it.filterNot { timer -> timer.id == first.id } }
            assertEquals(listOf(shifted.copy(label = "Timer 1"), custom, formerLast.copy(label = "Timer 3")), remaining)
        }
        open(emptyList()) { prefs ->
            assertEquals(listOf(shifted.copy(label = "Timer 1"), custom, formerLast.copy(label = "Timer 3")), ClockStore(prefs).timersNow())
        }
    }

    @Test fun legacyTimerRetainsItsDeadlineWhenTheTimerListIsWrittenAndReopened() = runBlocking {
        val original = ClockTimer(157, "Tea", java.time.Duration.ofSeconds(90), TimerState.RUNNING, 110_000)
        val second = ClockTimer(158, "Oven", java.time.Duration.ofSeconds(30), TimerState.PAUSED)
        open(emptyList()) { prefs ->
            prefs.edit {
                it[stringPreferencesKey("timer")] =
                    """{"id":157,"label":"Tea","total":90000,"state":"RUNNING","deadline":110000,"paused":90000}"""
            }
            val store = ClockStore(prefs)
            assertEquals(listOf(original), store.timersNow())
            store.updateTimers { it + second }
        }
        open(emptyList()) { prefs ->
            val store = ClockStore(prefs)
            assertEquals(listOf(original, second), store.timersNow())
            assertNull(prefs.data.first()[stringPreferencesKey("timer")])
            store.updateTimers { timers -> timers.filterNot { it.id == second.id } }
            assertEquals(listOf(original), store.timersNow())
        }
    }

    @Test fun vanillaFileUpgradesOnceAndRetainsOtherSettings() = runBlocking {
        open(emptyList()) { prefs ->
            prefs.edit {
                it[stringPreferencesKey("alarms")] = legacy
                it[stringPreferencesKey("cities")] = """[{"zone":"Asia/Kolkata","city":"Kolkata","region":"India"}]"""
                it[stringPreferencesKey("timerPresetSeconds")] = "9,19,29,39"
                it[longPreferencesKey("nextId")] = 132
                it[intPreferencesKey("snooze")] = 7
            }
        }
        repeat(2) { attempt ->
            open(listOf(WakeMigration)) { prefs ->
                val store = ClockStore(prefs)
                assertFalse(WakeMigration.shouldMigrate(prefs.data.first()))
                assertEquals(original, store.alarmsNow().single())
                assertEquals("Asia/Kolkata", store.cities.first().single().zone.id)
                assertEquals(listOf(9, 19, 29, 39, 900, 1800), store.settingsNow().timers.presets)
                assertEquals(7, store.settingsNow().alarms.snoozeMinutes)
                assertEquals(132L + attempt, store.nextId())
                assertTrue(store.wakeHistory.first().isEmpty())
            }
        }
    }

    @Test fun emptyVanillaAlarmListStaysEmptyAndNewInstallsKeepSeeds() = runBlocking {
        open(listOf(WakeMigration)) { prefs ->
            val store = ClockStore(prefs)
            assertEquals(listOf(1L, 2L, 3L), store.alarmsNow().map { it.id })
            store.putAlarms(emptyList())
        }
        open(listOf(WakeMigration)) { assertTrue(ClockStore(it).alarmsNow().isEmpty()) }
    }

    @Test fun snoozeLimitsPreserveLegacyBehaviorAndRoundTrip() {
        assertEquals(0, org.json.JSONObject("""{"snoozeEnabled":false,"maxSnoozes":3}""").toWakeOptions().maxSnoozes)
        assertEquals(3, org.json.JSONObject("""{"snoozeEnabled":true,"maxSnoozes":3}""").toWakeOptions().maxSnoozes)
        assertNull(org.json.JSONObject("""{"snoozeEnabled":true}""").toWakeOptions().maxSnoozes)
        assertEquals(0, WakeOptions().maxSnoozes)
        for (limit in listOf(0, 3, null)) {
            val options = WakeOptions(maxSnoozes = limit, stillAwakeChecksCount = 6, lockBeforeRingMin = 180)
            assertEquals(options, options.toJson().toWakeOptions())
        }
    }

    @Test fun muteAndEmergencySettingsPreserveDefaultsAndAllDurationChoices() {
        val legacy = org.json.JSONObject().toWakeOptions()
        assertEquals(30, legacy.muteDurationSec)
        assertFalse(legacy.emergencyStopEnabled)
        for (duration in MUTE_DURATIONS_SECONDS) {
            val options = WakeOptions(muteDurationSec = duration, emergencyStopEnabled = true)
            assertEquals(options, options.toJson().toWakeOptions())
        }
    }

    @Test fun muteGapAndSeparateUnlockMissionsRoundTrip() {
        val legacy = org.json.JSONObject().toWakeOptions()
        assertEquals(1, legacy.maxMutes)
        assertEquals(30, legacy.muteGapSeconds)
        assertTrue(legacy.unlockMissions.isEmpty())
        for (limit in listOf(0, 1, 5, null)) {
            val unlock = AlarmMission(5, MissionType.MEMORY, configJson = """{"boardSize":5}""")
            val options = WakeOptions(maxMutes = limit, muteGapSeconds = 180, unlockMissions = listOf(unlock))
            assertEquals(options, options.toJson().toWakeOptions())
        }
    }

    @Test fun wakeMetricsSurviveHistoryAndAlarmRoundTrips() {
        val alarm = original.copy(missionMuteCount = 2, snoozeCount = 1, firstRingAt = 2_000)
        assertEquals(alarm, parseAlarms(encodeAlarms(listOf(alarm))).single())
        val entry = WakeUpLog(21, alarm.id, alarm.label, 2_000, 2_000, 3_000, 1, false, false,
            WakeOutcome.STILL_AWAKE_FAILED, missionMuteCount = 2, completedWakeChecks = 4, passedWakeChecks = 3)
        assertEquals(entry, entry.toJson().toWakeUpLog())
        val legacy = entry.toJson().apply {
            remove("missionMuteCount")
            remove("completedWakeChecks")
            remove("passedWakeChecks")
        }.toWakeUpLog()
        assertNull(legacy.missionMuteCount)
        assertNull(legacy.completedWakeChecks)
        assertNull(legacy.passedWakeChecks)
    }

    @Test fun retiredNfcMissionsKeepTheirPlaceInBothAlarmChains() {
        val memory = AlarmMission(15, MissionType.MEMORY)
        val oldMission = org.json.JSONObject("""{"id":16,"type":"NFC","target":{"kind":"NFC","value":"1234","label":"Desk"}}""")
        val math = AlarmMission(16, MissionType.MATH, MissionDifficulty.HARD, roundsOrCount = 3)
        val encoded = org.json.JSONArray(encodeAlarms(listOf(original)))
        val alarm = encoded.getJSONObject(0)
        alarm.put("missions", org.json.JSONArray().put(memory.toJson()).put(oldMission))
        alarm.put("wake", WakeOptions().toJson().put("unlockMissions", org.json.JSONArray().put(oldMission)))
        val decoded = parseAlarms(encoded.toString()).single()
        assertEquals(listOf(memory, math), decoded.missions)
        assertEquals(listOf(math), decoded.wake.unlockMissions)
        assertEquals(decoded, parseAlarms(encodeAlarms(listOf(decoded))).single())
    }

    @Test fun legacyRebootRecoveryCountsMigrateToTheSwitch() {
        for (count in 0..5) {
            val json = org.json.JSONArray(encodeAlarms(listOf(original)))
            val wake = json.getJSONObject(0).getJSONObject("wake")
            wake.remove("rebootRecovery")
            wake.put("rebootSurvivalMax", count)
            val decoded = parseAlarms(json.toString()).single()
            assertEquals(count > 0, decoded.wake.rebootRecovery)
            assertEquals(decoded, parseAlarms(encodeAlarms(listOf(decoded))).single())
        }
    }

    @Test fun missionOrderPolicyRegistrationAndHistorySurviveRestartAndDeletion() = runBlocking {
        val code = ScanTarget(ScanKind.BARCODE, "00012345", "Kitchen")
        val types = MissionType.entries.reversed()
        val missions = types.mapIndexed { i, type ->
            AlarmMission(50L + i, type, MissionDifficulty.HARD, 3, """{"mode":"test"}""",
                code.takeIf { type == MissionType.QR })
        }
        val options = WakeOptions(
            scheduledDateEpochDay = 21_000, pausedUntilEpochDay = 20_009, timeZoneId = "Asia/Kolkata",
            soundMode = AlarmSoundMode.LOCAL_MUSIC,
            musicUris = listOf("content://tracks/1", "content://tracks/2"), volumePercent = 80,
            fadeInSeconds = 90, vibrationPattern = VibrationPattern.HEARTBEAT, flashEnabled = true,
            voiceAnnounceEnabled = true, snoozeDurationMin = 4, maxSnoozes = 2,
            shakeToSnooze = true, silenceAfterMin = 0, repeatMissedCount = -1, reRingIntervalMin = 3,
            deleteAfterDismiss = true, lockBeforeRingMin = 15, rebootRecovery = true,
            stillAwakeChecksCount = 2, stillAwakeDelayMin = 8,
        )
        val alarm = original.copy(wake = options, missions = missions, createdAt = 1500,
            ringingAt = 3010, ringSoundUri = "content://tracks/2")
        val log = WakeUpLog(99, alarm.id, alarm.label, 3000, 3010, 3300, 1, null, false, WakeOutcome.DISMISSED)
        open(listOf(WakeMigration)) { prefs ->
            val store = ClockStore(prefs)
            store.putAlarms(listOf(alarm))
            store.registerTarget(code.copy(label = "Old name"))
            store.registerTarget(code)
            coroutineScope {
                for (i in 0L..4L) launch(Dispatchers.Default) { store.putWakeLog(log.copy(id = log.id + i)) }
            }
            store.putWakeLog(log.copy(stillAwakePassed = false, outcome = WakeOutcome.STILL_AWAKE_FAILED))
        }
        open(listOf(WakeMigration)) { prefs ->
            val store = ClockStore(prefs)
            assertEquals(alarm, store.alarmsNow().single())
            assertEquals(listOf(code), store.scanTargets.first())
            store.updateAlarms { it.map { a -> a.copy(missions = a.missions.reversed()) } }
            assertEquals(missions.reversed(), store.alarmsNow().single().missions)
            store.updateAlarms { list -> list.filterNot { it.id == alarm.id } }
            assertTrue(store.alarmsNow().isEmpty())
            val rows = store.wakeHistory.first()
            assertEquals(5, rows.size)
            assertEquals(log.copy(stillAwakePassed = false, outcome = WakeOutcome.STILL_AWAKE_FAILED),
                rows.single { it.id == log.id })
            assertEquals(log.copy(id = log.id + 1), rows.single { it.id == log.id + 1 })
            store.clearWakeHistory()
            assertTrue(store.wakeHistory.first().isEmpty())
        }
    }
}
