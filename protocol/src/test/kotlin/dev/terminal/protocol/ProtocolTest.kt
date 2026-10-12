package dev.terminal.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtocolTest {
    @Test
    fun frameRoundTripsWithoutChangingItsRequestIdentity() {
        val frame = ProtocolFrame(
            service = Service.SESSION,
            kind = FrameKind.REQUEST,
            requestId = 42,
            payload = "attach:alpha".encodeToByteArray(),
        )

        val decoded = Protocol.decode(Protocol.encode(frame))

        assertEquals(frame.version, decoded.version)
        assertEquals(frame.service, decoded.service)
        assertEquals(frame.kind, decoded.kind)
        assertEquals(frame.requestId, decoded.requestId)
        assertEquals("attach:alpha", decoded.payload.decodeToString())
    }

    @Test
    fun rejectsAnInvalidMagic() {
        val bytes = Protocol.encode(
            ProtocolFrame(service = Service.SESSION, kind = FrameKind.REQUEST, requestId = 1),
        )
        bytes[0] = 0

        assertThrows(IllegalArgumentException::class.java) { Protocol.decode(bytes) }
    }

    @Test
    fun sessionCreatePayloadPreservesItsShellEnvironmentAndDimensions() {
        val request = SessionRequest.Create(
            shell = "/bin/bash",
            cwd = "/home/droid",
            env = mapOf("LANG" to "C.UTF-8", "TERM" to "xterm-256color"),
            rows = 24u,
            cols = 80u,
        )

        val decoded = SessionPayloadCodec.decode(SessionPayloadCodec.encode(request))

        assertEquals(request, decoded)
    }

    @Test
    fun sessionInputRetainsBinaryTerminalBytes() {
        val request = SessionRequest.Write("session-1", byteArrayOf(0x1b, '['.code.toByte(), 'A'.code.toByte(), 0))

        val decoded = SessionPayloadCodec.decode(SessionPayloadCodec.encode(request)) as SessionRequest.Write

        assertEquals(request.sessionId, decoded.sessionId)
        org.junit.Assert.assertArrayEquals(request.bytes, decoded.bytes)
    }

    @Test
    fun sessionListResponseRetainsLiveSessionMetadata() {
        val response = SessionResponse.Listed(listOf(
            SessionDescriptor("session-1", "Build", "/bin/bash", "/home/droid", 100, 24u, 80u, SessionStatus.RUNNING),
        ))

        assertEquals(response, SessionResponseCodec.decode(SessionResponseCodec.encode(response)))
    }

    @Test
    fun typedErrorResponseRoundTrips() {
        val error = ProtocolErrorPayload(ProtocolErrorCode.NOT_FOUND, "session not found")

        assertEquals(error, ProtocolErrorCodec.decode(ProtocolErrorCodec.encode(error)))
    }

    @Test
    fun sessionOutputEventRetainsTerminalEscapeBytes() {
        val event = SessionEvent.Output("session-1", byteArrayOf(0x1b, '['.code.toByte(), '2'.code.toByte(), 'J'.code.toByte()))

        val decoded = SessionEventCodec.decode(SessionEventCodec.encode(event)) as SessionEvent.Output

        assertEquals(event.sessionId, decoded.sessionId)
        org.junit.Assert.assertArrayEquals(event.bytes, decoded.bytes)
    }
}
