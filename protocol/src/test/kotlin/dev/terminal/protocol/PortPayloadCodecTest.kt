package dev.terminal.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class PortPayloadCodecTest {
    @Test fun roundTripsListeningPort() {
        val event = PortEvent.Listening(8000, "python")
        assertEquals(event, PortPayloadCodec.decode(PortPayloadCodec.encode(event)))
    }
}
