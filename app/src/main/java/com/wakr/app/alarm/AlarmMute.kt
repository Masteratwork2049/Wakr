package com.wakr.app.alarm

import com.wakr.app.core.WakeOptions

/** Uses elapsed real time so clock changes cannot shorten a mute or its gap. */
internal data class AlarmMute(
    val occurrence: Pair<Long, Long>,
    val untilElapsedMs: Long?,
    val used: Long = 1,
    val availableAgainAtElapsedMs: Long? = null,
) {
    fun ended(wake: WakeOptions, now: Long): AlarmMute = copy(
        untilElapsedMs = null,
        availableAgainAtElapsedMs = if (wake.maxMutes?.let { used < it } != false)
            now + wake.muteGapSeconds * 1_000L else null,
    )
}

internal fun WakeOptions.canBeginMute(mute: AlarmMute?, now: Long): Boolean =
    muteDurationSec > 0 && maxMutes != 0 &&
        (mute == null || mute.untilElapsedMs == null &&
            mute.availableAgainAtElapsedMs?.let { now >= it } == true &&
            maxMutes?.let { mute.used < it } != false)
