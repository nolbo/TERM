package dev.terminal.app.ui

import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import java.util.WeakHashMap

internal data class TerminalSheetCorners(val left: Color, val right: Color)

internal data class TerminalCanvasSurface(val left: Int, val right: Int, val bottom: Int, val color: Color)

/** Physical adjacency, independent of focused pane, pane order, split ratio, or layout direction. */
internal fun terminalSheetCorners(surfaces: List<TerminalCanvasSurface>): TerminalSheetCorners? {
    val bottom = surfaces.maxOfOrNull { it.bottom } ?: return null
    val adjacent = surfaces.filter { it.bottom == bottom }
    return TerminalSheetCorners(adjacent.minBy { it.left }.color, adjacent.maxBy { it.right }.color)
}

/** Presentation colors only: the terminal's existing palette clock publishes each visible canvas. */
internal class TerminalSheetBackdrop {
    private val backgrounds = WeakHashMap<View, Color>()
    private var corners by mutableStateOf<TerminalSheetCorners?>(null)

    fun update(view: View, color: Color) {
        backgrounds[view] = color
        refresh()
    }

    @Composable
    fun colors(fallback: Color): TerminalSheetCorners {
        val root = LocalView.current.rootView
        DisposableEffect(root) {
            val observer = root.viewTreeObserver
            val listener = ViewTreeObserver.OnGlobalLayoutListener { refresh() }
            observer.addOnGlobalLayoutListener(listener)
            onDispose {
                if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener)
                backgrounds.clear()
                corners = null
            }
        }
        return corners ?: TerminalSheetCorners(fallback, fallback)
    }

    private fun refresh() {
        val bounds = Rect()
        corners = terminalSheetCorners(backgrounds.mapNotNull { (view, color) ->
            if (view.isAttachedToWindow && view.isShown && view.getGlobalVisibleRect(bounds))
                TerminalCanvasSurface(bounds.left, bounds.right, bounds.bottom, color)
            else null
        })
    }
}
