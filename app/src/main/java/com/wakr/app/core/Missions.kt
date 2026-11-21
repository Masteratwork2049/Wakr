package com.wakr.app.core

import java.time.ZonedDateTime
import kotlin.random.Random

internal val Alarm.ringMissions: List<AlarmMission> get() = missions

internal val Alarm.missionsRemaining: Boolean
    get() = missionIndex < maxOf(ringMissions.size, privateMissionCount)

internal fun Alarm.canSnooze(): Boolean = wake.maxSnoozes?.let { it > 0 && snoozeCount < it } ?: true

internal fun Alarm.isPreRingProtected(now: ZonedDateTime = ZonedDateTime.now()): Boolean {
    if (wake.lockBeforeRingMin <= 0) return false
    val next = nextFire(now) ?: return false
    return !next.isAfter(now.plusMinutes(wake.lockBeforeRingMin.toLong()))
}

internal data class MathProblem(val expression: String, val answer: Int)

internal fun mathProblem(difficulty: MissionDifficulty, random: Random): MathProblem {
    return when (difficulty) {
        MissionDifficulty.EASY -> {
            val left = random.nextInt(3, 18)
            val right = random.nextInt(2, 10)
            MathProblem("$left + $right", left + right)
        }
        MissionDifficulty.MEDIUM -> if (random.nextBoolean()) {
            val left = random.nextInt(3, 10)
            val right = random.nextInt(3, 10)
            MathProblem("$left × $right", left * right)
        } else {
            val left = random.nextInt(15, 60)
            val right = random.nextInt(3, 20)
            MathProblem("$left + $right", left + right)
        }
        MissionDifficulty.HARD -> {
            val left = random.nextInt(4, 13)
            val right = random.nextInt(5, 13)
            val extra = random.nextInt(11, 59)
            MathProblem("$left × $right + $extra", left * right + extra)
        }
    }
}

internal fun adjacentTiles(first: Int, second: Int): Boolean =
    kotlin.math.abs(first % 3 - second % 3) + kotlin.math.abs(first / 3 - second / 3) == 1

internal fun shuffledTiles(random: Random): List<Int> {
    val tiles = (1..8).toMutableList().apply { add(0) }
    var gap = tiles.lastIndex
    repeat(31) {
        val next = tiles.indices.filter { adjacentTiles(it, gap) }.random(random)
        tiles[gap] = tiles[next]
        tiles[next] = 0
        gap = next
    }
    return tiles
}

internal fun typingStepAllowed(old: String, new: String): Boolean {
    val prefix = old.commonPrefixWith(new).length
    val suffix = old.drop(prefix).commonSuffixWith(new.drop(prefix)).length
    return new.length - prefix - suffix <= 1
}
