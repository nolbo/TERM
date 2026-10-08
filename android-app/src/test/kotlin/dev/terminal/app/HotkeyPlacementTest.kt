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

    @Test fun outsideAndNonFiniteChangesAreRejectedWithoutMovingOtherKeys() {
        for (bounds in listOf(HotkeyBounds(-1f, 2f), HotkeyBounds(6.5f, 2f),
            HotkeyBounds(0f, 128f), HotkeyBounds(0f, 2f, 0.5f), HotkeyBounds(Float.NaN, 2f),
            HotkeyBounds(0f, Float.POSITIVE_INFINITY))) {
            assertEquals(bounds.toString(), original, placeHotkey(original, 0, bounds))
        }
        assertEquals(original, placeHotkey(original, -1, HotkeyBounds(0f, 3f)))
        val malformed = JSONObject(serializeHotkeyLayout(placeHotkey(original, 0, HotkeyBounds(0f, 3f))))
        malformed.getJSONArray("keys").getJSONObject(0).getJSONObject("bounds").put("y", 0).put("x", 2)
        assertEquals(HotkeyLayout(), parseHotkeyLayout(malformed.toString()))
    }

    @Test fun movingIntoOccupiedCellsPushesNeighborsWithoutRepackingDistantKeys() {
        val layout = original.copy(keys = original.keys + Hotkey("Far", "TAB", bounds = HotkeyBounds(6f, 8f)))
        val moved = placeHotkey(layout, 0, HotkeyBounds(2f, 0f, 2f))
        assertEquals(listOf(HotkeyBounds(2f, 0f, 2f), HotkeyBounds(2f, 1f), HotkeyBounds(3f, 1f), HotkeyBounds(6f, 8f)), hotkeyBounds(moved))
        assertEquals(layout.keys.map { it.copy(bounds = null) }, moved.keys.map { it.copy(bounds = null) })
        assertEquals(moved, parseHotkeyLayout(serializeHotkeyLayout(moved)))
    }

    @Test fun growingBothAxesPushesAChainOfDifferentSizedKeys() {
        val layout = HotkeyLayout(columns = 4, keys = listOf(
            Hotkey("A", "A", bounds = HotkeyBounds(0f, 0f)),
            Hotkey("B", "B", bounds = HotkeyBounds(1f, 0f, 2f)),
            Hotkey("C", "C", bounds = HotkeyBounds(1f, 1f, 2f, 2f)),
            Hotkey("D", "D", bounds = HotkeyBounds(3f, 6f)),
        ))
        val resized = placeHotkey(layout, 0, HotkeyBounds(0f, 0f, 3f, 2f))
        assertEquals(listOf(HotkeyBounds(0f, 0f, 3f, 2f), HotkeyBounds(1f, 2f, 2f),
            HotkeyBounds(1f, 3f, 2f, 2f), HotkeyBounds(3f, 6f)), hotkeyBounds(resized))
        assertEquals(resized, parseHotkeyLayout(serializeHotkeyLayout(resized)))
    }

    @Test fun bottomEdgeDisplacementCanReuseTheVacatedSpaceAbove() {
        val layout = HotkeyLayout(columns = 2, keys = listOf(
            Hotkey("A", "A", bounds = HotkeyBounds(0f, 0f, 2f, 127f)),
            Hotkey("B", "B", bounds = HotkeyBounds(0f, 127f, 2f)),
        ))
        val moved = placeHotkey(layout, 0, HotkeyBounds(0f, 1f, 2f, 127f))
        assertEquals(HotkeyBounds(0f, 0f, 2f), hotkeyBounds(moved)[1])
        assertEquals(moved, parseHotkeyLayout(serializeHotkeyLayout(moved)))
        assertNull(resolveHotkeyPlacement(layout, 0, HotkeyBounds(0f, 0f, 2f, 128f)))
        assertEquals(layout, placeHotkey(layout, 0, HotkeyBounds(0f, 0f, 2f, 128f)))
    }

    @Test fun previewingFromTheSameOriginalLayoutDoesNotAccumulateDisplacement() {
        val before = hotkeyBounds(original)
        val preview = resolveHotkeyPlacement(original, 0, HotkeyBounds(2f, 0f, 2f))
        assertNotNull(preview)
        assertEquals(before, hotkeyBounds(original))
        assertEquals(preview, resolveHotkeyPlacement(original, 0, HotkeyBounds(2f, 0f, 2f)))
        assertEquals(original, resolveHotkeyPlacement(original, 0, before[0]))
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

    @Test fun movementSnapsToTheNearestCellInBothDirections() {
        val bounds = HotkeyBounds(2f, 3f, 2f, 2f)
        assertEquals(bounds, transformHotkeyBounds(bounds, 0.49f, -0.49f, 7))
        assertEquals(HotkeyBounds(3f, 2f, 2f, 2f), transformHotkeyBounds(bounds, 0.51f, -0.51f, 7))
        assertEquals(HotkeyBounds(1f, 4f, 2f, 2f), transformHotkeyBounds(bounds, -0.51f, 0.51f, 7))
    }

    @Test fun allCornersSnapWithoutMovingTheirOppositeCorner() {
        val bounds = HotkeyBounds(2f, 3f, 2f, 2f)
        for (corner in HotkeyCorner.entries) {
            val dx = if (corner.left) -0.6f else 0.6f
            val dy = if (corner.top) -0.6f else 0.6f
            assertEquals(bounds, transformHotkeyBounds(bounds, dx / 2, dy / 2, 7, corner))
            val resized = transformHotkeyBounds(bounds, dx, dy, 7, corner)
            assertEquals(3f, resized.width, 0f)
            assertEquals(3f, resized.height, 0f)
            assertEquals(if (corner.left) bounds.right else bounds.x, if (corner.left) resized.right else resized.x, 0f)
            assertEquals(if (corner.top) bounds.bottom else bounds.y, if (corner.top) resized.bottom else resized.y, 0f)
        }
    }

    @Test fun editingAnOlderFractionalKeyAlignsOnlyThatKeyToTheGrid() {
        val older = placeHotkey(original, 0, HotkeyBounds(1.2f, 3.4f, 1.6f, 2.2f))
        val bounds = hotkeyBounds(older)[0]
        assertEquals(older, parseHotkeyLayout(serializeHotkeyLayout(older)))
        val moved = placeHotkey(older, 0, transformHotkeyBounds(bounds, 1.1f, 1.1f, 7))
        assertEquals(HotkeyBounds(2f, 5f, 2f, 3f), hotkeyBounds(moved)[0])
        assertEquals(hotkeyBounds(older).drop(1), hotkeyBounds(moved).drop(1))
        assertEquals(moved, parseHotkeyLayout(serializeHotkeyLayout(moved)))
        assertEquals(HotkeyBounds(1f, 3f, 3f, 4f), transformHotkeyBounds(bounds, 1.1f, 1.1f, 7, HotkeyCorner.BottomRight))
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
