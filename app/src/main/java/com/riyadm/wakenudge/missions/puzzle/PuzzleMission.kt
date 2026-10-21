package com.riyadm.wakenudge.missions.puzzle

import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.Difficulty
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.domain.model.PuzzleKind
import com.riyadm.wakenudge.missions.ui.MissionProgress
import com.riyadm.wakenudge.missions.ui.MissionPrompt
import com.riyadm.wakenudge.missions.ui.MissionScaffold
import com.riyadm.wakenudge.missions.ui.errorShake
import com.riyadm.wakenudge.missions.ui.overlay
import com.riyadm.wakenudge.missions.ui.rememberErrorFeedback
import com.riyadm.wakenudge.ui.theme.LocalWakeHaptics
import com.riyadm.wakenudge.ui.theme.WakeMotion
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import kotlinx.coroutines.delay

private val SlidingSaver = listSaver<SlidingPuzzle, Int>(save = { listOf(it.size) + it.tiles }, restore = { SlidingPuzzle.restore(it[0], it.drop(1)) })
private val GridSaver = listSaver<NumberGrid, Int>(save = { listOf(it.side, it.next) + it.numbers }, restore = { NumberGrid(it[0], it.drop(2), it[1]) })
private val PatternSaver = listSaver<List<Int>, Int>(save = { it }, restore = { it })

/**
 * The puzzle mission: one of three small games chosen by [MissionConfig.puzzleKind], repeated [MissionConfig.count]
 * times (normally once). Each game is generated fresh, and all of them are solvable by construction.
 */
@Composable
internal fun PuzzleMission(config: MissionConfig, onInteraction: () -> Unit, onComplete: () -> Unit) {
    val total = config.count.coerceAtLeast(1)
    var solved by rememberSaveable { mutableIntStateOf(0) }
    var finished by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(finished) { if (finished) onComplete() }

    MissionScaffold(scrollable = false, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (total > 1) {
            MissionProgress(solved.toFloat() / total, stringResource(R.string.mission_puzzle_progress, minOf(solved + 1, total), total))
        }
        key(solved) {
            val onSolved: () -> Unit = {
                if (solved + 1 >= total) {
                    finished = true
                } else {
                    solved++
                }
            }
            when (config.puzzleKind) {
                PuzzleKind.SLIDING_TILES -> SlidingTilesGame(config.difficulty, onInteraction, onSolved)
                PuzzleKind.NUMBER_GRID -> NumberGridGame(config.difficulty, onInteraction, onSolved)
                PuzzleKind.PATTERN_TRACE -> PatternTraceGame(config.difficulty, onInteraction, onSolved)
            }
        }
    }
}

// ---- sliding tiles ------------------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.SlidingTilesGame(difficulty: Difficulty, onInteraction: () -> Unit, onSolved: () -> Unit) {
    val haptics = LocalWakeHaptics.current
    val feedback = rememberErrorFeedback()
    val puzzle = rememberSaveable(saver = SlidingSaver) { SlidingPuzzle.forDifficulty(difficulty, Random.Default) }
    var revision by remember { mutableIntStateOf(0) }
    var solved by rememberSaveable { mutableStateOf(false) }
    @Suppress("UNUSED_VARIABLE") val observed = revision
    LaunchedEffect(solved) {
        if (solved) {
            delay(550)
            onSolved()
        }
    }

    fun slide(index: Int, dRow: Int = 0, dCol: Int = 0, byTap: Boolean) {
        if (solved) return
        onInteraction()
        val moved = if (byTap) puzzle.tap(index) else puzzle.swipe(index, dRow, dCol)
        if (moved) {
            haptics.tick()
            revision++
            if (puzzle.isSolved) {
                haptics.confirm()
                solved = true
            }
        } else {
            haptics.reject()
            feedback.trigger()
        }
    }

    MissionPrompt(stringResource(R.string.mission_puzzle_sliding_prompt), body = stringResource(R.string.mission_puzzle_sliding_hint))
    MissionProgress(puzzle.correctCount.toFloat() / puzzle.tileTotal, stringResource(R.string.mission_puzzle_tiles_placed, puzzle.correctCount, puzzle.tileTotal))
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight).coerceAtMost(420.dp)
        val gap = 6.dp
        val tile = (side - gap * (puzzle.size + 1)) / puzzle.size
        val density = LocalDensity.current
        val stepPx = with(density) { (tile + gap).roundToPx() }
        val padPx = with(density) { gap.roundToPx() }
        val dragThresholdPx = with(density) { 14.dp.toPx() }
        Box(
            Modifier
                .size(side)
                .errorShake(feedback)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .background(feedback.overlay()),
        ) {
            for (value in 1 until puzzle.size * puzzle.size) {
                key(value) {
                    val index = puzzle.tiles.indexOf(value)
                    val target = IntOffset(padPx + (index % puzzle.size) * stepPx, padPx + (index / puzzle.size) * stepPx)
                    val position by animateIntOffsetAsState(target, WakeMotion.defaultSpatial(), label = "tile$value")
                    val home = index == value - 1
                    val container = if (home) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
                    val content = if (home) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                    val description = stringResource(R.string.mission_puzzle_tile, value)
                    Box(
                        Modifier
                            .offset { position }
                            .size(tile)
                            .clip(RoundedCornerShape(if (puzzle.size > 3) 18.dp else 22.dp))
                            .background(container)
                            .pointerInput(index) {
                                var dx = 0f
                                var dy = 0f
                                detectDragGestures(
                                    onDragStart = { dx = 0f; dy = 0f },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dx += amount.x
                                        dy += amount.y
                                    },
                                    onDragEnd = {
                                        if (maxOf(abs(dx), abs(dy)) >= dragThresholdPx) {
                                            if (abs(dx) > abs(dy)) slide(index, dCol = if (dx > 0) 1 else -1, byTap = false) else slide(index, dRow = if (dy > 0) 1 else -1, byTap = false)
                                        }
                                    },
                                )
                            }
                            .clickable(role = Role.Button, onClickLabel = description) { slide(index, byTap = true) }
                            .semantics { contentDescription = description },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "$value",
                            style = (if (puzzle.size > 3) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge).copy(fontWeight = FontWeight.SemiBold),
                            color = content,
                        )
                    }
                }
            }
        }
    }
}

