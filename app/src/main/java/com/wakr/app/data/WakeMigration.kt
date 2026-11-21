package com.wakr.app.data

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.wakr.app.core.WakeOptions
import org.json.JSONArray

/** Version 1 adds a policy object to each vanilla alarm, retaining the original JSON fields. */
internal object WakeMigration : DataMigration<Preferences> {
    private val version = intPreferencesKey("wakeSchema")
    private val alarms = stringPreferencesKey("alarms")

    override suspend fun shouldMigrate(currentData: Preferences) = (currentData[version] ?: 0) < 1

    override suspend fun migrate(currentData: Preferences): Preferences {
        val next = currentData.toMutablePreferences()
        currentData[alarms]?.let { raw ->
            val records = runCatching { JSONArray(raw) }.getOrNull()
            if (records != null) {
                for (i in 0 until records.length()) {
                    val alarm = records.optJSONObject(i) ?: continue
                    if (!alarm.has("wake")) alarm.put("wake", WakeOptions(maxSnoozes = null).toJson())
                }
                next[alarms] = records.toString()
            }
        }
        next[version] = 1
        return next
    }

    override suspend fun cleanUp() = Unit
}
