package dev.terminal.app.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlin.math.roundToInt

/** Keep only the visual snapshot after runtime removal; it cannot receive actions. */
internal class SessionTabExit(val size: State<Float>, val alpha: State<Float>) {
    val fraction get() = size.value.coerceIn(0f, 1f)
}

internal class SessionTabPresentation(sessions: List<SessionUi>, pair: List<String>) {
    var input by mutableStateOf(sessions)
    var displayed by mutableStateOf(sessions)
    var pair by mutableStateOf(pair)
    val exitingIds get() = displayed.map { it.id }.toSet() - input.map { it.id }.toSet()

    fun update(sessions: List<SessionUi>, currentPair: List<String>) {
        if (input != sessions) {
            displayed = retainExitingSessionTabs(displayed, sessions)
            input = sessions
        }
        if (pair.none { it in exitingIds }) pair = currentPair
    }

    fun finished(id: String) {
        if (id in exitingIds) displayed = displayed.filterNot { it.id == id }
    }
}

/** Live order and titles win; removed tabs retain their old slots until motion finishes. */
internal fun retainExitingSessionTabs(previous: List<SessionUi>, current: List<SessionUi>): List<SessionUi> {
    val ids = current.mapTo(hashSetOf()) { it.id }
    return current.toMutableList().apply {
        previous.forEachIndexed { index, session ->
            if (session.id !in ids) add(index.coerceAtMost(size), session)
        }
    }
}

@Composable
internal fun rememberSessionTabPresentation(sessions: List<SessionUi>, pair: List<String>, scope: String): SessionTabPresentation {
    val state = remember(scope) { SessionTabPresentation(sessions, pair) }
    state.update(sessions, pair)
    return state
}

@Composable
internal fun sessionTabExits(state: SessionTabPresentation): Map<String, SessionTabExit> {
    val exiting = state.exitingIds
    return state.displayed.associate { session -> session.id to key(state, session.id) {
        val transition = updateTransition(session.id !in exiting, label = "Session tab exit")
        val size = transition.animateFloat(
            transitionSpec = { MaterialTheme.motionScheme.defaultSpatialSpec() }, label = "Session tab size",
        ) { if (it) 1f else 0f }
        val alpha = transition.animateFloat(
            transitionSpec = { MaterialTheme.motionScheme.defaultEffectsSpec() }, label = "Session tab opacity",
        ) { if (it) 1f else 0f }
        LaunchedEffect(transition.currentState, transition.targetState) {
            if (!transition.currentState && !transition.targetState) state.finished(session.id)
        }
        remember(size, alpha) { SessionTabExit(size, alpha) }
    } }
}

internal fun Modifier.sessionTabExit(exit: SessionTabExit?, exiting: Boolean): Modifier =
    graphicsLayer {
        alpha = exit?.alpha?.value?.coerceIn(0f, 1f) ?: 1f
        val scale = 0.85f + 0.15f * (exit?.fraction ?: 1f)
        scaleX = scale
        scaleY = scale
    }.then(if (exiting) Modifier.clearAndSetSemantics { } else Modifier)

/** Measure the full tab so its text does not reflow as its horizontal slot closes. */
internal fun Modifier.sessionTabExitWidth(fraction: () -> Float): Modifier = clipToBounds().layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout((placeable.width * fraction()).roundToInt(), placeable.height) { placeable.placeRelative(0, 0) }
}
