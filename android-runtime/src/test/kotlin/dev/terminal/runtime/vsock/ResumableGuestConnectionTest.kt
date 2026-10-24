package dev.terminal.runtime.vsock

import dev.terminal.protocol.FrameKind
import dev.terminal.protocol.Protocol
import dev.terminal.protocol.ProtocolFrame
import dev.terminal.protocol.ReplayByteStream
import dev.terminal.protocol.Service
import dev.terminal.runtime.api.GuestByteStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ResumableGuestConnectionTest {
    private data class Diagnostic(val event: String, val code: String?, val error: Throwable?)

    private fun guest(input: InputStream, output: OutputStream = ByteArrayOutputStream(), close: () -> Unit = {}) =
        object : GuestByteStream {
            override val input = input
            override val output = output
            override fun close() = close()
        }

    @Test fun attachmentDiagnosticsDistinguishPlatformOwnerAndReplayFailuresWithoutLeakingMessages() {
        fun replayReply(magic: Int = 0x54525332, window: Int = 64, received: Long = 0) = ByteArrayOutputStream().apply {
            DataOutputStream(this).apply { writeByte(0); writeInt(magic); writeInt(window); writeLong(received); writeLong(0) }
        }.toByteArray()
        val privateDetail = "private-path/token/terminal-content"
        data class Case(val phaseAndReason: String, val reply: String, val error: Class<out Exception>,
            val connect: () -> GuestByteStream)
        val cases = listOf(
            Case("connect.error", "none", IllegalArgumentException::class.java) { throw IllegalArgumentException(privateDetail) },
            Case("owner_write.error", "none", IOException::class.java) {
                guest(ByteArrayInputStream(byteArrayOf()), object : OutputStream() {
                    override fun write(value: Int) { throw IOException(privateDetail) }
                })
            },
            Case("owner_status.error", "none", IOException::class.java) {
                guest(object : InputStream() { override fun read(): Int = throw IOException(privateDetail) })
            },
            Case("owner_status.error", "-1", IOException::class.java) { guest(ByteArrayInputStream(byteArrayOf())) },
            Case("owner_status.error", "1", IOException::class.java) { guest(ByteArrayInputStream(byteArrayOf(1))) },
            Case("owner_status.error", "2", IllegalArgumentException::class.java) { guest(ByteArrayInputStream(byteArrayOf(2))) },
            Case("replay.handshake.magic", "0", ReplayByteStream.HandshakeException::class.java) {
                guest(ByteArrayInputStream(replayReply(magic = 0)))
            },
            Case("replay.handshake.window", "0", ReplayByteStream.HandshakeException::class.java) {
                guest(ByteArrayInputStream(replayReply(window = 0)))
            },
            Case("replay.handshake.offsets", "0", ReplayByteStream.HandshakeException::class.java) {
                guest(ByteArrayInputStream(replayReply(received = 1)))
            },
            Case("replay.error", "0", java.io.EOFException::class.java) { guest(ByteArrayInputStream(byteArrayOf(0))) },
        )
        for (case in cases) {
            val diagnostics = CopyOnWriteArrayList<Diagnostic>()
            val failure = assertThrows(case.error) {
                ResumableGuestConnection(0, case.connect, report = { event, code, error ->
                    diagnostics += Diagnostic(event, code, error)
                    throw IllegalStateException("A broken diagnostic observer must not replace the real failure")
                })
            }
            val diagnostic = diagnostics.single()
            assertEquals("session.attach.failed", diagnostic.event)
            assertSame(failure, diagnostic.error)
            assertTrue(requireNotNull(diagnostic.code).matches(
                Regex(Regex.escape("${case.phaseAndReason}.reply_${case.reply}.attempt_0.elapsed_ms_") + "[0-9]+")))
            val line = dev.terminal.runtime.lifecycle.connectionLogLine(0, 0, 1, diagnostic.event, "test-owner",
                diagnostic.code, diagnostic.error?.javaClass?.simpleName)
            assertTrue(line.contains("code=${diagnostic.code}"))
            assertFalse(line.contains("redacted"))
            assertFalse(line.contains(privateDetail))
        }
    }

    @Test(timeout = 10_000) fun recoveryRecordsTheFirstLinkCauseAndLostGenerationWithoutRetryingOrChangingOwner() = runBlocking {
        val listener = ServerSocket(0)
        val ready = CountDownLatch(1)
        val breakLink = CountDownLatch(1)
        val serverFailure = AtomicReference<Throwable>()
        val attempts = AtomicInteger()
        val diagnostics = CopyOnWriteArrayList<Diagnostic>()
        val server = thread(isDaemon = true) {
            try {
                var token: Pair<Long, Long>? = null
                repeat(2) { index ->
                    listener.accept().use { raw ->
                        val input = DataInputStream(raw.getInputStream())
                        val output = DataOutputStream(raw.getOutputStream())
                        assertEquals(0x54535231, input.readInt())
                        val receivedToken = input.readLong() to input.readLong()
                        assertEquals(if (index == 0) 1 else 0, input.readUnsignedByte())
                        if (index == 0) {
                            token = receivedToken
                            output.writeByte(0); output.flush()
                            ByteArray(24).also(input::readFully)
                            output.writeInt(0x54525332); output.writeInt(64)
                            output.writeLong(0); output.writeLong(0); output.flush()
                            ready.countDown()
                            assertTrue(breakLink.await(2, TimeUnit.SECONDS))
                            output.writeByte(2); output.writeLong(Long.MAX_VALUE); output.writeInt(0); output.flush()
                        } else {
                            assertEquals(token, receivedToken)
                            output.writeByte(2); output.flush()
                        }
                    }
                }
            } catch (failure: Throwable) { serverFailure.set(failure); ready.countDown() }
        }
        val client = ResumableGuestConnection(0, connect = {
            attempts.incrementAndGet()
            val socket = Socket("127.0.0.1", listener.localPort)
            guest(socket.getInputStream(), socket.getOutputStream()) { socket.close() }
        }, report = { event, code, error -> diagnostics += Diagnostic(event, code, error) })
        try {
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            serverFailure.get()?.let { throw it }
            val frames = async { withTimeout(5_000) { client.incomingFrames().toList() } }
            // A malformed ACK breaks the raw link; the resume reply then reports a lost generation.
            breakLink.countDown()
            assertTrue(frames.await().isEmpty())
            server.join(1_000)
            serverFailure.get()?.let { throw it }
            val reconnecting = diagnostics.single { it.event == "session.link.reconnecting" }
            assertEquals("frame.ack", reconnecting.code)
            assertTrue(reconnecting.error is IllegalArgumentException)
            val attaching = diagnostics.single { it.event == "session.attach.failed" }
            assertTrue(requireNotNull(attaching.code).startsWith("owner_status.error.reply_2.attempt_1.elapsed_ms_"))
            assertTrue(diagnostics.single { it.event == "session.link.failed" }.error is IllegalArgumentException)
            assertFalse(diagnostics.any { it.event == "session.link.restored" })
            assertEquals(2, attempts.get())
        } finally { breakLink.countDown(); client.close(); listener.close(); server.join(1_000) }
    }

    @Test(timeout = 15_000) fun frameConsumerAndInputSurviveRawSocketReplacement() = runBlocking {
        val listener = ServerSocket(0)
        val peer = ReplayByteStream()
        val current = AtomicReference<Socket>()
        val second = CountDownLatch(1)
        val firstReady = CountDownLatch(1)
        val error = AtomicReference<Throwable>()
        val server = thread(isDaemon = true) {
            try {
                repeat(2) { index ->
                    val raw = listener.accept()
                    current.set(raw)
                    val input = DataInputStream(raw.getInputStream())
                    assertEquals(0x54535231, input.readInt())
                    input.readLong(); input.readLong()
                    assertEquals(if (index == 0) 1 else 0, input.readUnsignedByte())
                    raw.getOutputStream().write(0)
                    peer.attach(input, raw.getOutputStream()) { raw.close() }
                    if (index == 0) firstReady.countDown()
                    if (index == 1) second.countDown()
                }
            } catch (failure: Throwable) { error.set(failure); second.countDown() }
        }
        val client = ResumableGuestConnection(0, connect = {
            val raw = Socket("127.0.0.1", listener.localPort)
            object : GuestByteStream {
                override val input = raw.getInputStream()
                override val output = raw.getOutputStream()
                override fun close() { raw.close() }
            }
        })
        try {
            assertTrue(firstReady.await(2, TimeUnit.SECONDS))
            val frames = async { withTimeout(10_000) { client.incomingFrames().take(2).toList() } }
            val first = ProtocolFrame(service = Service.SESSION, kind = FrameKind.STREAM, requestId = 0,
                payload = ByteArray(100_000) { it.toByte() })
            val last = first.copy(payload = ByteArray(65_537) { (it * 17).toByte() })
            peer.output.write(Protocol.encode(first))
            current.get().close()
            peer.output.write(Protocol.encode(last))
            assertTrue(second.await(5, TimeUnit.SECONDS))
            error.get()?.let { throw it }
            val actual = frames.await()
            assertEquals(2, actual.size)
            assertArrayEquals(first.payload, actual[0].payload)
            assertArrayEquals(last.payload, actual[1].payload)
        } finally {
            client.close(); peer.close(); listener.close(); server.join(1_000)
        }
    }

    @Test(timeout = 10_000) fun aStuckReplacementConnectCannotOutliveRecoveryOrLeakItsLateSocket() = runBlocking {
        val listener = ServerSocket(0)
        val peer = ReplayByteStream()
        val current = AtomicReference<Socket>()
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val lateClosed = CountDownLatch(1)
        val attempts = AtomicInteger()
        val diagnostics = CopyOnWriteArrayList<Diagnostic>()
        val server = thread(isDaemon = true) {
            runCatching {
                val raw = listener.accept(); current.set(raw)
                val input = DataInputStream(raw.getInputStream())
                input.readInt(); input.readLong(); input.readLong(); input.readByte()
                raw.getOutputStream().write(0)
                peer.attach(input, raw.getOutputStream()) { raw.close() }
                ready.countDown()
            }
        }
        val client = ResumableGuestConnection(0, connect = {
            if (attempts.incrementAndGet() == 1) {
                val socket = Socket("127.0.0.1", listener.localPort)
                object : GuestByteStream {
                    override val input = socket.getInputStream()
                    override val output = socket.getOutputStream()
                    override fun close() { socket.close() }
                }
            } else {
                release.await() // Models a platform Binder call that cannot be cancelled.
                object : GuestByteStream {
                    override val input = ByteArrayInputStream(byteArrayOf())
                    override val output = ByteArrayOutputStream()
                    override fun close() { lateClosed.countDown() }
                }
            }
        }, report = { event, code, error -> diagnostics += Diagnostic(event, code, error) },
            recoveryTimeoutMillis = 300, handshakeTimeoutMillis = 200)
        try {
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            assertEquals(500, client.requestTimeoutMillis)
            val frames = async { withTimeout(2_000) { client.incomingFrames().toList() } }
            current.get().close()
            assertTrue(frames.await().isEmpty())
            assertEquals(1, diagnostics.count { it.event == "session.link.failed" })
            assertTrue(diagnostics.any { it.event == "session.attach.failed" &&
                it.code?.startsWith("connect.timeout.reply_none.attempt_") == true })
            release.countDown()
            assertTrue(lateClosed.await(2, TimeUnit.SECONDS))
        } finally {
            release.countDown(); client.close(); peer.close(); listener.close(); server.join(1_000)
        }
    }
}
