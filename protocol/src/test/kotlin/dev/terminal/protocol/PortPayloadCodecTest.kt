package dev.terminal.protocol

import org.junit.Assert.*
import org.junit.Test

class PortPayloadCodecTest {
    @Test fun roundTripsLegacyAndExtendedEvents() {
        for (event in listOf(PortEvent.Listening(8000, "python"), PortEvent.Closed(8000),
            PortEvent.EndpointListening(8000, "127.0.0.1", "개발 서버"), PortEvent.EndpointListening(443, "::1"),
            PortEvent.EndpointClosed(443, "::"), PortEvent.SnapshotBegin, PortEvent.SnapshotEnd)) {
            assertEquals(event, PortPayloadCodec.decode(PortPayloadCodec.encode(event)))
        }
        assertArrayEquals(byteArrayOf(1, 0x1f, 0x40, 0, 0), PortPayloadCodec.encode(PortEvent.Listening(8000)))
        assertArrayEquals(byteArrayOf(2, 0x1f, 0x40), PortPayloadCodec.encode(PortEvent.Closed(8000)))
    }
    @Test fun rejectsMalformedAndUnknownEventsIncludingInvalidClosePorts() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(1), byteArrayOf(2, 0, 0), byteArrayOf(5, 2),
            byteArrayOf(6, 0), byteArrayOf(3, 0, 80, 60), byteArrayOf(127), byteArrayOf(1, 0, 80, 0, 2, 0))) {
            assertTrue(runCatching { PortPayloadCodec.decode(bytes) }.isFailure)
        }
        val valid = PortPayloadCodec.encode(PortEvent.EndpointListening(80, "::1"))
        for (size in 0 until valid.size) assertTrue(runCatching { PortPayloadCodec.decode(valid.copyOf(size)) }.isFailure)
    }
    @Test fun acceptsNumericIpv4AndIpv6ButNeverHostnamesUrisOrZoneIds() {
        for (address in listOf("0.0.0.0", "127.0.0.1", "::", "::1", "::ffff:127.0.0.1", "fe80::abcd"))
            assertTrue(address, PortPayloadCodec.validAddress(address))
        for (address in listOf("localhost", "1.2.3.999", "intent:x", "file:///tmp/x", "::1%wlan0", "::xyz", "1:2", "javascript:1"))
            assertFalse(address, PortPayloadCodec.validAddress(address))
    }
}
