package dev.terminal.app

import android.animation.ValueAnimator
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import dev.terminal.app.ui.TerminalChromeColors

/** One clock for the canvas and chrome. Interrupted transitions resume at the visible frame. */
internal class TerminalPaletteTransition(private val publish: (TerminalChromeColors) -> Unit) {
    private var animator: ValueAnimator? = null
    private var frame: IntArray? = null
    private var chrome: TerminalChromeColors? = null

    fun capture(view: TerminalView?) {
        if (animator?.isRunning != true) frame = view?.currentSession?.emulator?.mColors?.mCurrentColors?.clone() ?: frame
    }

    fun show(view: TerminalView, animate: Boolean) {
        val colors = view.currentSession?.emulator?.mColors ?: return
        val start = frame ?: colors.mCurrentColors.clone()
        val fromChrome = chrome ?: TerminalChromeColors(Color(start[TextStyle.COLOR_INDEX_BACKGROUND]))
        cancel()
        val targetChrome = TerminalChromeColors(Color(colors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]))
        fun draw(fraction: Float) {
            // Read live OSC colors on every frame, without exposing intermediate colors to the guest.
            val target = colors.mCurrentColors
            val current = IntArray(target.size) { index ->
                lerp(Color(start[index]), Color(target[index]), fraction).toArgb()
            }
            frame = current
            chrome = fromChrome.interpolate(targetChrome, fraction)
            view.setPaletteOverride(if (fraction < 1f) current else null)
            view.setBackgroundColor(current[TextStyle.COLOR_INDEX_BACKGROUND])
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

    fun cancel() {
        animator?.removeAllUpdateListeners()
        animator?.cancel()
        animator = null
    }
}
