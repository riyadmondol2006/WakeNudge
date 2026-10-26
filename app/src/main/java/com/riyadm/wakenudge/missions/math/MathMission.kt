package com.riyadm.wakenudge.missions.math

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.missions.ui.MissionProgress
import com.riyadm.wakenudge.missions.ui.MissionScaffold
import com.riyadm.wakenudge.missions.ui.TouchTarget
import com.riyadm.wakenudge.missions.ui.errorShake
import com.riyadm.wakenudge.missions.ui.rememberErrorFeedback
import com.riyadm.wakenudge.missions.ui.tint
import com.riyadm.wakenudge.ui.components.rememberReduceMotion
import com.riyadm.wakenudge.ui.theme.LocalWakeHaptics
import com.riyadm.wakenudge.ui.theme.WakeMotion
import com.riyadm.wakenudge.ui.theme.pressScale
import com.riyadm.wakenudge.ui.theme.rememberInteractionSource
import kotlin.random.Random

private const val MAX_DIGITS = 6

private val ProblemSaver = listSaver<MathProblem, Any>(
    save = { listOf(it.expression, it.answer) },
    restore = { MathProblem(it[0] as String, it[1] as Int) },
)

/**
 * Solve [MissionConfig.count] arithmetic problems on a built-in keypad. The system keyboard is deliberately avoided:
 * it is slow to appear, can autocomplete, and a numeric pad of big keys is easier to hit half asleep.
 */
@Composable
internal fun MathMission(config: MissionConfig, onInteraction: () -> Unit, onComplete: () -> Unit) {
    val haptics = LocalWakeHaptics.current
    val reduceMotion = rememberReduceMotion()
    val feedback = rememberErrorFeedback()
    val total = config.count.coerceAtLeast(1)
    val seed = rememberSaveable { Random.nextLong() }
    val generator = remember(seed) { MathProblemGenerator(Random(seed)) }
    var solved by rememberSaveable { mutableIntStateOf(0) }
    var problem by rememberSaveable(stateSaver = ProblemSaver) { mutableStateOf(generator.next(config.difficulty)) }
    var input by rememberSaveable { mutableStateOf("") }
    // A wrong answer stays on screen (in the error colour) until the next key, so a typo can be seen and not just felt.
    var wrong by rememberSaveable { mutableStateOf(false) }
    var finished by rememberSaveable { mutableStateOf(false) }

    fun press(digit: Char) {
        if (wrong) {
            // Typing after a wrong answer starts a new one, like a calculator.
            wrong = false
            input = ""
        }
        if (input.length < MAX_DIGITS && !finished) {
            // No leading zeros: "007" is never an answer.
            input = if (input == "0") digit.toString() else input + digit
            haptics.frequentTick()
        }
        onInteraction()
    }

    fun delete() {
        wrong = false
        input = input.dropLast(1)
        haptics.tick()
        onInteraction()
    }

    fun submit() {
        onInteraction()
        // Nothing typed (or the wrong answer still showing): not an attempt, so no buzz and no shake.
        if (finished || input.isEmpty() || wrong) return
        if (input.toIntOrNull() == problem.answer) {
            haptics.confirm()
            solved++
            input = ""
            if (solved >= total) {
                finished = true
            } else {
                problem = generator.next(config.difficulty)
            }
        } else {
            haptics.reject()
            feedback.trigger()
            wrong = true
        }
    }

    LaunchedEffect(finished) { if (finished) onComplete() }

    // Above a mission the ring screen leaves what it can; the problem and the keys shrink to fit it, so the bottom row
    // (delete, 0, submit) is on screen without scrolling. Below the smallest sizes the column still scrolls.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val short = maxHeight < 560.dp
        val fixed = if (short) 244.dp else 272.dp
        val keyHeight = ((maxHeight - fixed) / 4).coerceIn(TouchTarget - 8.dp, TouchTarget + 8.dp)
        MissionScaffold(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MissionProgress(solved.toFloat() / total, stringResource(R.string.mission_math_progress, minOf(solved + 1, total), total))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 16.dp, vertical = if (short) 12.dp else 20.dp)
                    .semantics(mergeDescendants = true) { contentDescription = problem.expression },
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = problem.expression,
                    transitionSpec = {
                        if (reduceMotion) {
                            fadeIn(WakeMotion.defaultEffects()) togetherWith fadeOut(WakeMotion.fastEffects())
                        } else {
                            (slideInVertically(WakeMotion.defaultSpatial()) { it / 2 } + fadeIn(WakeMotion.defaultEffects()))
                                .togetherWith(slideOutVertically(WakeMotion.defaultSpatial()) { -it / 2 } + fadeOut(WakeMotion.fastEffects()))
                                .using(SizeTransform(clip = false))
                        }
                    },
                    label = "mathProblem",
                ) { expression ->
                    Text(
                        expression,
                        style = (if (short) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.displayMedium).copy(fontWeight = FontWeight.Medium),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .errorShake(feedback)
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(feedback.tint(MaterialTheme.colorScheme.surfaceContainerHighest))
                    .heightIn(min = 72.dp)
                    .padding(horizontal = 20.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = input.ifEmpty { stringResource(R.string.mission_math_answer_hint) },
                        style = MaterialTheme.typography.displaySmall.copy(fontSize = 40.sp, fontWeight = FontWeight.SemiBold),
                        color = when {
                            wrong -> MaterialTheme.colorScheme.error
                            input.isEmpty() -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        textAlign = TextAlign.Center,
                    )
                    if (wrong) {
                        Text(stringResource(R.string.mission_math_wrong), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Keypad(
                onDigit = ::press, onDelete = ::delete, onClear = { input = ""; wrong = false; haptics.tick(); onInteraction() },
                onSubmit = ::submit, canSubmit = input.isNotEmpty() && !wrong, keyHeight = keyHeight,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Keypad(
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit,
    canSubmit: Boolean,
    keyHeight: Dp,
) {
    val rows = listOf("123", "456", "789")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { digit ->
                    Key(onClick = { onDigit(digit) }, modifier = Modifier.weight(1f), height = keyHeight) {
                        Text(digit.toString(), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key(
                onClick = onDelete,
                onLongClick = onClear,
                modifier = Modifier.weight(1f),
                height = keyHeight,
                container = MaterialTheme.colorScheme.secondaryContainer,
                description = stringResource(R.string.mission_math_delete),
            ) {
                Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(28.dp))
            }
            Key(onClick = { onDigit('0') }, modifier = Modifier.weight(1f), height = keyHeight) {
                Text("0", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
            }
            Key(
                onClick = onSubmit,
                modifier = Modifier.weight(1f),
                height = keyHeight,
                container = if (canSubmit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                description = stringResource(R.string.mission_math_submit),
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = if (canSubmit) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}

/** A keypad key: a big rounded target that squashes under the finger. Long-press is optional (used to clear all). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Key(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    description: String? = null,
    height: Dp = TouchTarget + 8.dp,
    content: @Composable () -> Unit,
) {
    val interaction = rememberInteractionSource()
    Box(
        modifier
            .heightIn(min = height)
            .pressScale(interaction, 0.92f)
            .clip(RoundedCornerShape(28.dp))
            .background(container)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = description,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
