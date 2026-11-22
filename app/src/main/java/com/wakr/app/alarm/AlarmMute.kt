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

internal data class MuteControl(
    val label: String,
    val enabled: Boolean = true,
    val isMuted: Boolean = false,
)

internal fun WakeOptions.muteControl(mute: AlarmMute?, now: Long): MuteControl? {
    if (muteDurationSec <= 0 || maxMutes == 0) return null
    mute?.untilElapsedMs?.let { deadline ->
        val seconds = ((deadline - now + 999) / 1_000).coerceAtLeast(1)
        return MuteControl("Unmute · ${seconds}s", isMuted = true)
    }
    if (canBeginMute(mute, now)) return MuteControl("Mute · ${muteDurationSec}s")
    val againAt = mute?.availableAgainAtElapsedMs ?: return null
    val seconds = (againAt - now + 999) / 1_000
    return if (seconds > 0) MuteControl("You can mute in $seconds secs", enabled = false) else null
}
