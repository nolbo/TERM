package dev.terminal.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MemoryDiagnosticsTest {
    @Test fun combinesBothProcessesWithoutInventingMissingAreas() {
        val combined = requireNotNull(combineAppMemory(
            JSONObject().put("basis", "pss").put("total", 200).put("java-heap", 80),
            JSONObject().put("basis", "pss").put("total", 300).put("java-heap", 20)))
        assertEquals(500, combined.getLong("total"))
        assertEquals(100, combined.getLong("java-heap"))
        assertTrue(combined.isNull("graphics"))
        assertNull(combineAppMemory(null, JSONObject().put("basis", "pss").put("total", 100)))
        assertNull(combineAppMemory(JSONObject().put("basis", "pss").put("total", 100), null))
    }

    @Test fun invalidDeviceSamplesAreUnknownRatherThanClampedToZero() {
        assertNull(DeviceMemory.from(0, 0))
        assertNull(DeviceMemory.from(100, -1))
        assertNull(DeviceMemory.from(100, 101))
        val device = requireNotNull(DeviceMemory.from(100, 25))
        assertEquals(75, device.used)
        assertEquals(0.75, device.usedRatio, 0.0)
    }
    @Test fun totalsRejectMixedBasesAndMissingMeasurements() {
        val pss = JSONObject().put("basis", "pss").put("total", 100)
        assertNull(combineAppMemory(pss, JSONObject().put("basis", "rss").put("total", 200)))
        assertNull(combineAppMemory(pss, JSONObject().put("total", 200)))
        val missing = requireNotNull(combineAppMemory(pss, JSONObject().put("basis", "pss")))
        assertTrue(missing.isNull("total"))
        val overflow = requireNotNull(combineAppMemory(pss, JSONObject().put("basis", "pss").put("total", Long.MAX_VALUE)))
        assertTrue(overflow.isNull("total"))
        assertNull(JSONObject().put("total", JSONObject.NULL).memoryBytes("total"))
        assertNull(JSONObject().put("total", -1).memoryBytes("total"))
        assertEquals(0L, JSONObject().put("total", 0).memoryBytes("total"))
    }
}
