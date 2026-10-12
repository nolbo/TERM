package dev.terminal.runtime.lifecycle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestSessionResizeRequestsTest {
    @Test fun onlyNewestResizeForEachSessionMayReachThePty() {
        val requests = LatestSessionResizeRequests()
        val first = requests.record("native-a", rows = 24, cols = 80)
        val otherSession = requests.record("rootless-b", rows = 30, cols = 100)
        val newest = requests.record("native-a", rows = 40, cols = 120)

        assertFalse(requests.isLatest(first))
        assertTrue(requests.isLatest(newest))
        assertTrue(requests.isLatest(otherSession))
    }

    @Test fun terminatedSessionRejectsQueuedResize() {
        val requests = LatestSessionResizeRequests()
        val queued = requests.record("native-a", rows = 24, cols = 80)

        requests.forget("native-a")

        assertFalse(requests.isLatest(queued))
    }
}
