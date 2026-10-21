package com.riyadm.wakenudge.missions.typing

import androidx.annotation.ArrayRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.Difficulty
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.missions.ui.MissionProgress
import com.riyadm.wakenudge.missions.ui.MissionScaffold
import com.riyadm.wakenudge.missions.ui.errorShake
import com.riyadm.wakenudge.missions.ui.overlay
import com.riyadm.wakenudge.missions.ui.rememberErrorFeedback
import com.riyadm.wakenudge.ui.theme.LocalWakeHaptics
import kotlin.random.Random
import kotlinx.coroutines.delay

@ArrayRes
internal fun phraseArray(difficulty: Difficulty): Int = when (difficulty) {
    Difficulty.EASY -> R.array.mission_phrases_easy
    Difficulty.MEDIUM -> R.array.mission_phrases_medium
    Difficulty.HARD -> R.array.mission_phrases_hard
}

/** Colours wrong characters in the text field as they are typed, without altering the text itself. */
private class MistakeHighlight(
    private val validator: TypingValidator,
    private val target: String,
    private val wrong: SpanStyle,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val analysis = validator.analyze(target, text.text)
        val styled = buildAnnotatedString {
            append(text.text)
            analysis.typedCorrect.forEachIndexed { index, ok -> if (!ok) addStyle(wrong, index, index + 1) }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }
}

/**
 * Type [MissionConfig.count] phrases with the normal keyboard. Waking up means getting fingers and eyes working, so
 * the phrase is long enough that it cannot be done on autopilot; the phrase above the field lights up as you go and
 * the first wrong character is marked immediately.
 */
@Composable
internal fun TypingMission(config: MissionConfig, onInteraction: () -> Unit, onComplete: () -> Unit) {
    val haptics = LocalWakeHaptics.current
    val feedback = rememberErrorFeedback()
    val validator = remember(config.difficulty) { TypingValidator(config.difficulty) }
    val pool = stringArrayResource(phraseArray(config.difficulty)).toList()
    val total = config.count.coerceAtLeast(1)
    val seed = rememberSaveable { Random.nextLong() }
    val phrases = remember(seed, pool, total) { TypingValidator.pickPhrases(pool, total, Random(seed)) }
    var done by rememberSaveable { mutableIntStateOf(0) }
    var typed by rememberSaveable { mutableStateOf("") }
    var finished by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val target = phrases.getOrElse(done) { phrases.lastOrNull().orEmpty() }
    val analysis = remember(target, typed) { validator.analyze(target, typed) }

    LaunchedEffect(done) {
        if (!finished) {
            // Focus can only be requested once the field is laid out; if that is not yet true the user simply taps it.
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
    }
    LaunchedEffect(finished) {
        if (finished) {
            delay(350)
            onComplete()
        }
    }

    fun check(newText: String) {
        val hadMistake = validator.analyze(target, typed).hasMistake
        typed = newText
        onInteraction()
        val result = validator.analyze(target, newText)
        when {
            result.isComplete -> {
                haptics.confirm()
                done++
                typed = ""
                if (done >= total) finished = true
            }
            result.hasMistake && !hadMistake -> {
                // Only buzz on the keystroke that introduced a mistake, not on every following character.
                haptics.reject()
                feedback.trigger()
            }
            else -> haptics.frequentTick()
        }
    }

    val phraseStyle = when (config.difficulty) {
        Difficulty.EASY -> MaterialTheme.typography.headlineMedium
        Difficulty.MEDIUM -> MaterialTheme.typography.headlineSmall
        Difficulty.HARD -> MaterialTheme.typography.titleLarge
    }
    val covered = MaterialTheme.colorScheme.primary
    val rest = MaterialTheme.colorScheme.onSurfaceVariant
    val wrongBg = MaterialTheme.colorScheme.errorContainer
    val wrongFg = MaterialTheme.colorScheme.onErrorContainer
    val goal = target.trim()
    val highlighted = remember(goal, analysis.coveredPrefix, analysis.hasMistake, covered, rest, wrongBg, wrongFg) {
        buildAnnotatedString {
            val prefix = analysis.coveredPrefix.coerceIn(0, goal.length)
            withStyle(SpanStyle(color = covered, fontWeight = FontWeight.SemiBold)) { append(goal.substring(0, prefix)) }
            var next = prefix
            if (analysis.hasMistake && prefix < goal.length) {
                withStyle(SpanStyle(color = wrongFg, background = wrongBg, fontWeight = FontWeight.SemiBold)) { append(goal[prefix]) }
                next = prefix + 1
            }
            withStyle(SpanStyle(color = rest)) { append(goal.substring(next)) }
        }
    }
    val statusText = when {
        analysis.hasMistake -> stringResource(R.string.mission_typing_mistake, analysis.coveredPrefix + 1)
        typed.isBlank() -> stringResource(R.string.mission_typing_start)
        else -> stringResource(R.string.mission_typing_on_track)
    }

    MissionScaffold(Modifier.imePadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        MissionProgress(done.toFloat() / total, stringResource(R.string.mission_typing_progress, minOf(done + 1, total), total))
        Box(
            Modifier
                .fillMaxWidth()
                .errorShake(feedback)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .background(feedback.overlay())
                .padding(20.dp)
                .semantics { contentDescription = goal },
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(highlighted, style = phraseStyle.copy(lineHeight = phraseStyle.lineHeight * 1.1f))
        }
        OutlinedTextField(
            value = typed,
            onValueChange = ::check,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            textStyle = TextStyle(fontSize = MaterialTheme.typography.titleLarge.fontSize),
            label = { Text(stringResource(R.string.mission_typing_field_label)) },
            minLines = 2,
            shape = MaterialTheme.shapes.large,
            visualTransformation = MistakeHighlight(validator, target, SpanStyle(color = wrongFg, background = wrongBg)),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = {
                if (!validator.isCorrect(target, typed)) {
                    haptics.reject()
                    feedback.trigger()
                }
            }),
        )
        Text(
            statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = if (analysis.hasMistake) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
