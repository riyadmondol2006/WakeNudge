package com.riyadm.wakenudge.missions.focus

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.missions.ui.MissionButton
import com.riyadm.wakenudge.missions.ui.MissionButtonKind
import com.riyadm.wakenudge.missions.ui.MissionProgress
import com.riyadm.wakenudge.missions.ui.MissionPrompt
import com.riyadm.wakenudge.missions.ui.MissionScaffold
import com.riyadm.wakenudge.missions.ui.errorShake
import com.riyadm.wakenudge.missions.ui.overlay
import com.riyadm.wakenudge.missions.ui.rememberErrorFeedback
import com.riyadm.wakenudge.missions.ui.rememberScreenReaderOn
import com.riyadm.wakenudge.ui.theme.LocalWakeHaptics
import kotlin.random.Random
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private val GameSaver = listSaver<FocusGame, Int>(save = { it.toSaved() }, restore = { FocusGame.restore(it) })

/** Fixed rather than themed: the two must look different from each other on every theme, and from the board. */
internal fun FocusColor.paint(): Color = when (this) {
    FocusColor.BLUE -> Color(0xFF1E6FD9)
    FocusColor.ORANGE -> Color(0xFFF08A00)
}

@Composable
internal fun FocusCue.name(): String = stringResource(
    when (color) {
        FocusColor.BLUE -> when (shape) {
            FocusShape.CIRCLE -> R.string.mission_focus_blue_circle
            FocusShape.SQUARE -> R.string.mission_focus_blue_square
            FocusShape.TRIANGLE -> R.string.mission_focus_blue_triangle
            FocusShape.DIAMOND -> R.string.mission_focus_blue_diamond
        }
        FocusColor.ORANGE -> when (shape) {
            FocusShape.CIRCLE -> R.string.mission_focus_orange_circle
            FocusShape.SQUARE -> R.string.mission_focus_orange_square
            FocusShape.TRIANGLE -> R.string.mission_focus_orange_triangle
            FocusShape.DIAMOND -> R.string.mission_focus_orange_diamond
        }
    },
)

/**
 * Focus Tap: tap only the shape asked for, as shapes come and go around a 3 x 3 board. With a screen reader nothing is
 * timed: each shape is announced and stays until the user answers "That's it" or "Not this one".
 */
