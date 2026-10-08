package dev.terminal.app

import org.junit.Assert.*
import org.junit.Test

class HotkeyMovementTest {
    private val row = HotkeyLayout(columns = 4, keys = listOf("A", "B", "C", "D").mapIndexed { index, name ->
        Hotkey(name, name, bounds = HotkeyBounds(index.toFloat(), 0f))
    })

    @Test fun aCenterHoverSwapsDistantKeysAndLeavesIntermediateKeysAlone() {
        val moved = resolveHotkeyMove(row, 0, HotkeyBounds(3f, 0f))!!
        assertEquals(listOf(HotkeyBounds(3f, 0f), HotkeyBounds(1f, 0f), HotkeyBounds(2f, 0f), HotkeyBounds(0f, 0f)), hotkeyBounds(moved))
        assertPreserved(row, moved)
    }

    @Test fun differentSizedKeysCanSwapAcrossRowsWhenBothRectanglesFit() {
        val layout = row.copy(keys = listOf(
            Hotkey("A", "A", bounds = HotkeyBounds(0f, 5f, 2f, 2f)),
            Hotkey("B", "B", bounds = HotkeyBounds(2f, 1f)),
            Hotkey("C", "C", bounds = HotkeyBounds(3f, 8f)),
        ))
        val moved = resolveHotkeyMove(layout, 0, HotkeyBounds(2f, 1f, 2f, 2f), 2.5f, 1.5f)!!
        assertEquals(listOf(HotkeyBounds(2f, 1f, 2f, 2f), HotkeyBounds(0f, 5f), HotkeyBounds(3f, 8f)), hotkeyBounds(moved))
        assertPreserved(layout, moved)
    }

    @Test fun insertingForwardClosesTheVacatedSlotAndOpensAGapBetweenNeighbors() {
        val moved = resolveHotkeyMove(row, 0, HotkeyBounds(2f, 0f), 2.05f, 0.5f)!!
        assertEquals(listOf(HotkeyBounds(1f, 0f), HotkeyBounds(0f, 0f), HotkeyBounds(2f, 0f), HotkeyBounds(3f, 0f)), hotkeyBounds(moved))
        assertPreserved(row, moved)
    }

    @Test fun insertingBackwardShiftsTheNeighborIntoTheOldSlot() {
        val moved = resolveHotkeyMove(row, 3, HotkeyBounds(2f, 0f), 2.05f, 0.5f)!!
        assertEquals(listOf(HotkeyBounds(0f, 0f), HotkeyBounds(1f, 0f), HotkeyBounds(3f, 0f), HotkeyBounds(2f, 0f)), hotkeyBounds(moved))
        assertPreserved(row, moved)
    }

    @Test fun aVerticalEdgeHoverCanMoveNeighborsUpIntoTheVacatedSpace() {
        val layout = row.copy(keys = row.keys.mapIndexed { index, key -> key.copy(bounds = HotkeyBounds(0f, index.toFloat())) })
        val moved = resolveHotkeyMove(layout, 0, HotkeyBounds(0f, 2f), 0.5f, 2.05f)!!
        assertEquals(listOf(HotkeyBounds(0f, 1f), HotkeyBounds(0f, 0f), HotkeyBounds(0f, 2f), HotkeyBounds(0f, 3f)), hotkeyBounds(moved))
        assertPreserved(layout, moved)
    }

    @Test fun insertingFromAnotherRowOpensASlotSidewaysWithoutMovingUnrelatedRows() {
        val layout = row.copy(keys = listOf(
            row.keys[0].copy(bounds = HotkeyBounds(0f, 4f)),
            row.keys[1].copy(bounds = HotkeyBounds(0f, 0f)),
            row.keys[2].copy(bounds = HotkeyBounds(1f, 0f)),
            row.keys[3].copy(bounds = HotkeyBounds(2f, 0f)),
        ))
        val moved = resolveHotkeyMove(layout, 0, HotkeyBounds(1f, 0f), 1.95f, 0.5f)!!
        assertEquals(listOf(HotkeyBounds(2f, 0f), HotkeyBounds(0f, 0f), HotkeyBounds(1f, 0f), HotkeyBounds(3f, 0f)), hotkeyBounds(moved))
        assertPreserved(layout, moved)
    }

