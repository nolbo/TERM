package dev.terminal.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MemoryDiagnosticsTest {
    @Test fun compositionKeepsPssDenominatorAndShowsTheDifference() {
        val segments = requireNotNull(memoryCompositionSegments(listOf(20, 30, 10, 0, 15, 5, 10), 100))
        assertEquals(listOf(0.2, 0.3, 0.1, 0.0, 0.15, 0.05, 0.1), segments.fractions)
        assertEquals(10L, segments.difference)
    }

    @Test fun incompleteAndExcessiveCompositionsAreNotNormalizedOrFilledWithZeros() {
        assertNull(memoryCompositionSegments(listOf(20, null, 0), 100))
        assertNull(memoryCompositionSegments(listOf(0, 0), null))
        assertNull(memoryCompositionSegments(listOf(0, 0), 0))
        assertNull(memoryCompositionSegments(listOf(-1, 10), 100))
        assertNull(memoryCompositionSegments(emptyList(), 100))
        assertNull(memoryCompositionSegments(listOf(60, 50), 100))
        assertNull(memoryCompositionSegments(listOf(Long.MAX_VALUE, Long.MAX_VALUE), Long.MAX_VALUE))
    }

    @Test fun compositionsHandleTrueZeroAndLargeMeasurementsWithoutOverflow() {
        val empty = requireNotNull(memoryCompositionSegments(List(7) { 0L }, 100))
        assertTrue(empty.fractions.all { it == 0.0 })
        assertEquals(100L, empty.difference)
        val large = requireNotNull(memoryCompositionSegments(listOf(Long.MAX_VALUE / 2,
            Long.MAX_VALUE - Long.MAX_VALUE / 2), Long.MAX_VALUE))
        assertEquals(0L, large.difference)
        assertEquals(0.5, large.fractions[0], 0.000001)
        assertEquals(0.5, large.fractions[1], 0.000001)
    }

    @Test fun ratiosUseTheGivenTotalAndPreserveMeasuredZero() {
        assertEquals(0.75f, memoryRatio(750, 1000)!!, 0f)
        assertEquals(0.2f, memoryRatio(20, 100)!!, 0f)
        assertEquals(0f, memoryRatio(0, 100)!!, 0f)
        assertEquals(1f, memoryRatio(Long.MAX_VALUE, Long.MAX_VALUE)!!, 0f)
        assertEquals(0.5f, memoryRatio(Long.MAX_VALUE / 2, Long.MAX_VALUE)!!, 0.0001f)
    }

    @Test fun unavailableAndInconsistentRatiosDoNotProduceBars() {
        assertNull(memoryRatio(null, 100))
        assertNull(memoryRatio(20, null))
        assertNull(memoryRatio(0, 0))
        assertNull(memoryRatio(20, -1))
        assertNull(memoryRatio(-1, 100))
        assertNull(memoryRatio(101, 100))
    }

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
