package dev.terminal.app.ui

import org.junit.Assert.*
import org.junit.Test

class SessionTabReorderTest {
    @Test fun pairSharesOneRowInPaneOrderWithoutDuplicatingOrLosingOtherTabs() {
        val rows = sessionTabRows(listOf("a", "b", "c", "d"), listOf("c", "a"))
        assertEquals(listOf(listOf("c", "a"), listOf("b"), listOf("d")), rows)
        assertEquals(setOf("a", "b", "c", "d"), rows.flatten().toSet())
        assertEquals(4, rows.flatten().size)
        assertEquals(listOf("b", "c", "a", "d"), rows.toMutableList().apply { add(1, removeAt(0)) }.flatten())
    }

    @Test fun missingDuplicateOrUnsplitPairsLeaveTheSingleTabListIntact() {
        val ids = listOf("a", "b", "c")
        val singles = ids.map { listOf(it) }
        assertEquals(singles, sessionTabRows(ids, emptyList()))
        assertEquals(singles, sessionTabRows(ids, listOf("a", "missing")))
        assertEquals(singles, sessionTabRows(ids, listOf("a", "a")))
        assertEquals(singles, sessionTabRows(ids, listOf("a", "b", "c")))
    }

    @Test fun passingOverATabDoesNotSplitAndAStationaryHoverArmsAt300Milliseconds() {
        val hover = SplitTabHover()
        assertNull(hover.update("b", 1, 100f, 0.5f, 1000, 10f))
        assertNull(hover.update("b", 1, 104f, 0.5f, 1250, 10f))
        assertNull(hover.update("b", 1, 104f, 0.5f, 1299, 10f))
        assertEquals(SplitTabPreview("b", 1, false), hover.update("b", 1, 104f, 0.5f, 1300, 10f))
        assertNull(hover.update(null, 1, 104f, 0.5f, 1350, 10f))
        assertNull(hover.target)
    }

    @Test fun liveReorderingLeavesTheCenterAvailableForHoverAndMovesAfterCrossingIt() {
        assertEquals(0, liveSessionTabDestination(0, 2, 55f, 24f, 52f, 48f))
        assertEquals(0, liveSessionTabDestination(0, 2, 76f, 24f, 52f, 48f))
        assertEquals(1, liveSessionTabDestination(0, 2, 100f, 24f, 52f, 48f))
        assertEquals(2, liveSessionTabDestination(2, 2, 76f, 24f, 52f, 48f))
        assertEquals(1, liveSessionTabDestination(2, 2, 54f, 24f, 52f, 48f))
    }

    @Test fun smallDriftOutsideTheEntryBandKeepsTheOriginalDwellButLeavingTheBodyCancelsIt() {
        val hover = SplitTabHover()
        fun move(y: Float, now: Long): SplitTabPreview? {
            val candidate = "b".takeIf { splitTabHoverContains(y, 48f, hover.target == "b") }
            return hover.update(candidate, 1, y, 0.15f, now, 10f)
        }
        // The edge cannot start a split, but remains usable after entering the center.
        assertNull(move(48f * 0.18f, 900))
        assertNull(hover.target)
        assertNull(move(48f * 0.25f, 1000))
        assertNull(move(48f * 0.18f, 1200))
        assertNull(move(48f * 0.18f, 1299))
        assertEquals(SplitTabPreview("b", 1, true), move(48f * 0.18f, 1300))
        assertNull(move(48f * 0.07f, 1400))
        assertNull(hover.target)
        assertNull(move(48f * 0.18f, 1500))
        assertNull(hover.target)
    }

    @Test fun movingToAnotherTabOrMovingVerticallyRestartsTheHover() {
        val hover = SplitTabHover()
        hover.update("b", 1, 100f, 0.5f, 1000, 10f)
        assertNull(hover.update("b", 1, 120f, 0.5f, 1250, 10f))
        assertNull(hover.update("b", 1, 120f, 0.5f, 1549, 10f))
        assertNull(hover.update("c", 2, 120f, 0.5f, 1550, 10f))
        assertNull(hover.update("c", 2, 120f, 0.5f, 1849, 10f))
        assertNotNull(hover.update("c", 2, 120f, 0.5f, 1850, 10f))
    }

    @Test fun armedPreviewChangesLeftRightOrderWithAStableCenterAndCanBeCancelled() {
        val hover = SplitTabHover()
        hover.update("b", 1, 100f, 0.5f, 0, 10f)
        assertTrue(hover.update("b", 1, 100f, 0.2f, 300, 10f)!!.sourceFirst)
        assertTrue(hover.update("b", 1, 100f, 0.51f, 350, 10f)!!.sourceFirst)
        assertFalse(hover.update("b", 1, 100f, 0.8f, 400, 10f)!!.sourceFirst)
        assertFalse(hover.update("b", 1, 100f, 0.49f, 450, 10f)!!.sourceFirst)
        hover.clear()
        assertNull(hover.update("b", 1, 100f, 0.2f, 500, 10f))
    }

    @Test fun previewKeepsTheTargetRowAndEverySessionWhileReplacingAnExistingPair() {
        val singles = listOf(listOf("a"), listOf("b"), listOf("c"))
        assertEquals(listOf(emptyList<String>(), listOf("b"), listOf("a", "c")),
            splitTabPreviewRows(singles, "a", "c", true))
        val grouped = listOf(listOf("a", "b"), listOf("c"), listOf("d"))
        val preview = splitTabPreviewRows(grouped, "c", "d", false)
        assertEquals(listOf(listOf("a"), listOf("b"), listOf("d", "c")), preview)
        assertEquals(setOf("a", "b", "c", "d"), preview.flatten().toSet())
        assertEquals(4, preview.flatten().size)
        assertEquals(listOf(listOf("b", "a"), listOf("c"), listOf("d")),
            splitTabPreviewRows(grouped, "b", "a", true))
    }
}
