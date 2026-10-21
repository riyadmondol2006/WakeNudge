package com.riyadm.wakenudge.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import com.riyadm.wakenudge.ui.theme.WakeMotion

/**
 * A clock readout whose digits roll over individually (up when the value grows, down when it shrinks). Non-digit
 * characters stay put, so "07:30" only animates the characters that actually changed.
 */
@Composable
fun AnimatedClockText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    spokenText: String = text,
) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = spokenText },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        text.forEachIndexed { index, ch ->
            if (ch.isDigit()) {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = {
                        val up = targetState > initialState
                        (slideInVertically(WakeMotion.defaultSpatial()) { if (up) it else -it } + fadeIn(WakeMotion.defaultEffects()))
                            .togetherWith(slideOutVertically(WakeMotion.defaultSpatial()) { if (up) -it else it } + fadeOut(WakeMotion.fastEffects()))
                            .using(SizeTransform(clip = false))
                    },
                    label = "digit$index",
                ) { digit ->
                    Text(digit.toString(), style = style, color = color, modifier = Modifier.clearAndSetSemantics { })
                }
            } else {
                Text(ch.toString(), style = style, color = color, modifier = Modifier.clearAndSetSemantics { })
            }
        }
    }
}