    @Test fun anUnsafeWidgetSwapFallsBackToInsertionWithoutChangingAnySizes() {
        val layout = row.copy(keys = listOf(row.keys[0], row.keys[1], row.keys[2].copy(bounds = HotkeyBounds(2f, 0f, 2f))))
        val moved = resolveHotkeyMove(layout, 0, HotkeyBounds(3f, 0f), 3f, 0.5f)!!
        assertEquals(listOf(HotkeyBounds(3f, 0f), HotkeyBounds(0f, 0f), HotkeyBounds(1f, 0f, 2f)), hotkeyBounds(moved))
        assertPreserved(layout, moved)
    }

    @Test fun anEmptyDestinationPreservesAllOtherKeysAndGaps() {
        val moved = resolveHotkeyMove(row, 0, HotkeyBounds(2f, 8f))!!
        assertEquals(HotkeyBounds(2f, 8f), hotkeyBounds(moved)[0])
        assertEquals(hotkeyBounds(row).drop(1), hotkeyBounds(moved).drop(1))
        assertPreserved(row, moved)
    }

    @Test fun theSameSnappedCellCanChangeFromInsertionToSwapWithoutAccumulatingMoves() {
        val before = hotkeyBounds(row)
        val inserted = resolveHotkeyMove(row, 0, HotkeyBounds(2f, 0f), 2.05f, 0.5f)
        val swapped = resolveHotkeyMove(row, 0, HotkeyBounds(2f, 0f), 2.5f, 0.5f)
        assertNotEquals(inserted, swapped)
        assertEquals(HotkeyBounds(1f, 0f), hotkeyBounds(inserted!!)[0])
        assertEquals(HotkeyBounds(0f, 0f), hotkeyBounds(swapped!!)[2])
        assertEquals(before, hotkeyBounds(row))
        assertEquals(inserted, resolveHotkeyMove(row, 0, HotkeyBounds(2f, 0f), 2.05f, 0.5f))
        assertEquals(row, resolveHotkeyMove(row, 0, before[0]))
    }

    @Test fun aFullRowCanMakeRoomInAnAdjacentRow() {
        val layout = row.copy(keys = row.keys + Hotkey("E", "E", bounds = HotkeyBounds(0f, 3f)))
        val moved = resolveHotkeyMove(layout, 4, HotkeyBounds(1f, 0f), 1.05f, 0.5f)!!
        assertEquals(HotkeyBounds(0f, 0f), hotkeyBounds(moved)[4])
        assertEquals(HotkeyBounds(0f, 1f), hotkeyBounds(moved)[0])
        assertEquals(hotkeyBounds(layout).subList(1, 4), hotkeyBounds(moved).subList(1, 4))
        assertPreserved(layout, moved)
    }

    @Test fun invalidMoveRequestsAreRejected() {
        assertNull(resolveHotkeyMove(row, -1, HotkeyBounds(0f, 1f)))
        assertNull(resolveHotkeyMove(row, 0, HotkeyBounds(4f, 1f)))
        assertNull(resolveHotkeyMove(row, 0, HotkeyBounds(1f, 0f), Float.NaN, 0.5f))
    }

    private fun assertPreserved(original: HotkeyLayout, moved: HotkeyLayout) {
        assertEquals(original.keys.map { it.copy(bounds = null) }, moved.keys.map { it.copy(bounds = null) })
        assertEquals(hotkeyBounds(original).map { it.width to it.height }, hotkeyBounds(moved).map { it.width to it.height })
        assertEquals(moved, parseHotkeyLayout(serializeHotkeyLayout(moved)))
    }
}
