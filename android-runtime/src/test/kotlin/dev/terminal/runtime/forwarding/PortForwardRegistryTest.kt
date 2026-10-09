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
    private fun registration(id: String = "manual:${UUID.randomUUID()}", port: Any = 8000, env: String? = null, protocol: String = "UNKNOWN") =
        JSONObject().put("id", id).put("name", "Development server").put("port", port)
            .put("environmentId", env ?: JSONObject.NULL).put("applicationProtocol", protocol).toString()

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
    @Test fun manualCrudSurvivesOwnerRestartButNeverClaimsListenerHealthOrOwnership() {
        var saved: String? = null
        val env = Environment.create(SessionRuntime.ROOTLESS_UBUNTU, "Ubuntu")
        val manager = LocalServiceManager(write = { saved = it })
        val id = "manual:${UUID.randomUUID()}"
        assertEquals("", manager.saveManual(registration(id, env = env.id.value), listOf(env)))
        val restarted = LocalServiceManager(read = { saved }, write = { saved = it })
        val row = restarted.registry.services.value.single()
        assertEquals("ROOTLESS", row.runtime); assertEquals(ServiceObservation.REGISTERED_UNVERIFIED, row.observation)
        assertEquals(ForwardingState.NONE, row.forwarding); assertNull(row.browserUrl); assertNull(row.sessionId)
        assertEquals("", restarted.saveManual(registration(id, 9000, protocol = "HTTP"), listOf(env)))
        assertEquals("http://127.0.0.1:9000/", restarted.registry.get(id)!!.browserUrl)
        assertEquals("", restarted.deleteManual(id))
        assertTrue(LocalServiceManager(read = { saved }).registry.services.value.isEmpty())
    }
    @Test fun rejectsInvalidPortsNamesIdsUnknownEnvironmentAndAvfManualAssociation() {
        val manager = LocalServiceManager()
        for (port in listOf<Any>(0, 65536, -1, 12.5, "8000", "javascript:x"))
            assertEquals("INVALID_PORT", manager.saveManual(registration(port = port), emptyList()))
        val valid = JSONObject(registration())
        assertEquals("INVALID_NAME", manager.saveManual(valid.put("name", "x\ny").toString(), emptyList()))
        assertEquals("ENVIRONMENT_UNAVAILABLE", manager.saveManual(registration(env = environment), emptyList()))
        val avf = Environment.create(SessionRuntime.AVF, "AVF")
        assertEquals("INVALID_ENVIRONMENT", manager.saveManual(registration(env = avf.id.value), listOf(avf)))
        assertTrue(manager.registry.services.value.isEmpty())
    }
    @Test fun malformedPersistentRowsAreIsolatedAndCountsBounded() {
        val manager = LocalServiceManager(read = { """{"version":1,"manual":[{},{}]}""" })
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
