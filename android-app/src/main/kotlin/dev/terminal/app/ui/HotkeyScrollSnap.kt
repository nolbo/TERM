package dev.terminal.app.ui

import dev.terminal.app.HOTKEY_GAP_DP
import dev.terminal.app.HOTKEY_HEIGHT_DP
import dev.terminal.app.HotkeyLayout
import dev.terminal.app.hotkeyBounds
import kotlin.math.abs
import kotlin.math.roundToInt

/** Find the closest position whose viewport edges lie outside the saved key rectangles. */
internal fun hotkeyScrollSnapOffset(layout: HotkeyLayout, offset: Int, maximum: Int,
    viewport: Int, density: Float): Int {
    val current = offset.coerceIn(0, maximum.coerceAtLeast(0))
    if (maximum <= 0 || viewport <= 0) return current
    val padding = (4 * density).roundToInt()
    val pitch = HOTKEY_HEIGHT_DP + HOTKEY_GAP_DP
    val spans = hotkeyBounds(layout).map { rect ->
        val top = padding + (rect.y * pitch * density).roundToInt()
        top to top + ((rect.height * pitch - HOTKEY_GAP_DP) * density).roundToInt()
    }
    val candidates = buildSet {
        add(current)
        add(0)
        add(maximum)
        spans.forEach { (top, bottom) ->
            for (edge in listOf(top, bottom, top - viewport, bottom - viewport))
                if (edge in 0..maximum) add(edge)
        }
    }
    val safe = candidates.filter { candidate ->
        spans.none { (top, bottom) ->
            (candidate > top && candidate < bottom) ||
                (candidate + viewport > top && candidate + viewport < bottom)
        }
    }
    // Some custom keys exceed the viewport, or their staggered edges have no shared gap.
    // Keep these layouts reachable by aligning the nearest leading edge instead.
    val targets = safe.ifEmpty {
        listOf(0, maximum) + spans.map { (top, _) -> (top - padding).coerceIn(0, maximum) }
    }
    return targets.minWith(compareBy<Int> { abs(it - current) }.thenBy { it })
}
