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
        val manual = LocalService("manual:one", null, "SHARED_ANDROID", "Web", 8443, "127.0.0.1", ServiceSource.MANUAL,
            ServiceObservation.REGISTERED_UNVERIFIED, hostPort = 8443, applicationProtocol = ApplicationProtocol.HTTPS)
        val encoded = JSONObject().put("services", JSONArray(listOf(discovered.toJson(), manual.toJson())))
            .put("environments", JSONArray()).put("links", JSONObject()).put("storageError", JSONObject.NULL).toString()
        val state = LocalServicesSnapshot.parse(encoded)
        assertNull(state.services[0].androidAddress); assertNull(state.services[0].browserUrl)
        assertEquals("https://127.0.0.1:8443/", state.services[1].browserUrl)
        assertEquals(ServiceObservation.REGISTERED_UNVERIFIED, state.services[1].observation)
        assertNull(manual.copy(applicationProtocol = ApplicationProtocol.UNKNOWN).browserUrl)
    }
    @Test fun unavailableOrMalformedSnapshotsAreNeverEmptySuccess() {
        for (encoded in listOf("{}", "broken", """{"services":[],"environments":[]}"""))
            assertTrue(runCatching { LocalServicesSnapshot.parse(encoded) }.isFailure)
    }
}
