package dev.terminal.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolbarMemoryTest {
    @Test fun combinesBothProcessesWithoutInventingMissingAreas() {
        val combined = requireNotNull(combineAppMemory(
            JSONObject().put("total", 200).put("java-heap", 80),
            JSONObject().put("total", 300).put("java-heap", 20)))
        assertEquals(500, combined.getLong("total"))
        assertEquals(100, combined.getLong("java-heap"))
        assertTrue(combined.isNull("graphics"))
        assertNull(combineAppMemory(null, JSONObject().put("total", 100)))
        assertNull(combineAppMemory(JSONObject().put("total", 100), null))
    }
}
