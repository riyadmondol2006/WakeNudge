package com.riyadm.wakenudge.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class Difficulty { EASY, MEDIUM, HARD }

@Serializable
enum class MissionType(val defaultCount: Int, val needsSetup: Boolean) {
    MATH(defaultCount = 3, needsSetup = false),
    MEMORY(defaultCount = 3, needsSetup = false),
    TYPING(defaultCount = 2, needsSetup = false),
    QR_BARCODE(defaultCount = 1, needsSetup = true),
    NFC(defaultCount = 1, needsSetup = true),
    SHAKE(defaultCount = 30, needsSetup = false),
    STEPS(defaultCount = 30, needsSetup = false),
    SQUATS(defaultCount = 10, needsSetup = false),
    PUZZLE(defaultCount = 1, needsSetup = false),
    PHOTO(defaultCount = 1, needsSetup = true),
}

/** Sub-games of the puzzle mission, stored in [MissionConfig.params] under [MissionConfig.PARAM_PUZZLE]. */
@Serializable
enum class PuzzleKind { SLIDING_TILES, NUMBER_GRID, PATTERN_TRACE }

/**
 * One wake-up mission. [count] is the number of problems / rounds / shakes / steps / reps, depending on [type].
 * [params] carries mission-specific setup: the registered code for QR, the tag id for NFC, the reference
 * signature for photo matching, or the puzzle sub-game.
 */
@Serializable
data class MissionConfig(
    val type: MissionType,
    val difficulty: Difficulty = Difficulty.MEDIUM,
    val count: Int = type.defaultCount,
    val params: Map<String, String> = emptyMap(),
) {
    /** True when the mission still needs its one-time setup (a registered code, tag or reference photo). */
    val isConfigured: Boolean
        get() = when (type) {
            MissionType.QR_BARCODE -> params[PARAM_CODE]?.isNotBlank() == true
            MissionType.NFC -> params[PARAM_TAG_ID]?.isNotBlank() == true
            MissionType.PHOTO -> params[PARAM_SIGNATURE]?.isNotBlank() == true
            else -> true
        }

    val puzzleKind: PuzzleKind
        get() = params[PARAM_PUZZLE]?.let { runCatching { PuzzleKind.valueOf(it) }.getOrNull() } ?: PuzzleKind.SLIDING_TILES

    companion object {
        const val PARAM_CODE = "code"
        const val PARAM_CODE_LABEL = "codeLabel"
        const val PARAM_TAG_ID = "tagId"
        const val PARAM_SIGNATURE = "signature"
        const val PARAM_REFERENCE_FILE = "referenceFile"
        const val PARAM_PUZZLE = "puzzle"
        const val MAX_MISSIONS = 5
    }
}