@Composable
internal fun FocusMission(
    config: MissionConfig,
    onInteraction: () -> Unit,
    onComplete: () -> Unit,
    random: Random = Random.Default,
    screenReader: Boolean = rememberScreenReaderOn(),
) {
    val haptics = LocalWakeHaptics.current
    val feedback = rememberErrorFeedback()
    val game = rememberSaveable(saver = GameSaver) { FocusGame.forDifficulty(config.difficulty, config.count, random) }
    var revision by remember { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf<FocusStimulus?>(null) }
    var wrong by remember { mutableStateOf(false) }
    val latestComplete by rememberUpdatedState(onComplete)
    @Suppress("UNUSED_VARIABLE") val observed = revision
    val taps = remember { Channel<Int>(Channel.CONFLATED) }
    val (showMs, gapMs) = remember(config.difficulty) { FocusGame.timing(config.difficulty) }

    fun answer(result: FocusGame.Result) {
        when (result) {
            FocusGame.Result.IGNORED, FocusGame.Result.LET_GO, FocusGame.Result.MISSED -> Unit
            FocusGame.Result.HIT -> { haptics.tick(); wrong = false }
            FocusGame.Result.ROUND_COMPLETE -> { haptics.confirm(); wrong = false }
            FocusGame.Result.GAME_COMPLETE -> haptics.confirm()
            FocusGame.Result.WRONG -> {
                haptics.reject()
                feedback.trigger()
                wrong = true
            }
        }
        revision++
    }

    LaunchedEffect(game, screenReader) {
        delay(600)
        while (!game.finished) {
            // A tap that arrived as the last shape timed out belongs to that one, not to this.
            while (taps.tryReceive().isSuccess) Unit
            val stimulus = game.next()
            shown = stimulus
            revision++
            if (screenReader) {
                // Waits for an answer, however long it takes.
                while (game.current?.id == stimulus.id) taps.receive()
            } else {
                val tapped = withTimeoutOrNull(showMs) { taps.receive() }
                if (tapped == null) answer(game.letGo(stimulus.id))
            }
            shown = null
            delay(gapMs)
        }
        delay(400)
        latestComplete()
    }

    fun tap(stimulus: FocusStimulus) {
        onInteraction()
        answer(game.tap(stimulus.id))
        taps.trySend(stimulus.id)
    }

    fun letGo(stimulus: FocusStimulus) {
        onInteraction()
        answer(game.letGo(stimulus.id))
        taps.trySend(stimulus.id)
    }

    val targetName = game.target.name()
    val status = when {
        game.finished -> stringResource(R.string.mission_focus_done)
        wrong -> stringResource(R.string.mission_focus_wrong)
        screenReader && shown != null -> stringResource(R.string.mission_focus_now, shown!!.cue.name())
        else -> stringResource(R.string.mission_focus_hits, game.hits, game.hitsPerRound)
    }

    MissionScaffold(scrollable = false) {
        MissionProgress(game.completedRounds.toFloat() / game.rounds, stringResource(R.string.mission_focus_progress, minOf(game.round + 1, game.rounds), game.rounds))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Canvas(Modifier.size(36.dp)) { drawCue(game.target) }
            Text(
                stringResource(R.string.mission_focus_prompt, targetName),
                style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(start = 12.dp),
            )
        }
        MissionPrompt(status)
        if (screenReader) {
            val current = shown
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MissionButton(stringResource(R.string.mission_focus_its_it), { current?.let(::tap) }, Modifier.fillMaxWidth(), enabled = current != null)
                MissionButton(stringResource(R.string.mission_focus_not_it), { current?.let(::letGo) }, Modifier.fillMaxWidth(), kind = MissionButtonKind.Tonal, enabled = current != null)
            }
        } else {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth, maxHeight).coerceAtMost(420.dp)
                val gap = 10.dp
                val cellSize = (side - gap * 2) / 3
                Column(Modifier.size(side).errorShake(feedback), verticalArrangement = Arrangement.spacedBy(gap)) {
                    for (row in 0 until 3) {
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            for (col in 0 until 3) {
                                val cell = row * 3 + col
                                Cell(shown?.takeIf { it.cell == cell }, cellSize, feedback.overlay(), ::tap)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Cell(stimulus: FocusStimulus?, size: Dp, flash: Color, onTap: (FocusStimulus) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val base = Modifier.size(size).clip(RoundedCornerShape(20.dp)).background(scheme.surfaceContainerHighest).background(flash)
    if (stimulus == null) {
        Box(base)
        return
    }
    val name = stimulus.cue.name()
    Box(
        base
            .clickable(role = Role.Button, onClickLabel = name) { onTap(stimulus) }
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(size * 0.18f)) { drawCue(stimulus.cue) }
    }
}

private fun DrawScope.drawCue(cue: FocusCue) {
    val color = cue.color.paint()
    val w = size.width
    val h = size.height
    when (cue.shape) {
        FocusShape.CIRCLE -> drawCircle(color, radius = minOf(w, h) / 2)
        FocusShape.SQUARE -> drawRect(color)
        FocusShape.TRIANGLE -> drawPath(Path().apply { moveTo(w / 2, 0f); lineTo(w, h); lineTo(0f, h); close() }, color)
        FocusShape.DIAMOND -> drawPath(Path().apply { moveTo(w / 2, 0f); lineTo(w, h / 2); lineTo(w / 2, h); lineTo(0f, h / 2); close() }, color)
    }
}