// ---- number grid (Schulte table) ---------------------------------------------------------------------------------

@Composable
private fun ColumnScope.NumberGridGame(difficulty: Difficulty, onInteraction: () -> Unit, onSolved: () -> Unit) {
    val haptics = LocalWakeHaptics.current
    val feedback = rememberErrorFeedback()
    val grid = rememberSaveable(saver = GridSaver) { NumberGrid.forDifficulty(difficulty, Random.Default) }
    var revision by remember { mutableIntStateOf(0) }
    var solved by rememberSaveable { mutableStateOf(false) }
    @Suppress("UNUSED_VARIABLE") val observed = revision
    LaunchedEffect(solved) {
        if (solved) {
            delay(550)
            onSolved()
        }
    }

    MissionPrompt(
        if (grid.isComplete) stringResource(R.string.mission_puzzle_grid_done) else stringResource(R.string.mission_puzzle_grid_prompt, grid.next),
        body = stringResource(R.string.mission_puzzle_grid_hint, grid.total),
    )
    MissionProgress((grid.next - 1).toFloat() / grid.total, stringResource(R.string.mission_puzzle_grid_progress, grid.next - 1, grid.total))
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight).coerceAtMost(420.dp)
        val gap = if (grid.side >= 5) 6.dp else 8.dp
        val cell = (side - gap * (grid.side - 1)) / grid.side
        Column(Modifier.size(side).errorShake(feedback), verticalArrangement = Arrangement.spacedBy(gap)) {
            for (row in 0 until grid.side) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (col in 0 until grid.side) {
                        val number = grid.numbers[row * grid.side + col]
                        val found = number < grid.next
                        val container = if (found) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceContainerHigh
                        val description = stringResource(R.string.mission_puzzle_number, number)
                        Box(
                            Modifier
                                .size(cell)
                                .clip(RoundedCornerShape(if (grid.side >= 5) 14.dp else 20.dp))
                                .background(container)
                                .background(if (!found) feedback.overlay() else Color.Transparent)
                                .clickable(enabled = !found && !solved, role = Role.Button, onClickLabel = description) {
                                    onInteraction()
                                    when (grid.tap(number)) {
                                        NumberGrid.TapResult.CORRECT -> haptics.tick()
                                        NumberGrid.TapResult.COMPLETE -> {
                                            haptics.confirm()
                                            solved = true
                                        }
                                        NumberGrid.TapResult.WRONG -> {
                                            haptics.reject()
                                            feedback.trigger()
                                        }
                                    }
                                    revision++
                                }
                                .semantics { contentDescription = description },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "$number",
                                style = (if (grid.side >= 5) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium).copy(fontWeight = FontWeight.SemiBold),
                                color = if (found) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f) else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---- pattern trace -----------------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.PatternTraceGame(difficulty: Difficulty, onInteraction: () -> Unit, onSolved: () -> Unit) {
    val haptics = LocalWakeHaptics.current
    val feedback = rememberErrorFeedback()
    val measurer = rememberTextMeasurer()
    val pattern = rememberSaveable(saver = PatternSaver) { PatternGenerator.generate(PatternGenerator.lengthFor(difficulty), Random.Default) }
    val tracer = remember(pattern) { PatternTracer(pattern) }
    var revision by remember { mutableIntStateOf(0) }
    var finger by remember { mutableStateOf<Offset?>(null) }
    var wrong by remember { mutableStateOf(false) }
    var solved by rememberSaveable { mutableStateOf(false) }
    @Suppress("UNUSED_VARIABLE") val observed = revision
    LaunchedEffect(solved) {
        if (solved) {
            delay(550)
            onSolved()
        }
    }
    LaunchedEffect(wrong) {
        if (wrong) {
            delay(550)
            tracer.clear()
            wrong = false
            revision++
        }
    }

    val dotNames = stringArrayResource(R.array.mission_dot_names)
    val description = stringResource(R.string.mission_puzzle_pattern_description, pattern.joinToString(", ") { dotNames[it] })
    val scheme = MaterialTheme.colorScheme
    val numberStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = scheme.onPrimary)

    MissionPrompt(stringResource(R.string.mission_puzzle_pattern_prompt), body = stringResource(R.string.mission_puzzle_pattern_hint))
    MissionProgress(
        tracer.visited.size.coerceAtMost(pattern.size).toFloat() / pattern.size,
        stringResource(R.string.mission_puzzle_pattern_progress, tracer.visited.size.coerceAtMost(pattern.size), pattern.size),
    )
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight).coerceAtMost(400.dp)
        val density = LocalDensity.current
        val sidePx = with(density) { side.toPx() }
        val cellPx = sidePx / PatternGrid.SIDE
        fun center(node: Int) = Offset((node % PatternGrid.SIDE + 0.5f) * cellPx, (node / PatternGrid.SIDE + 0.5f) * cellPx)
        fun nodeAt(point: Offset): Int? = (0 until PatternGrid.NODES).minByOrNull { hypot(center(it).x - point.x, center(it).y - point.y) }
            ?.takeIf { hypot(center(it).x - point.x, center(it).y - point.y) <= cellPx * 0.4f }

        fun finishStroke() {
            finger = null
            when {
                tracer.isEmpty || solved -> Unit
                tracer.matches() -> {
                    haptics.confirm()
                    solved = true
                }
                else -> {
                    haptics.reject()
                    feedback.trigger()
                    wrong = true
                }
            }
            revision++
        }

        Canvas(
            Modifier
                .size(side)
                .errorShake(feedback)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(scheme.surfaceContainerLow)
                .background(feedback.overlay())
                .semantics { contentDescription = description }
                .pointerInput(pattern, cellPx) {
                    detectDragGestures(
                        onDragStart = { start ->
                            if (!solved && !wrong) {
                                tracer.clear()
                                finger = start
                                nodeAt(start)?.let { tracer.enter(it); haptics.frequentTick() }
                                onInteraction()
                                revision++
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            if (!solved && !wrong) {
                                finger = change.position
                                val node = nodeAt(change.position)
                                if (node != null && tracer.enter(node)) {
                                    haptics.frequentTick()
                                    onInteraction()
                                }
                                revision++
                            }
                        },
                        onDragEnd = { finishStroke() },
                        onDragCancel = { finishStroke() },
                    )
                },
        ) {
            val guideColor = scheme.outlineVariant
            val strokeColor = when {
                wrong -> scheme.error
                solved -> scheme.tertiary
                else -> scheme.primary
            }
            // The guide: the pattern to copy, with numbered stops so the direction is unambiguous.
            for (i in 0 until pattern.size - 1) {
                drawLine(guideColor, center(pattern[i]), center(pattern[i + 1]), strokeWidth = 8.dp.toPx(), cap = StrokeCap.Round)
            }
            // The user's stroke.
            val visited = tracer.visited
            for (i in 0 until visited.size - 1) {
                drawLine(strokeColor, center(visited[i]), center(visited[i + 1]), strokeWidth = 12.dp.toPx(), cap = StrokeCap.Round)
            }
            val tip = finger
            if (visited.isNotEmpty() && tip != null && !wrong && !solved) {
                drawLine(strokeColor.copy(alpha = 0.6f), center(visited.last()), tip, strokeWidth = 12.dp.toPx(), cap = StrokeCap.Round)
            }
            for (node in 0 until PatternGrid.NODES) {
                val order = pattern.indexOf(node)
                val reached = node in visited
                val c = center(node)
                if (order < 0) {
                    drawCircle(scheme.onSurfaceVariant.copy(alpha = 0.35f), radius = 9.dp.toPx(), center = c)
                } else {
                    drawCircle(if (reached) strokeColor else scheme.primary.copy(alpha = 0.85f), radius = 17.dp.toPx(), center = c)
                    if (order == 0) drawCircle(scheme.primary, radius = 24.dp.toPx(), center = c, style = Stroke(3.dp.toPx()))
                    val label = measurer.measure("${order + 1}", numberStyle)
                    drawText(label, topLeft = Offset(c.x - label.size.width / 2f, c.y - label.size.height / 2f))
                }
            }
        }
    }
}
