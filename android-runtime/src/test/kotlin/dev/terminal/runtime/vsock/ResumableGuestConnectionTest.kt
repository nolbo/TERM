package dev.terminal.runtime.vsock

import dev.terminal.protocol.FrameKind
import dev.terminal.protocol.Protocol
import dev.terminal.protocol.ProtocolFrame
import dev.terminal.protocol.ReplayByteStream
import dev.terminal.protocol.Service
import dev.terminal.runtime.api.GuestByteStream
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ResumableGuestConnectionTest {
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
                payload = byteArrayOf(1))
            val last = first.copy(payload = byteArrayOf(2))
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
}
