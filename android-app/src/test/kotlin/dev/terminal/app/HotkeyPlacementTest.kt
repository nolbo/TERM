package dev.terminal.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HotkeyPlacementTest {
    private val original = HotkeyLayout(columns = 7, keys = listOf(
        Hotkey("same", "CTRL+C", 2), Hotkey("same", "TAB"), Hotkey("B", "B"),
    ))

    @Test fun versionOneRetainsNamesOrderSpansAndInitialPositions() {
        val legacy = JSONObject(serializeHotkeyLayout(original)).put("version", 1).put("height", 80)
        val restored = parseHotkeyLayout(legacy.toString())
        assertEquals(original, restored)
        assertEquals(listOf(HotkeyBounds(0f, 0f, 2f), HotkeyBounds(2f, 0f), HotkeyBounds(3f, 0f)), hotkeyBounds(restored))
    }

    @Test fun sparseFractionalPositionsAndTallKeysSurviveSaving() {
        val layout = placeHotkey(original, 0, HotkeyBounds(4.25f, 12.5f, 2.5f, 3.25f))
        assertNotEquals(original, layout)
        assertEquals(original.keys.map { it.name to it.shortcut }, layout.keys.map { it.name to it.shortcut })
        assertEquals(hotkeyBounds(original).drop(1), hotkeyBounds(layout).drop(1))
        assertEquals(layout, parseHotkeyLayout(serializeHotkeyLayout(layout)))
        assertEquals(2, JSONObject(serializeHotkeyLayout(layout)).getInt("version"))
    }

    @Test fun overlappingOutsideAndNonFiniteChangesAreRejectedWithoutMovingOtherKeys() {
        for (bounds in listOf(HotkeyBounds(2f, 0f), HotkeyBounds(-1f, 2f), HotkeyBounds(6.5f, 2f),
            HotkeyBounds(0f, 128f), HotkeyBounds(0f, 2f, 0.5f), HotkeyBounds(Float.NaN, 2f),
            HotkeyBounds(0f, Float.POSITIVE_INFINITY))) {
            assertEquals(bounds.toString(), original, placeHotkey(original, 0, bounds))
        }
        assertEquals(original, placeHotkey(original, -1, HotkeyBounds(0f, 3f)))
        val malformed = JSONObject(serializeHotkeyLayout(placeHotkey(original, 0, HotkeyBounds(0f, 3f))))
        malformed.getJSONArray("keys").getJSONObject(0).getJSONObject("bounds").put("y", 0).put("x", 2)
        assertEquals(HotkeyLayout(), parseHotkeyLayout(malformed.toString()))
    }

    @Test fun everyCornerResizesBothAxesWhileKeepingTheOppositeCornerFixed() {
        val bounds = HotkeyBounds(2f, 3f, 2f, 2f)
        assertEquals(HotkeyBounds(1f, 2f, 3f, 3f), transformHotkeyBounds(bounds, -1f, -1f, 7, HotkeyCorner.TopLeft))
        assertEquals(HotkeyBounds(2f, 2f, 3f, 3f), transformHotkeyBounds(bounds, 1f, -1f, 7, HotkeyCorner.TopRight))
        assertEquals(HotkeyBounds(1f, 3f, 3f, 3f), transformHotkeyBounds(bounds, -1f, 1f, 7, HotkeyCorner.BottomLeft))
        assertEquals(HotkeyBounds(2f, 3f, 3f, 3f), transformHotkeyBounds(bounds, 1f, 1f, 7, HotkeyCorner.BottomRight))
        assertEquals(HotkeyBounds(3f, 4f, 1f, 1f), transformHotkeyBounds(bounds, 100f, 100f, 7, HotkeyCorner.TopLeft))
        assertEquals(HotkeyBounds(2f, 3f, 1f, 1f), transformHotkeyBounds(bounds, -100f, -100f, 7, HotkeyCorner.BottomRight))
        assertEquals(HotkeyBounds(5f, 126f, 2f, 2f), transformHotkeyBounds(bounds, 100f, 200f, 7))
    }

    @Test fun deletingLeavesAHoleAndAddingFillsAnAvailableAreaWithoutRepacking() {
        val sparse = placeHotkey(original, 2, HotkeyBounds(5f, 8.5f, 1.5f, 2f))
        val removed = removeHotkey(sparse, 0)
        assertEquals(hotkeyBounds(sparse).drop(1), hotkeyBounds(removed))
        val added = addHotkey(removed, Hotkey("Space", "SPACE"))
        assertEquals(hotkeyBounds(removed), hotkeyBounds(added).dropLast(1))
        assertEquals(HotkeyBounds(0f, 0f), hotkeyBounds(added).last())
        assertEquals(added, parseHotkeyLayout(serializeHotkeyLayout(added)))
    }

    @Test fun oldMaximumSizeLayoutsRemainReadable() {
        val layout = HotkeyLayout(columns = 2, keys = List(MAX_HOTKEYS) { Hotkey("$it", "TAB", 4) })
        assertEquals(layout, parseHotkeyLayout(JSONObject(serializeHotkeyLayout(layout)).put("version", 1).toString()))
        assertEquals(48f, hotkeyBounds(layout).maxOf { it.bottom })
    }

    @Test fun expandingTheCanvasKeepsLegacyPositionsAndShrinkingPastKeysIsRejected() {
        val layout = original.copy(columns = 3)
        val expanded = resizeHotkeyCanvas(layout, 6)
        assertEquals(6, expanded.columns)
        assertEquals(hotkeyBounds(layout), hotkeyBounds(expanded))
        assertEquals(1f, hotkeyBounds(expanded).last().y)
        assertEquals(layout, resizeHotkeyCanvas(layout, 2))
        assertEquals(layout, resizeHotkeyCanvas(layout, 11))
        assertEquals(expanded, parseHotkeyLayout(serializeHotkeyLayout(expanded)))
    }
}
