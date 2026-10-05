package io.github.bszapp.wifitoolbox.uidefault.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable

/** Shows or removes content immediately while keeping visibility out of the caller's UI branch. */
@Composable
fun ImmediateVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = EnterTransition.None,
        exit = ExitTransition.None,
        content = content,
    )
}

/** Switches content immediately without adding a transition or size animation. */
@Composable
fun <S> ImmediateContent(
    targetState: S,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    contentKey: (targetState: S) -> Any? = { it },
    label: String = "ImmediateContent",
    content: @Composable AnimatedContentScope.(targetState: S) -> Unit,
) {
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            ContentTransform(
                targetContentEnter = EnterTransition.None,
                initialContentExit = ExitTransition.None,
                sizeTransform = null,
            )
        },
        contentAlignment = contentAlignment,
        contentKey = contentKey,
        label = label,
        content = content,
    )
}
