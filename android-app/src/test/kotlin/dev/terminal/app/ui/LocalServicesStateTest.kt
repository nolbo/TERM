package dev.terminal.app.ui

import dev.terminal.runtime.forwarding.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalServicesStateTest {
    @Test fun snapshotSeparatesForwardingFromDiscoveryAndOnlyBuildsSafeExplicitBrowserUrls() {
        val discovered = LocalService("avf:one", "vm-one", "AVF", null, 8000, "::1", ServiceSource.AVF_AGENT,
            ServiceObservation.LISTENING, ForwardingState.CONFLICT, 18000)
        val forwarded = discovered.copy(id = "avf:two", name = "Web", forwarding = ForwardingState.ACTIVE,
            hostPort = 8443, applicationProtocol = ApplicationProtocol.HTTPS)
        val encoded = JSONObject().put("services", JSONArray(listOf(discovered.toJson(), forwarded.toJson())))
            .put("environments", JSONArray()).put("links", JSONObject()).put("storageError", JSONObject.NULL).toString()
        val state = LocalServicesSnapshot.parse(encoded)
        assertNull(state.services[0].androidAddress); assertNull(state.services[0].browserUrl)
        assertEquals("https://127.0.0.1:8443/", state.services[1].browserUrl)
        assertEquals(ServiceObservation.LISTENING, state.services[1].observation)
        assertNull(forwarded.copy(applicationProtocol = ApplicationProtocol.UNKNOWN).browserUrl)
        val native = JSONObject(encoded).apply {
            getJSONArray("services").getJSONObject(0).put("runtime", "NATIVE")
        }
        assertTrue(runCatching { LocalServicesSnapshot.parse(native.toString()) }.isFailure)
    }
    @Test fun unavailableOrMalformedSnapshotsAreNeverEmptySuccess() {
        for (encoded in listOf("{}", "broken", """{"services":[],"environments":[]}"""))
            assertTrue(runCatching { LocalServicesSnapshot.parse(encoded) }.isFailure)
    }
}
