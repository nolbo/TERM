package dev.terminal.app.ui

import org.junit.Assert.*
import org.junit.Test

class SessionTabExitTest {
    private fun tabs(vararg ids: String) = ids.map { SessionUi(it, "NATIVE", it, "RUNNING") }

    @Test fun removedTabsKeepTheirSlotsUntilTheirOwnAnimationFinishes() {
        val state = SessionTabPresentation(tabs("a", "b", "c"), emptyList())
        state.update(tabs("c"), emptyList())
        assertEquals(tabs("a", "b", "c"), state.displayed)
        assertEquals(setOf("a", "b"), state.exitingIds)
        state.finished("b")
        assertEquals(tabs("a", "c"), state.displayed)
        state.update(tabs("c", "d"), emptyList())
        assertEquals(tabs("a", "c", "d"), state.displayed)
        state.finished("a")
        assertEquals(tabs("c", "d"), state.displayed)
    }

    @Test fun liveOrderTitlesAndReappearingIdsWinOverExitSnapshots() {
        val state = SessionTabPresentation(tabs("a", "b", "c"), emptyList())
        state.update(tabs("a", "c"), emptyList())
        val latest = tabs("c", "b", "a").map { it.copy(title = "Updated ${it.id}") }
        state.update(latest, emptyList())
        state.finished("b")
        assertEquals(latest, state.displayed)
        assertTrue(state.exitingIds.isEmpty())
    }

    @Test fun closingSplitMemberKeepsVisualPairUntilExitCompletes() {
        val state = SessionTabPresentation(tabs("a", "b", "c"), listOf("a", "b"))
        state.update(tabs("b", "c"), emptyList())
        assertEquals(listOf("a", "b"), state.pair)
        state.finished("a")
        state.update(tabs("b", "c"), emptyList())
        assertTrue(state.pair.isEmpty())
    }

    @Test fun closingAllTabsRetainsTheLastFrameThenBecomesEmpty() {
        val state = SessionTabPresentation(tabs("a", "b"), emptyList())
        state.update(emptyList(), emptyList())
        assertEquals(tabs("a", "b"), state.displayed)
        state.finished("a")
        state.finished("b")
        assertTrue(state.displayed.isEmpty())
        state.update(emptyList(), emptyList())
        assertTrue(state.displayed.isEmpty())
    }
}
