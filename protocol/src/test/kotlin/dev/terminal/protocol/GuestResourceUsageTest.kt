package dev.terminal.protocol

import org.junit.Assert.*
import org.junit.Test

class GuestResourceUsageTest {
    @Test fun fixedResourcePayloadRoundTripsAndRejectsInconsistentMetrics() {
        assertEquals(SessionRequest.ResourceUsage, SessionPayloadCodec.decode(SessionPayloadCodec.encode(SessionRequest.ResourceUsage)))
        val usage = GuestResourceUsage(2048, 512, 1024, 768, 2, 250, -1)
        val response = SessionResponse.Resources(usage)
        val bytes = SessionResponseCodec.encode(response)
        assertEquals(45, bytes.size)
        assertEquals(response, SessionResponseCodec.decode(bytes))
        assertThrows(IllegalArgumentException::class.java) { GuestResourceUsage(1, 2, 0, 0, 1, 0, -1) }
        assertThrows(IllegalArgumentException::class.java) { GuestResourceUsage(1, 0, 0, 1, 1, 0, -1) }
        assertThrows(IllegalArgumentException::class.java) { GuestResourceUsage(1, 0, 0, 0, 0, 0, -1) }
        assertThrows(IllegalArgumentException::class.java) { SessionResponseCodec.decode(bytes.copyOf(44)) }
    }
}
