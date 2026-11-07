package com.riyadm.wakenudge.missions

import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.Difficulty
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.engine.policy.RingPolicy

/**
 * Why a mission cannot be done right now. A mission that cannot be done is never passed: a backup mission takes its
 * place. [userCaused] says whether it could be a choice (a refused permission, NFC switched off) rather than bad luck.
 */
enum class FallbackReason(val userCaused: Boolean) {
    /** The phone has no such hardware. */
    UNSUPPORTED(false),
    /** The mission was never set up (no code, tag or photo registered). */
    NOT_SET_UP(false),
    /** What was set up cannot be read any more. */
    BROKEN_SETUP(false),
    /** The camera would not start or delivered no frames. */
    CAMERA_FAILED(false),
    /** The camera shows only black, with the torch tried. */
    CAMERA_DARK(false),
    /** The sensor delivers nothing. */
    SENSOR_SILENT(false),
    PERMISSION_REFUSED(true),
    NFC_OFF(true),
}

/** What the steps of a chain are made of. */
enum class ChainStyle {
    /** Focus Tap rounds, typed phrases and sums, in turn. */
    MIXED,
    /** Typed phrases only. */
    TYPING_ONLY,
    /** Sums and typed phrases in turn: nothing timed and nothing physical. */
    SUMS_AND_TYPING,
}

/**
 * The backup mission and the way-out chain, as plain missions of one step each, so they run in the ordinary mission host.
 * Longer and varied rather than harder: no step is harder than today's hard sums, and there is never a multi-digit
 * multiplication (MEDIUM sums multiply by 2 to 5 at most).
 */
object FallbackChain {
    /** How many steps stand in for a mission that cannot be done: short for bad luck, the way-out length for what could be a choice. */
    fun length(reason: FallbackReason, ao: AntiOversleepSettings): Int =
        if (reason.userCaused) RingPolicy.wayOutSteps(ao) else ao.fallbackCount.coerceIn(1, MAX_FALLBACK_STEPS)

    /** [length] one-step missions in the [style], starting at [offset] in the turn (so two chains need not begin alike). */
    fun steps(length: Int, style: ChainStyle = ChainStyle.MIXED, offset: Int = 0): List<MissionConfig> {
        val kinds = when (style) {
            ChainStyle.MIXED -> listOf(MissionType.FOCUS_TAP, MissionType.TYPING, MissionType.MATH)
            ChainStyle.TYPING_ONLY -> listOf(MissionType.TYPING)
            ChainStyle.SUMS_AND_TYPING -> listOf(MissionType.MATH, MissionType.TYPING)
        }
        val start = Math.floorMod(offset, kinds.size)
        return List(length.coerceAtLeast(1)) { i -> MissionConfig(kinds[(start + i) % kinds.size], STEP_DIFFICULTY, count = 1) }
    }

    /** The backup mission for one that failed for [reason]. */
    fun backup(reason: FallbackReason, ao: AntiOversleepSettings, style: ChainStyle = ChainStyle.MIXED, offset: Int = 0): List<MissionConfig> =
        steps(length(reason, ao), style, offset)

    /** The way-out chain of an alarm that has one. */
    fun wayOut(ao: AntiOversleepSettings, style: ChainStyle = ChainStyle.MIXED): List<MissionConfig> = steps(RingPolicy.wayOutSteps(ao), style)

    /** Every step is at this level: below today's HARD, so a chain is longer, never harder. */
    val STEP_DIFFICULTY = Difficulty.MEDIUM
    const val MAX_FALLBACK_STEPS = 20
}
