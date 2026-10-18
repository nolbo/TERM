package dev.terminal.runtime.forwarding

import dev.terminal.protocol.PortEvent
import dev.terminal.runtime.session.Environment
import dev.terminal.runtime.session.SessionRuntime
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.BindException
import java.util.UUID

class PortForwardRegistryTest {
    private val environment = UUID.randomUUID().toString()
    private class Driver : PortForwardDriver {
        val ports = mutableMapOf<String, Int>()
        var failure: Exception? = null
        var binds = 0
        override fun replace(id: String, guestPort: Int, address: String, hostPort: Int, commit: () -> Unit): Result<Unit> = runCatching {
            binds++
            failure?.let { throw it }
            commit()
            ports[id] = hostPort
        }
        override fun stop(id: String) { ports.remove(id) }
        override fun close() { ports.clear() }
    }
    private fun listening(manager: LocalServiceManager, env: String = environment, address: String = "127.0.0.1", port: Int = 8000) {
        manager.event(env, PortEvent.EndpointListening(port, address))
    }
    private fun connect(manager: LocalServiceManager, env: String = environment, driver: Driver = Driver()): Driver {
        manager.attach(env, driver); manager.event(env, PortEvent.SnapshotBegin); return driver
    }
    @Test fun differentEnvironmentsAndAddressesKeepDistinctIdentities() {
        val manager = LocalServiceManager()
        val other = UUID.randomUUID().toString()
        connect(manager); connect(manager, other)
        listening(manager); listening(manager, other); listening(manager, address = "::1")
        assertEquals(3, manager.registry.services.value.map { it.id }.distinct().size)
        assertEquals(2, manager.registry.services.value.map { it.environmentId }.distinct().size)
    }
    @Test fun duplicatesDoNotReplaceWorkingMappingsOrProbeApplications() {
        val manager = LocalServiceManager()
        val driver = connect(manager)
        listening(manager)
        val id = manager.registry.services.value.single().id
        assertEquals("", manager.updateForwarding(id, 18000, "HTTP"))
        listening(manager)
        val row = manager.registry.services.value.single()
        assertEquals(18000, row.hostPort)
        assertEquals(ForwardingState.ACTIVE, row.forwarding)
        assertEquals("http://127.0.0.1:18000/", row.browserUrl)
        assertEquals(2, driver.binds)
    }
    @Test fun bindConflictsAndFailuresNeverAdvertiseAnAccessAddress() {
        val manager = LocalServiceManager()
        val driver = connect(manager)
        driver.failure = BindException("Address already in use")
        listening(manager)
        assertEquals(ForwardingState.CONFLICT, manager.registry.services.value.single().forwarding)
        assertNull(manager.registry.services.value.single().androidAddress)
        driver.failure = IllegalStateException("broken")
        listening(manager)
        assertEquals(ForwardingState.FAILED, manager.registry.services.value.single().forwarding)
    }
    @Test fun failedPortChangePreservesPreviousListenerAndReportsAttemptedPort() {
        val manager = LocalServiceManager()
        val driver = connect(manager); listening(manager)
        val id = manager.registry.services.value.single().id
        driver.failure = BindException("Address already in use")
        assertEquals("PORT_CONFLICT", manager.updateForwarding(id, 18000, "HTTPS"))
        val row = manager.registry.get(id)!!
        assertEquals(ForwardingState.ACTIVE, row.forwarding)
        assertEquals(8000, row.hostPort); assertEquals(18000, row.attemptedHostPort)
        assertEquals(8000, driver.ports[id]); assertNull(row.browserUrl)
    }
    @Test fun persistenceFailurePreservesWorkingForwardAndProtocol() {
        val manager = LocalServiceManager(write = { error("disk full") })
        val driver = connect(manager); listening(manager)
        val id = manager.registry.services.value.single().id
        assertEquals("STORAGE_FAILED", manager.updateForwarding(id, 18000, "HTTP"))
        assertEquals(8000, driver.ports[id]); assertEquals(8000, manager.registry.get(id)!!.hostPort)
        assertNull(manager.registry.get(id)!!.browserUrl)
    }
    @Test fun endSnapshotClosesMissingListenersAndDisconnectOnlyAffectsItsEnvironment() {
        val manager = LocalServiceManager()
        val other = UUID.randomUUID().toString()
        val first = connect(manager); val second = connect(manager, other)
        listening(manager); listening(manager, other)
        manager.event(environment, PortEvent.SnapshotEnd)
        manager.event(environment, PortEvent.SnapshotBegin)
        manager.event(environment, PortEvent.SnapshotEnd)
        assertEquals(ServiceObservation.CLOSED, manager.registry.services.value.single { it.environmentId == environment }.observation)
        assertTrue(first.ports.isEmpty()); assertFalse(second.ports.isEmpty())
        manager.disconnected(environment)
        assertEquals(ForwardingState.ACTIVE, manager.registry.services.value.single { it.environmentId == other }.forwarding)
    }
    @Test fun reconnectUsesNewSnapshotAndRetainsExplicitMapping() {
        var saved: String? = null
        val manager = LocalServiceManager(write = { saved = it })
        val driver = connect(manager); listening(manager)
        val id = manager.registry.services.value.single().id
        assertEquals("", manager.updateForwarding(id, 18000, "HTTPS"))
        manager.disconnected(environment)
        assertTrue(driver.ports.isEmpty()); assertNull(manager.registry.get(id)!!.androidAddress)
        manager.event(environment, PortEvent.SnapshotBegin); listening(manager); manager.event(environment, PortEvent.SnapshotEnd)
        assertEquals(18000, driver.ports[id])
        val restarted = LocalServiceManager(read = { saved })
        connect(restarted); listening(restarted)
        assertEquals("https://127.0.0.1:18000/", restarted.registry.get(id)!!.browserUrl)
    }
    @Test fun legacyAgentReportsUnknownAddressesAndUnavailableProxyWithoutStoppingRuntime() {
        val manager = LocalServiceManager()
        manager.event(environment, PortEvent.Listening(8000, "old"))
        val row = manager.registry.services.value.single()
        assertNull(row.sourceAddress); assertNull(row.androidAddress)
        assertEquals(ForwardingState.UNAVAILABLE, row.forwarding)
        manager.event(environment, PortEvent.Closed(8000))
        assertEquals(ServiceObservation.CLOSED, manager.registry.get(row.id)!!.observation)
        manager.event(environment, PortEvent.SnapshotBegin); listening(manager)
        manager.event(environment, PortEvent.Listening(8000))
        assertEquals(1, manager.registry.services.value.size)
    }
    @Test fun retiredManualRegistrationsAreIgnoredAndAvfMappingsSurviveUpgrade() {
        var saved: String? = null
        val id = LocalService.avfId(environment, "127.0.0.1", 8000)
        val legacy = JSONObject().put("version", 1)
            .put("manual", org.json.JSONArray("""[{"source":"MANUAL","runtime":"NATIVE"},{"source":"MANUAL","runtime":"ROOTLESS"}]"""))
            .put("mappings", JSONObject().put(id, JSONObject().put("port", 18000).put("protocol", "HTTPS"))).toString()
        val manager = LocalServiceManager(read = { legacy }, write = { saved = it })
        assertTrue(manager.registry.services.value.isEmpty())
        assertNull(manager.storageError)
        connect(manager); listening(manager)
        assertEquals("https://127.0.0.1:18000/", manager.registry.get(id)!!.browserUrl)
        assertEquals("", manager.updateForwarding(id, 19000, "HTTP"))
        assertFalse(JSONObject(requireNotNull(saved)).has("manual"))
        val restarted = LocalServiceManager(read = { saved })
        connect(restarted); listening(restarted)
        assertEquals("http://127.0.0.1:19000/", restarted.registry.get(id)!!.browserUrl)
    }
    @Test fun snapshotAndRegistryAcceptOnlyAvfEnvironments() {
        val manager = LocalServiceManager()
        val avf = Environment.create(SessionRuntime.AVF, "AVF")
        val environments = listOf(avf, Environment.create(SessionRuntime.NATIVE, "Native"),
            Environment.create(SessionRuntime.ROOTLESS_UBUNTU, "Ubuntu"))
        val rows = JSONObject(manager.snapshot(environments)).getJSONArray("environments")
        assertEquals(1, rows.length())
        assertEquals(avf.id.value, rows.getJSONObject(0).getString("id"))
        connect(manager); listening(manager)
        val observed = manager.registry.services.value.single()
        for (runtime in listOf("NATIVE", "ROOTLESS", "SHARED_ANDROID"))
            assertTrue(runCatching { manager.registry.put(observed.copy(runtime = runtime)) }.isFailure)
        assertEquals(listOf(observed), manager.registry.services.value)
    }
    @Test fun malformedPersistentRowsAreIsolatedAndCountsBounded() {
        val manager = LocalServiceManager(read = { """{"version":1,"mappings":{"invalid":{}}}""" })
        assertEquals("STORAGE_CORRUPT", manager.storageError)
        connect(manager)
        repeat(140) { listening(manager, port = 8000 + it) }
        assertEquals(128, manager.registry.services.value.size)
    }
    @Test fun connectionFailureDoesNotPretendThatTheBoundProxyOrGuestListenerStopped() {
        val manager = LocalServiceManager(); connect(manager); listening(manager)
        val id = manager.registry.services.value.single().id
        manager.forwardingFailure(environment, id, 8000, false)
        assertEquals(ForwardingState.ACTIVE, manager.registry.get(id)!!.forwarding)
        assertEquals("GUEST_CONNECT_FAILED", manager.registry.get(id)!!.error)
        manager.event(environment, PortEvent.EndpointClosed(8000, "127.0.0.1"))
        assertNull(manager.registry.get(id)!!.androidAddress)
    }
}
