package dev.terminal.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.nio.ByteBuffer
import org.junit.Test

class ProtocolTest {
    @Test
    fun streamReadsFragmentedFramesExactlyOnceIntoTheirOwnedPayload() {
        val first = ProtocolFrame(service = Service.PORT, kind = FrameKind.STREAM, flags = 0xa123u,
            requestId = Long.MAX_VALUE, payload = ByteArray(65_537) { (it * 17).toByte() })
        val second = ProtocolFrame(service = Service.BRIDGE, kind = FrameKind.RESPONSE, requestId = 7)
        val wire = Protocol.encode(first) + Protocol.encode(second)
        val targets = mutableListOf<ByteArray>()
        val raw = object : ByteArrayInputStream(wire) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                targets += bytes
                return super.read(bytes, offset, minOf(length, 7))
            }
        }
        val input = DataInputStream(raw)
        val decoded = Protocol.read(input)
        assertSame("The payload read from the stream must not be cloned", targets.last(), decoded.payload)
        assertTrue(targets.all { it.size == Protocol.HEADER_SIZE || it === decoded.payload })
        assertArrayEquals(Protocol.encode(first), Protocol.encode(decoded))
        assertArrayEquals(Protocol.encode(second), Protocol.encode(Protocol.read(input)))
        assertEquals(0, raw.available())
        decoded.payload.fill(0)
        assertArrayEquals(first.payload, wire.copyOfRange(Protocol.HEADER_SIZE, Protocol.HEADER_SIZE + first.payload.size))
    }

    @Test
    fun streamAndArrayDecodersRejectInvalidHeadersBeforeReadingPayloads() {
        val header = Protocol.encode(ProtocolFrame(service = Service.SESSION, kind = FrameKind.REQUEST, requestId = 1))
        val invalid = listOf(
            header.copyOf().also { it[0] = 0 },
            header.copyOf().also { it[5] = 2 },
            header.copyOf().also { it[6] = 0 },
            header.copyOf().also { it[7] = 0 },
            header.copyOf().also { it[10] = 1 },
        ) + listOf(-1, Protocol.MAX_PAYLOAD_BYTES + 1, Int.MAX_VALUE).map { length ->
            header.copyOf().also { ByteBuffer.wrap(it).putInt(20, length) }
        }
        for (bytes in invalid) {
            val source = object : ByteArrayInputStream(bytes) {
                override fun read(target: ByteArray, offset: Int, length: Int): Int {
                    check(available() > 0) { "An invalid header must not trigger a payload read" }
                    return super.read(target, offset, length)
                }
            }
            assertThrows(IllegalArgumentException::class.java) { Protocol.decode(bytes) }
            assertThrows(IllegalArgumentException::class.java) { Protocol.read(DataInputStream(source)) }
        }
    }

    @Test
    fun streamTruncationAndArrayLengthMismatchAreStillRejected() {
        val wire = Protocol.encode(ProtocolFrame(service = Service.SESSION, kind = FrameKind.STREAM,
            requestId = 0, payload = byteArrayOf(1, 2, 3, 4, 5)))
        for (length in wire.indices) {
            assertThrows(EOFException::class.java) { Protocol.read(DataInputStream(wire.copyOf(length).inputStream())) }
        }
        assertThrows(IllegalArgumentException::class.java) { Protocol.decode(wire + byteArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { Protocol.decode(wire.copyOf(wire.size - 1)) }
    }

    @Test
    fun streamAcceptsTheMaximumPayloadWithoutReadingIntoTheNextFrame() {
        val bytes = ByteArray(Protocol.MAX_PAYLOAD_BYTES) { it.toByte() }
        val wire = Protocol.encode(ProtocolFrame(service = Service.SESSION, kind = FrameKind.STREAM,
            requestId = 0, payload = bytes))
        val input = DataInputStream((wire + byteArrayOf(99)).inputStream())
        assertArrayEquals(bytes, Protocol.read(input).payload)
        assertEquals(99, input.read())
    }

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
