package com.adm.clickgui.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import com.adm.utils.ui.animations.Easing
import com.adm.utils.ui.compose.*

@Composable
fun animateFloatAsState(target: Float, durationMillis: Int = 150, easing: Easing = Easing.LINEAR): Animatable {
    val tween = remember { Animatable(target) }
    SideEffect { tween.animateTo(target, durationMillis, easing) }
    return tween
}

@Composable
fun animateProgress(target: Boolean, durationMillis: Int = 150, easing: Easing = Easing.LINEAR): Animatable =
    animateFloatAsState(if (target) 1f else 0f, durationMillis, easing)

// Follows the condition at draw time instead of on recomposition, so hover and filters need no recompose.
@Composable
fun animateProgress(durationMillis: Int = 150, easing: Easing = Easing.LINEAR, target: () -> Boolean): Animatable {
    val condition = rememberUpdatedState(target)
    return remember { Following(Snapshot.withoutReadObservation(target), condition, durationMillis, easing) }
}

private class Following(
    startOn: Boolean,
    private val condition: State<() -> Boolean>,
    private val durationMillis: Int,
    private val easing: Easing,
) : Animatable(if (startOn) 1f else 0f) {
    override val value: Float
        get() {
            animateTo(if (condition.value()) 1f else 0f, durationMillis, easing)
            return super.value
        }
}

@Composable
fun AnimatedVisibility(
    visible: Boolean,
    durationMillis: Int = 200,
    easing: Easing = Easing.EASE_IN_OUT,
    content: @Composable () -> Unit,
): UiNode? {
    val progress = animateProgress(visible, durationMillis, easing)
    var lingering by remember { mutableStateOf(visible) }
    SideEffect { if (visible) lingering = true }
    if (!visible && !lingering) return null
    return Unfold(progress, { lingering = false }, content)
}

@Composable
fun AnimatedVisibility(
    visible: () -> Boolean,
    durationMillis: Int = 200,
    easing: Easing = Easing.EASE_IN_OUT,
    content: @Composable () -> Unit,
): UiNode = Unfold(animateProgress(durationMillis, easing, visible), {}, content)

@Composable
private fun Unfold(progress: Animatable, onFolded: () -> Unit, content: @Composable () -> Unit): UiNode {
    val node = Column(content = content)
    node.width { if (progress.value == 0f) 0 else width }
    node.height {
        val shown = progress.value
        if (shown == 0f && progress.target == 0f) onFolded()
        (height * shown).toInt()
    }
    return node.clip { progress.value < 1f }
}
