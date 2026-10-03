package dev.terminal.app.ui

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTabOrderTest {
    private fun tabs(vararg ids: String) = ids.map { SessionUi(it, "NATIVE", it, "RUNNING") }

    @Test fun savedOrderSurvivesRuntimeOrderingAndTitleUpdates() {
        val live = tabs("a", "b", "c").map { it.copy(title = "Updated ${it.id}") }
        val restored = orderSessionTabs(live, parseSessionTabOrder(JSONArray(listOf("c", "a", "b")).toString()))
        assertEquals(listOf("c", "a", "b"), restored.map { it.id })
        assertEquals("Updated c", restored.first().title)
    }

    @Test fun closedAndDuplicateIdsAreRemovedAndNewSessionsAreAppended() {
        assertEquals(tabs("c", "a", "new"), orderSessionTabs(tabs("a", "c", "new"), listOf("closed", "c", "c", "a")))
        assertEquals(emptyList<SessionUi>(), orderSessionTabs(emptyList(), listOf("closed")))
    }

    @Test fun invalidSavedOrderUsesRuntimeOrder() {
        assertEquals(emptyList<String>(), parseSessionTabOrder("broken"))
        assertEquals(emptyList<String>(), parseSessionTabOrder(null))
        assertEquals(tabs("a", "b"), orderSessionTabs(tabs("a", "b"), emptyList()))
    }

    @Test fun closingSelectedTabChoosesNeighborInDisplayedOrder() {
        val previous = tabs("c", "a", "b")
        val updated = orderSessionTabs(tabs("b", "c"), previous.map { it.id })
        assertEquals("b", reconcileSessionTabs(previous, "a", emptySet(), updated).activeSessionId)
    }
}
