package dev.terminal.runtime.forwarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortForwardRegistryTest {
    @Test
    fun replacesDuplicatePortAndKeepsPortsSorted() {
        val registry = PortForwardRegistry()

        registry.reportListening(8080, "first")
        registry.reportListening(3000, "dev")
        registry.reportListening(8080, "updated")

        val ports = registry.ports.value
        assertEquals(listOf(3000, 8080), ports.map { it.guestPort })
        assertEquals("updated", ports.single { it.guestPort == 8080 }.label)
    }

    @Test
    fun rejectsInvalidPortsAndRemovesClosedPort() {
        val registry = PortForwardRegistry()

        assertTrue(registry.reportListening(0).isFailure)
        registry.reportListening(8000)
        registry.remove(8000)

        assertTrue(registry.ports.value.isEmpty())
    }
}
