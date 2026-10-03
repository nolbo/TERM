package dev.terminal.app.ui

import dev.terminal.runtime.session.SessionProfile
import dev.terminal.runtime.session.SessionRuntime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AvfSessionLaunchTabsTest {
    private val spec = SessionProfile("avf", "Debian VM", SessionRuntime.AVF, "/bin/bash").resolve()

    @Test fun aPendingTabCarriesTheProfileWithoutClaimingARunningShell() {
        val tab = avfLaunchTab("request-a", spec)
        assertEquals("avf-launch:request-a", tab.id)
        assertEquals(spec, tab.launchSpec)
        assertTrue(tab.pendingLaunch)
        assertEquals("PREPARING", tab.state)
        assertTrue(avfLaunchTabReplacements(listOf(tab)).isEmpty())
    }

    @Test fun completionMatchesRequestIdentityEvenForTheSameProfileAndDifferentOrder() {
        val first = avfLaunchTab("request-a", spec)
        val second = avfLaunchTab("request-b", spec)
        val ready = second.copy(id = "real-b", state = "RUNNING", pendingLaunch = false)
        val replacements = avfLaunchTabReplacements(listOf(ready, first))
        assertEquals(mapOf(second.id to ready.id), replacements)
        assertEquals(ready.id, replacements[second.id])
        assertNull("A different selected tab must keep focus", replacements["native-tab"])
        val order = listOf(first.id, "native-tab", second.id).map { replacements[it] ?: it }
        assertEquals(listOf(first.id, "native-tab", ready.id), order)
    }

    @Test fun ownerSnapshotsRestorePendingFailuresAndCompletedRequestIdentities() {
        val pending = JSONObject().put("id", "avf-launch:a").put("runtime", "AVF")
            .put("title", "Debian VM").put("state", "LAUNCH_FAILED").put("pendingLaunch", true)
            .put("launchRequestId", "a").put("launchError", "permission denied").put("launchSpec", spec.toJson())
        val parsed = parseSessions(JSONArray().put(pending).toString()).single()
        assertTrue(parsed.pendingLaunch)
        assertEquals("permission denied", parsed.launchError)
        assertEquals(spec, parsed.launchSpec)
        val ready = pending.put("id", "real-a").put("pendingLaunch", false).put("state", "RUNNING")
            .put("launchError", JSONObject.NULL)
        val actual = parseSessions(JSONArray().put(ready).toString()).single()
        assertNull(actual.launchError)
        assertEquals("real-a", avfLaunchTabReplacements(listOf(actual))["avf-launch:a"])
    }
}
