package dev.terminal.app

import android.animation.ValueAnimator
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import dev.terminal.emulator.TextStyle
import dev.terminal.view.TerminalView
import dev.terminal.app.ui.TerminalChromeColors
import dev.terminal.app.ui.terminalDynamicColors

/** One clock for the canvas and chrome. Interrupted transitions resume at the visible frame. */
internal class TerminalPaletteTransition(
    private val followTuiBackground: () -> Boolean = { false },
    private val publish: (TerminalChromeColors) -> Unit,
) {
    private var animator: ValueAnimator? = null
    private var frame: IntArray? = null
    private var chrome: TerminalChromeColors? = null

    fun capture(view: TerminalView?) {
        if (animator?.isRunning != true) frame = view?.currentSession?.emulator?.mColors?.mCurrentColors?.clone() ?: frame
    }

    fun show(view: TerminalView, animate: Boolean) {
        val emulator = view.currentSession?.emulator ?: return
        val colors = emulator.mColors
        val start = frame ?: colors.mCurrentColors.clone()
        val fromChrome = chrome ?: TerminalChromeColors(Color(start[TextStyle.COLOR_INDEX_BACKGROUND]))
        cancel()
        fun draw(fraction: Float) {
            if (emulator.synchronizedOutputDelayMillis > 0) return
            // Read live OSC colors on every frame, without exposing intermediate colors to the guest.
            val target = colors.mCurrentColors
            val current = IntArray(target.size) { index ->
                lerp(Color(start[index]), Color(target[index]), fraction).toArgb()
            }
            frame = current
            val followsTui = followTuiBackground() && emulator.isAlternateBufferActive
            val background = Color(terminalChromeBackground(
                emulator, followTuiBackground(), target,
            ))
            val targetChrome = if (followsTui) terminalDynamicColors(background, Color(colors.defaultBackgroundColor))
                else TerminalChromeColors(background)
            chrome = fromChrome.interpolate(targetChrome, fraction)
            view.setPaletteOverride(if (fraction < 1f) current else null)
            val canvasBackground = requireNotNull(chrome).background.toArgb()
                .takeIf { followsTui }
            view.setCanvasBackgroundOverride(canvasBackground)
            view.setBackgroundColor(canvasBackground ?: current[TextStyle.COLOR_INDEX_BACKGROUND])
            publish(requireNotNull(chrome))
        }
        if (!animate || !ValueAnimator.areAnimatorsEnabled() || start.contentEquals(colors.mCurrentColors)) {
            draw(1f)
            return
        }
        draw(0f)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240
            addUpdateListener { draw(it.animatedValue as Float) }
            start()
        }
    }

    fun update(view: TerminalView) {
        // The running transition already reads live colors; keep its clock and canvas override.
        if (animator?.isRunning != true) show(view, animate = false)
    }

    fun cancel() {
        animator?.removeAllUpdateListeners()
        animator?.cancel()
        animator = null
    }
}
