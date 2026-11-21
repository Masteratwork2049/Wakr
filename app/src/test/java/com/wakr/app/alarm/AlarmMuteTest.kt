package com.wakr.app.alarm

import com.wakr.app.core.WakeOptions
import org.junit.Assert.*
import org.junit.Test

class AlarmMuteTest {
    private val inProgress = AlarmMute(7L to 8L, 10_000L)

    @Test fun muteGapStartsWhenAudioResumesIncludingEarlyUnmute() {
        val wake = WakeOptions(maxMutes = 2)
        assertTrue(wake.canBeginMute(null, 100))
        assertFalse(wake.canBeginMute(inProgress, 500))
        val early = inProgress.ended(wake, 5_000)
        assertEquals(35_000L, early.availableAgainAtElapsedMs)
        assertFalse(wake.canBeginMute(early, 34_999))
        assertTrue(wake.canBeginMute(early, 35_000))
        val last = inProgress.copy(used = 2).ended(wake, 40_000)
        assertNull(last.availableAgainAtElapsedMs)
        assertFalse(wake.canBeginMute(last, 150_000))
    }

    @Test fun offAndOneMuteUseNoGapWhileUnlimitedKeepsIt() {
        assertFalse(WakeOptions(maxMutes = 0).canBeginMute(null, 100))
        assertFalse(WakeOptions(muteDurationSec = 0, maxMutes = null).canBeginMute(null, 100))
        assertNull(inProgress.ended(WakeOptions(), 10_000).availableAgainAtElapsedMs)
        val unlimited = WakeOptions(maxMutes = null, muteGapSeconds = 45)
        val ended = inProgress.copy(used = 99).ended(unlimited, 10_000)
        assertEquals(55_000L, ended.availableAgainAtElapsedMs)
        assertTrue(unlimited.canBeginMute(ended, 55_000))
    }
}
