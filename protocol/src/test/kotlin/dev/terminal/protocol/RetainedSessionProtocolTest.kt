package dev.terminal.protocol

import org.junit.Assert.assertThrows
import org.junit.Test

class RetainedSessionProtocolTest {
    @Test fun hostHistoryCannotBecomeAGuestSessionInEitherWireDirection() {
        val session = SessionDescriptor("history", "History", "/bin/sh", "/", 1, 24u, 80u, SessionStatus.INTERRUPTED)
        assertThrows(IllegalArgumentException::class.java) { SessionResponseCodec.encode(SessionResponse.Attached(session)) }
        val bytes = SessionResponseCodec.encode(SessionResponse.Attached(session.copy(state = SessionStatus.RUNNING)))
        bytes[bytes.size - 5] = SessionStatus.INTERRUPTED.ordinal.toByte()
        assertThrows(IllegalArgumentException::class.java) { SessionResponseCodec.decode(bytes) }
    }
}
