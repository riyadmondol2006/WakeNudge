package com.riyadm.wakenudge.ui.update

import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.LazyListScope

/** The Play build has no update popup. The GitHub APK defines the real one under the same name in src/github. */
@Composable
fun UpdatePromptHost() = Unit

/** The Play build has no "Updates" section on the About screen. */
@Suppress("UnusedReceiverParameter")
fun LazyListScope.updatesSection() = Unit
