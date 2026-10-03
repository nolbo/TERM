package dev.terminal.runtime.lifecycle

import dev.terminal.runtime.session.SessionProfile
import dev.terminal.runtime.session.SessionRuntime
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PendingAvfSessionLaunchesTest {
    private fun launch() = PendingAvfSessionLaunches.Launch(UUID.randomUUID().toString(),
        SessionProfile("avf", "Debian VM", SessionRuntime.AVF, "/bin/bash").resolve())

    @Test fun concurrentRequestsForOneProfileHaveDistinctTabsAndCannotStartTwice() {
        val requests = PendingAvfSessionLaunches()
        val first = launch()
        val second = launch()
        assertTrue(requests.begin(first))
        assertFalse(requests.begin(first))
        assertTrue(requests.begin(second))
        val tabs = JSONArray(requests.project("[]"))
        assertEquals(2, tabs.length())
        assertEquals(first.tabId, tabs.getJSONObject(0).getString("id"))
        assertEquals(second.tabId, tabs.getJSONObject(1).getString("id"))
        assertTrue(tabs.getJSONObject(0).getBoolean("pendingLaunch"))
        assertEquals("PREPARING", tabs.getJSONObject(0).getString("state"))
        assertFalse(requests.dismiss(first.tabId))
    }

    @Test fun failuresRemainVisibleAndDismissalOnlyRemovesThatRequest() {
        val requests = PendingAvfSessionLaunches()
        val first = launch()
        val second = launch()
        requests.begin(first)
        requests.begin(second)
        requests.fail(first, "image damaged")
        val replay = requests.project(requests.project("[]"))
        assertEquals(2, JSONArray(replay).length())
        assertEquals("image damaged", JSONArray(replay).getJSONObject(0).getString("launchError"))
        assertTrue(requests.dismiss(first.tabId))
        assertEquals(second.tabId, JSONArray(requests.project("[]")).getJSONObject(0).getString("id"))
    }

    @Test fun aConfirmedSessionReplacesItsRequestWithoutRemovingAnotherIdenticalProfile() {
        val requests = PendingAvfSessionLaunches()
        val first = launch()
        val second = launch()
        requests.begin(first)
        requests.begin(second)
        val real = JSONArray().put(JSONObject().put("id", "real-avf-session")
            .put("launchRequestId", second.requestId))
        val projected = JSONArray(requests.project(real.toString()))
        assertEquals(2, projected.length())
        assertEquals(first.tabId, projected.getJSONObject(1).getString("id"))
        // The completed request must not reappear when its real session is later closed.
        assertEquals(1, JSONArray(requests.project("[]")).length())
    }

    @Test fun requestValidationRejectsOtherRuntimesAndMalformedIdentities() {
        val requests = PendingAvfSessionLaunches()
        assertThrows(IllegalArgumentException::class.java) {
            requests.begin(launch().copy(requestId = "bad-id"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            requests.begin(launch().copy(spec = SessionProfile("native", "Native", SessionRuntime.NATIVE, "/system/bin/sh").resolve()))
        }
    }
}
