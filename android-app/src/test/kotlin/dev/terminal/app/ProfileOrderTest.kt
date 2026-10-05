package dev.terminal.app

import org.junit.Assert.*
import org.junit.Test

class ProfileOrderTest {
    @Test fun defaultOrderIsPreservedUntilEdited() {
        val ids = listOf("native", "debian", "ubuntu", "arch", "avf")
        assertEquals(ids, resolveProfileOrder(ids, parseProfileOrder(null)))
        assertEquals(ids, resolveProfileOrder(ids, parseProfileOrder("broken")))
    }

    @Test fun deletedAndDuplicateIdsAreIgnoredAndNewProfilesAppend() {
        val saved = parseProfileOrder("""["ubuntu","deleted","native","ubuntu",null,42]""")
        assertEquals(listOf("ubuntu", "native", "new"), resolveProfileOrder(listOf("native", "ubuntu", "new"), saved))
    }

    @Test fun omittedProfilesKeepTheirSlotsDuringVisibleReorder() {
        assertEquals(listOf("ubuntu", "hidden-avf", "native", "new"),
            reorderProfileIds(listOf("native", "hidden-avf", "ubuntu", "new"),
                listOf("ubuntu", "unknown", "ubuntu", "native")))
    }

    @Test fun insertionUsesMeasuredHeightsAndStableThresholds() {
        val heights = listOf(72f, 160f, 88f)
        assertEquals(0, profileDropTarget(heights, -10f, 2f))
        assertEquals(0, profileDropTarget(heights, 35f, 2f))
        assertEquals(1, profileDropTarget(heights, 37f, 2f))
        assertEquals(1, profileDropTarget(heights, 153f, 2f))
        assertEquals(2, profileDropTarget(heights, 155f, 2f))
        assertEquals(3, profileDropTarget(heights, 500f, 2f))
        assertEquals(0, profileDropTarget(emptyList(), 50f, 2f))
    }
}
