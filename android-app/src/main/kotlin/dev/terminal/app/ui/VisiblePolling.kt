package dev.terminal.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Stops work at ON_STOP even when the background Compose recomposer is suspended. */
@Composable
internal fun VisiblePolling(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {
    val lifecycle = (LocalContext.current as? LifecycleOwner)?.lifecycle ?: return
    val scope = rememberCoroutineScope()
    DisposableEffect(lifecycle, *keys) {
        var work: Job? = null
        fun start() { if (work?.isActive != true) work = scope.launch(block = block) }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> { work?.cancel(); work = null }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose { lifecycle.removeObserver(observer); work?.cancel() }
    }
}
