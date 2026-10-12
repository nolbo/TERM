package dev.terminal.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class HotkeyDropTargetTest {
    private val grid = Rect(10f, 100f, 170f, 208f)
    private val cells = mapOf(
        0 to Rect(10f, 100f, 114f, 152f), // Two-column key.
        1 to Rect(118f, 100f, 170f, 152f),
        2 to Rect(10f, 156f, 62f, 208f),
    )

    @Test fun targetsUseActualSpansAndChooseNearestKeyInUnfilledRow() {
        assertEquals(0, hotkeyDropTarget(Offset(100f, 120f), cells, grid, grid))
        assertEquals(1, hotkeyDropTarget(Offset(140f, 120f), cells, grid, grid))
        assertEquals(2, hotkeyDropTarget(Offset(78f, 205f), cells, grid, grid))
        assertEquals(0, hotkeyDropTarget(Offset(115f, 120f), cells, grid, grid))
    }

    @Test fun droppingOutsideGridOrVisibleViewportCancels() {
        assertNull(hotkeyDropTarget(Offset(0f, 120f), cells, grid, grid))
        assertNull(hotkeyDropTarget(Offset(30f, 220f), cells, grid, grid))
        assertNull(hotkeyDropTarget(Offset(30f, 190f), cells, grid, Rect(0f, 0f, 200f, 180f)))
        assertNull(hotkeyDropTarget(Offset(30f, 120f), emptyMap(), grid, grid))
    }

    @Test fun movingAcrossRowsRetainsWideKeyAndDuplicateEntries() {
        val same = Hotkey("duplicate", "CTRL+C", 2)
        val layout = HotkeyLayout(keys = listOf(same, Hotkey("tab", "TAB"), same, Hotkey("alt", "ALT")))
        val moved = moveHotkey(layout, 0, 3)
        assertEquals(listOf(layout.keys[1], same, layout.keys[3], same), moved.keys)
        assertEquals(layout, moveHotkey(moved, 3, 0))
    }
}
