package dev.terminal.app.ui

import dev.terminal.app.Hotkey
import dev.terminal.app.HotkeyBounds
import dev.terminal.app.HotkeyLayout
import org.junit.Assert.*
import org.junit.Test

class HotkeyScrollSnapTest {
    private val layout = HotkeyLayout(columns = 2, keys = (1..12).map { Hotkey("Key $it", "F$it") })

    @Test fun defaultViewportSettlesToWholeRowsInBothDirections() {
        assertEquals(44, hotkeyScrollSnapOffset(layout, 62, 176, 92, 1f))
        assertEquals(88, hotkeyScrollSnapOffset(layout, 72, 176, 92, 1f))
        assertEquals(0, hotkeyScrollSnapOffset(layout, 10, 176, 92, 1f))
        assertEquals(176, hotkeyScrollSnapOffset(layout, 170, 176, 92, 1f))
    }

    @Test fun expandedViewportAccountsForBothEdgesAndPreservesSafePositions() {
        // Five complete 40 dp keys and their gaps fit after moving past the top padding.
        assertEquals(4, hotkeyScrollSnapOffset(layout, 20, 52, 216, 1f))
        assertEquals(48, hotkeyScrollSnapOffset(layout, 50, 52, 216, 1f))
        assertEquals(48, hotkeyScrollSnapOffset(layout, 48, 52, 216, 1f))
    }

    @Test fun fractionalPositionsAndTallKeysUseTheirSavedRectangles() {
        val custom = HotkeyLayout(columns = 2, keys = listOf(
            Hotkey("Tall", "ENTER", bounds = HotkeyBounds(0f, 0.5f, 2f, 2f)),
            Hotkey("Last", "ESC", bounds = HotkeyBounds(0f, 3.5f, 2f, 1f)),
        ))
        assertEquals(26, hotkeyScrollSnapOffset(custom, 40, 100, 92, 1f))
        // A viewport shorter than a key still permits scrolling to its leading edge.
        val oversized = custom.copy(keys = listOf(custom.keys.first().copy(bounds = HotkeyBounds(0f, 0.5f, 2f, 4f))))
        assertEquals(22, hotkeyScrollSnapOffset(oversized, 40, 162, 40, 1f))
    }

    @Test fun pixelRoundingAndScrollLimitsRemainReachable() {
        assertEquals(116, hotkeyScrollSnapOffset(layout, 160, 463, 242, 2.625f))
        assertEquals(127, hotkeyScrollSnapOffset(layout, 138, 138, 567, 2.625f))
        assertEquals(0, hotkeyScrollSnapOffset(layout, 5, 0, 92, 1f))
        assertEquals(10, hotkeyScrollSnapOffset(layout, 10, Int.MAX_VALUE, 0, 1f))
    }
}
