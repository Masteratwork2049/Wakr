package com.wakr.app.ui

import com.wakr.app.data.DefaultStartupPage
import org.junit.Assert.*
import org.junit.Test

class StartupPageTest {
    @Test fun existingStoredNamesAndNavigationKeysRemainCompatible() {
        for ((name, key) in mapOf("ALARMS" to "alarms", "TIMER" to "timers", "STOPWATCH" to "stopwatch", "WORLD" to "world", "MORNING" to "morning")) {
            val restored = DefaultStartupPage.valueOf(name)
            assertEquals(key, restored.tabKey)
            assertEquals(restored, DefaultStartupPage.fromName(name))
            assertEquals(1, Tab.entries.count { it.key == restored.tabKey })
        }
        assertEquals(DefaultStartupPage.ALARMS, DefaultStartupPage.fromName("unknown"))
    }
}
