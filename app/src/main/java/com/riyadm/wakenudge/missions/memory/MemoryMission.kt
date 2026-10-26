package com.riyadm.wakenudge.missions.memory

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.Difficulty
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.missions.ui.MissionProgress
import com.riyadm.wakenudge.missions.ui.MissionPrompt
import com.riyadm.wakenudge.missions.ui.MissionScaffold
import com.riyadm.wakenudge.missions.ui.errorShake
import com.riyadm.wakenudge.missions.ui.rememberErrorFeedback
import com.riyadm.wakenudge.missions.ui.rememberScreenReaderOn
import com.riyadm.wakenudge.missions.ui.overlay
import com.riyadm.wakenudge.ui.theme.LocalWakeHaptics
import com.riyadm.wakenudge.ui.theme.WakeMotion
import kotlin.random.Random
import kotlinx.coroutines.delay

/** Playback while a screen reader speaks each tile ("Tile 3") through the prompt's live region. */
private const val SPOKEN_ON_MS = 1_300L
private const val SPOKEN_GAP_MS = 500L

private val GameSaver = listSaver<MemoryGame, Int>(save = { it.toSaved() }, restore = { MemoryGame.restore(it) })

/** Milliseconds a tile stays lit and the pause between tiles; shorter on harder levels. */
private fun playbackTiming(difficulty: Difficulty): Pair<Long, Long> = when (difficulty) {
    Difficulty.EASY -> 700L to 300L
    Difficulty.MEDIUM -> 600L to 260L
    Difficulty.HARD -> 480L to 200L
}

/** Distinct colours for the tiles, spread around the hue wheel so lit tiles are easy to tell apart. */
@Composable
private fun tileColor(index: Int, count: Int): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val hue = (index * 360f / count + 8f) % 360f
    return Color.hsv(hue, if (dark) 0.55f else 0.7f, if (dark) 0.95f else 0.9f)
}

/**
 * Simon-style pattern recall: tiles light up in sequence (with a haptic tick each), then the user repeats them. The
 * sequence grows by one tile per round; a wrong tap replays the round with a fresh sequence instead of failing.
 */
@Composable
internal fun MemoryMission(config: MissionConfig, onInteraction: () -> Unit, onComplete: () -> Unit) {
    val haptics = LocalWakeHaptics.current
    val feedback = rememberErrorFeedback()
    val rounds = config.count.coerceAtLeast(1)
    val game = rememberSaveable(saver = GameSaver) { MemoryGame.forDifficulty(config.difficulty, rounds, Random.Default) }
    // The game is a plain mutable object; this counter makes the UI re-read it after every change.
    var revision by remember { mutableIntStateOf(0) }
    var lit by remember { mutableIntStateOf(-1) }
    var finished by rememberSaveable { mutableStateOf(false) }
    @Suppress("UNUSED_VARIABLE") val observed = revision
    // With a screen reader the sequence is spoken tile by tile (below), which needs more time than a flash.
    val screenReader = rememberScreenReaderOn()
    val (onMillis, gapMillis) = remember(config.difficulty, screenReader) {
        if (screenReader) SPOKEN_ON_MS to SPOKEN_GAP_MS else playbackTiming(config.difficulty)
    }

    LaunchedEffect(game.sequenceId) {
        if (game.phase != MemoryGame.Phase.SHOWING) return@LaunchedEffect
        try {
            delay(750)
            for (tile in game.sequence) {
                lit = tile
                haptics.tick()
                delay(onMillis)
                lit = -1
                delay(gapMillis)
            }
            game.beginInput()
            revision++
        } finally {
            // A restart mid-playback (wrong tap, rotation) must not leave a tile stuck lit.
            lit = -1
        }
    }
    LaunchedEffect(finished) {
        if (finished) {
            delay(450)
            onComplete()
        }
    }

    fun onTile(tile: Int) {
        onInteraction()
        when (game.tap(tile)) {
            MemoryGame.TapResult.IGNORED -> Unit
            MemoryGame.TapResult.CORRECT -> haptics.tick()
            MemoryGame.TapResult.ROUND_COMPLETE -> haptics.confirm()
            MemoryGame.TapResult.GAME_COMPLETE -> {
                haptics.confirm()
                finished = true
            }
            MemoryGame.TapResult.WRONG -> {
                haptics.reject()
                feedback.trigger()
            }
        }
        revision++
    }

    val showing = game.phase == MemoryGame.Phase.SHOWING
    val status = when {
        game.phase == MemoryGame.Phase.FINISHED -> stringResource(R.string.mission_memory_done)
        showing && screenReader && lit >= 0 -> stringResource(R.string.mission_memory_tile, lit + 1)
        showing -> stringResource(R.string.mission_memory_watch)
        else -> stringResource(R.string.mission_memory_your_turn, game.inputIndex + 1, game.sequence.size)
    }

    MissionScaffold(scrollable = false) {
        MissionProgress(
            game.completedRounds.toFloat() / rounds,
            stringResource(R.string.mission_memory_progress, minOf(game.round + 1, rounds), rounds),
        )
        MissionPrompt(status)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val side = minOf(maxWidth, maxHeight).coerceAtMost(420.dp)
            val gap = if (game.gridSide > 3) 8.dp else 12.dp
            val tileSize = (side - gap * (game.gridSide - 1)) / game.gridSide
            Column(Modifier.size(side).errorShake(feedback), verticalArrangement = Arrangement.spacedBy(gap)) {
                for (row in 0 until game.gridSide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        for (col in 0 until game.gridSide) {
                            val index = row * game.gridSide + col
                            Tile(
                                index = index,
                                count = game.tileCount,
                                lit = lit == index,
                                enabled = !showing && game.phase == MemoryGame.Phase.INPUT,
                                size = tileSize,
                                flash = feedback.overlay(),
                                onClick = { onTile(index) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tile(index: Int, count: Int, lit: Boolean, enabled: Boolean, size: Dp, flash: Color, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val color = tileColor(index, count)
    // Pressing also lights the tile for a moment, so the user sees what they tapped.
    var pressedLit by remember { mutableStateOf(false) }
    LaunchedEffect(pressedLit) {
        if (pressedLit) {
            delay(180)
            pressedLit = false
        }
    }
    val on = lit || pressedLit
    val container by animateColorAsState(if (on) color else scheme.surfaceContainerHighest, WakeMotion.fastEffects(), label = "tile$index")
    val scale by animateFloatAsState(if (on) 1.07f else 1f, WakeMotion.fastSpatial(), label = "tileScale$index")
    val tileName = stringResource(R.string.mission_memory_tile, index + 1)
    val litText = stringResource(R.string.mission_memory_tile_lit)
    Box(
        Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled || lit) 1f else 0.72f
            }
            .clip(RoundedCornerShape(if (size > 90.dp) 28.dp else 20.dp))
            .background(container)
            .background(flash)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = tileName) {
                pressedLit = true
                onClick()
            }
            .semantics {
                contentDescription = tileName
                if (lit) stateDescription = litText
            },
    )
}
