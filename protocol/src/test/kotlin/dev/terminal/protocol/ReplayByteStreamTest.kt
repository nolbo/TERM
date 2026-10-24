package dev.terminal.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ReplayByteStreamTest {
    @Test fun handshakeRejectionsIdentifyMagicWindowAndOffsetsWithoutChangingFatalClassification() {
        data class Case(val magic: Int, val window: Int, val received: Long, val reason: ReplayByteStream.FailureReason)
        for ((magic, window, received, reason) in listOf(
            Case(0, 64, 0, ReplayByteStream.FailureReason.HANDSHAKE_MAGIC),
            Case(0x54525332, 0, 0, ReplayByteStream.FailureReason.HANDSHAKE_WINDOW),
            Case(0x54525332, 64, 1, ReplayByteStream.FailureReason.HANDSHAKE_OFFSETS),
        )) {
            val input = ByteArrayOutputStream().apply {
                DataOutputStream(this).apply {
                    writeInt(magic); writeInt(window)
                    writeLong(received); writeLong(0)
                }
            }.toByteArray()
            val replay = ReplayByteStream(64)
            var socketClosed = false
            try {
                val failure = assertThrows(ReplayByteStream.HandshakeException::class.java) {
                    replay.attach(ByteArrayInputStream(input), ByteArrayOutputStream()) { socketClosed = true }
                }
                assertEquals(reason, failure.reason)
                assertTrue(failure is IllegalArgumentException)
                assertTrue(socketClosed)
                assertFalse(replay.connected)
                assertFalse(replay.ownerClosed)
            } finally { replay.close() }
        }
    }

    @Test(timeout = 5_000) fun malformedFramesAndEofRetainTheFirstLinkCauseWithoutClosingTheOwner() {
        data class Case(val kind: Int, val position: Long, val length: Int, val payload: ByteArray,
            val reason: ReplayByteStream.FailureReason)
        val cases = listOf(
            Case(1, -1, 0, byteArrayOf(), ReplayByteStream.FailureReason.FRAME_BOUNDS),
            Case(1, 0, ReplayByteStream.CHUNK + 1, byteArrayOf(), ReplayByteStream.FailureReason.FRAME_BOUNDS),
            Case(1, 0, 0, byteArrayOf(), ReplayByteStream.FailureReason.FRAME_DATA),
            Case(1, 1, 1, byteArrayOf(1), ReplayByteStream.FailureReason.FRAME_DATA),
            Case(1, 0, 65, ByteArray(65), ReplayByteStream.FailureReason.FRAME_CREDIT),
            Case(2, 1, 0, byteArrayOf(), ReplayByteStream.FailureReason.FRAME_ACK),
            Case(3, 1, 0, byteArrayOf(), ReplayByteStream.FailureReason.FRAME_PING),
            Case(9, 0, 0, byteArrayOf(), ReplayByteStream.FailureReason.FRAME_KIND),
            Case(1, 0, 2, byteArrayOf(1), ReplayByteStream.FailureReason.READ_PAYLOAD),
            Case(3, 0, 0, byteArrayOf(), ReplayByteStream.FailureReason.READ_HEADER),
        )
        for (case in cases) {
            val wire = ByteArrayOutputStream().apply {
                DataOutputStream(this).apply {
                    writeInt(0x54525332); writeInt(64); writeLong(0); writeLong(0)
                    writeByte(case.kind); writeLong(case.position); writeInt(case.length); write(case.payload)
                }
            }.toByteArray()
            val replay = ReplayByteStream(64)
            try {
                val link = replay.attach(ByteArrayInputStream(wire), ByteArrayOutputStream()) { }
                assertTrue(case.toString(), link.awaitClosed(1, TimeUnit.SECONDS))
                val failure = requireNotNull(link.failure)
                assertEquals(case.toString(), case.reason, failure.reason)
                assertNotNull(failure.cause)
                link.close()
                assertSame(failure, link.failure)
                assertFalse(replay.ownerClosed)
            } finally { replay.close() }
        }
    }

    @Test(timeout = 5_000) fun writeFailureWinsOverTheReadErrorCausedByItsSocketShutdown() {
        val replay = ReplayByteStream()
        val worker = Executors.newSingleThreadExecutor()
        val failure = IOException("private exception detail must not form a diagnostic code")
        ServerSocket(0).use { server ->
            Socket("127.0.0.1", server.localPort).use { host ->
                server.accept().use { peer ->
                    try {
                        val raw = host.getOutputStream()
                        var writes = 0
                        val output = object : OutputStream() {
                            override fun write(value: Int) = throw AssertionError("Expected buffered bulk writes")
                            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                                if (++writes > 1) throw failure // Handshake succeeds; the first ACK cannot be sent.
                                raw.write(bytes, offset, count)
                            }
                        }
                        val attached = worker.submit<ReplayByteStream.Link> {
                            replay.attach(host.getInputStream(), output) { host.close() }
                        }
                        val input = DataInputStream(peer.getInputStream())
                        ByteArray(24).also(input::readFully)
                        DataOutputStream(peer.getOutputStream()).apply {
                            writeInt(0x54525332); writeInt(64); writeLong(0); writeLong(0); flush()
                        }
                        val link = attached.get(2, TimeUnit.SECONDS)
                        assertTrue(link.awaitClosed(2, TimeUnit.SECONDS))
                        assertEquals(ReplayByteStream.FailureReason.WRITE_FRAME, link.failure?.reason)
                        assertSame(failure, link.failure?.cause)
                        assertFalse(replay.ownerClosed)
                    } finally { replay.close(); worker.shutdownNow() }
                }
            }
        }
    }

    @Test(timeout = 30_000) fun heartbeatExpiryRetainsItsCauseAndExplicitCloseHasNoFailure() {
        val replay = ReplayByteStream()
        val worker = Executors.newSingleThreadExecutor()
        ServerSocket(0).use { server ->
            Socket("127.0.0.1", server.localPort).use { host ->
                server.accept().use { peer ->
                    try {
                        val attached = worker.submit<ReplayByteStream.Link> {
                            replay.attach(host.getInputStream(), host.getOutputStream()) { host.close() }
                        }
                        ByteArray(24).also(DataInputStream(peer.getInputStream())::readFully)
                        DataOutputStream(peer.getOutputStream()).apply {
                            writeInt(0x54525332); writeInt(64); writeLong(0); writeLong(0); flush()
                        }
                        val link = attached.get(2, TimeUnit.SECONDS)
                        assertTrue(link.awaitClosed(26, TimeUnit.SECONDS))
                        assertEquals(ReplayByteStream.FailureReason.HEARTBEAT_TIMEOUT, link.failure?.reason)
                        assertNull(link.failure?.cause)
                        assertFalse(replay.ownerClosed)
                        val right = ReplayByteStream()
                        try {
                            val replacement = connect(replay, right)
                            replacement.close()
                            assertTrue(replacement.awaitClosed(1, TimeUnit.SECONDS))
                            assertNull(replacement.failure)
                            val last = connect(replay, right)
                            replay.close()
                            assertTrue(last.awaitClosed(1, TimeUnit.SECONDS))
                            assertNull(last.failure)
                        } finally { right.close() }
                    } finally { replay.close(); worker.shutdownNow() }
                }
            }
        }
    }

    @Test(timeout = 5_000) fun shortAndFullFramesFlushImmediatelyAsSingleRawWrites() {
        val replay = ReplayByteStream()
        val writes = CopyOnWriteArrayList<ByteArray>()
        val worker = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            Socket(java.net.InetAddress.getLoopbackAddress(), server.localPort).use { host ->
                server.accept().use { peer ->
                    host.tcpNoDelay = true; peer.tcpNoDelay = true; peer.soTimeout = 2_000
                    val raw = host.getOutputStream()
                    val output = object : OutputStream() {
                        override fun write(value: Int) { writes += byteArrayOf(value.toByte()); raw.write(value) }
                        override fun write(bytes: ByteArray, offset: Int, count: Int) {
                            if (count > 0) writes += bytes.copyOfRange(offset, offset + count)
                            raw.write(bytes, offset, count)
                        }
                    }
                    try {
                        val attached = worker.submit<ReplayByteStream.Link> {
                            replay.attach(host.getInputStream(), output) { host.close() }
                        }
                        val incoming = DataInputStream(peer.getInputStream())
                        val outgoing = DataOutputStream(peer.getOutputStream())
                        assertEquals(0x54525332, incoming.readInt())
                        assertEquals(ReplayByteStream.WINDOW, incoming.readInt())
                        assertEquals(0L, incoming.readLong()); assertEquals(0L, incoming.readLong())
                        outgoing.writeInt(0x54525332); outgoing.writeInt(ReplayByteStream.WINDOW)
                        outgoing.writeLong(0); outgoing.writeLong(0); outgoing.flush()
                        attached.get(2, TimeUnit.SECONDS)
                        var offset = 0L
                        for (bytes in listOf(byteArrayOf(42), ByteArray(ReplayByteStream.CHUNK) { it.toByte() })) {
                            replay.output.write(bytes)
                            // No next application write, timer or full buffer may be
                            // required to release even a one-byte interactive packet.
                            while (true) {
                                val kind = incoming.readUnsignedByte()
                                val position = incoming.readLong()
                                val size = incoming.readInt()
                                if (kind == 2) { assertEquals(0, size); continue }
                                assertEquals(1, kind); assertEquals(offset, position)
                                assertEquals(bytes.size, size)
                                val actual = ByteArray(size).also(incoming::readFully)
                                assertArrayEquals(bytes, actual)
                                offset += size
                                break
                            }
                        }
                        val dataWrites = writes.filter { it.firstOrNull() == 1.toByte() }
                        assertEquals(listOf(14, 13 + ReplayByteStream.CHUNK), dataWrites.map { it.size })
                    } finally { replay.close(); worker.shutdownNow() }
                }
            }
        }
    }

    @Test(timeout = 10_000) fun bulkCopiesWrapAndReleaseCreditInBothDirections() {
        val left = ReplayByteStream(31); val right = ReplayByteStream(31)
        val workers = Executors.newFixedThreadPool(2)
        try {
            connect(left, right)
            val first = ByteArray(4097) { (it * 17).toByte() }
            val second = ByteArray(4099) { (it * 31 + 5).toByte() }
            val sendingLeft = workers.submit { left.output.write(first) }
            val sendingRight = workers.submit { right.output.write(second) }
            val receivedLeft = ByteArray(second.size)
            val receivedRight = ByteArray(first.size)
            // Consume different chunks in each direction to cross both ring ends
            // and repeatedly exhaust/release a non-power-of-two receive window.
            var a = 0; var b = 0
            while (a < receivedLeft.size || b < receivedRight.size) {
                if (a < receivedLeft.size) a += left.input.read(receivedLeft, a, minOf(17, receivedLeft.size - a))
                if (b < receivedRight.size) b += right.input.read(receivedRight, b, minOf(23, receivedRight.size - b))
            }
            sendingLeft.get(2, TimeUnit.SECONDS); sendingRight.get(2, TimeUnit.SECONDS)
            assertArrayEquals(second, receivedLeft); assertArrayEquals(first, receivedRight)
        } finally { left.close(); right.close(); workers.shutdownNow() }
    }

    @Test fun socketReplacementKeepsApplicationStreamAndUnconsumedPrefixExactlyOnce() {
        val left = ReplayByteStream(64); val right = ReplayByteStream(64)
        try {
            var link = connect(left, right)
            left.output.write("first-second".toByteArray())
            assertEquals("first", read(right, 5))
            link.close()
            left.output.write("-third".toByteArray())
            link = connect(left, right)
            assertEquals("-second-third", read(right, 13))
            right.output.write("response".toByteArray())
            assertEquals("response", read(left, 8))
            link.close()
        } finally { left.close(); right.close() }
    }

    @Test fun kotlinAndGuestPythonUseTheSameReplayWireAcrossReconnect() {
        val script = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
            .map { it.resolve("image/scripts/private-share-transport.py") }.first { it.toFile().isFile }
        ServerSocket(0, 4, java.net.InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 5_000
            val process = ProcessBuilder("python3", "-c", """
                import importlib.util, socket, sys
                spec=importlib.util.spec_from_file_location('transport',sys.argv[1])
                module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
                relay=module.ReplayStream(64)
                for count in (3,6):
                    link=relay.attach(socket.create_connection(('127.0.0.1',int(sys.argv[2]))))
                    data=bytearray()
                    while len(data)<count:data.extend(relay.read(count-len(data)))
                    relay.write(data)
                    if count==3:assert link.wait(5)
                with relay.condition:assert relay.condition.wait_for(lambda:relay.acknowledged==relay.produced,5)
                relay.close()
                print('GUEST_REPLAY_OK')
            """.trimIndent(), script.toString(), server.localPort.toString()).redirectErrorStream(true).start()
            val relay = ReplayByteStream(64)
            try {
                var socket = server.accept()
                val first = relay.attach(socket.getInputStream(), socket.getOutputStream()) { socket.close() }
                relay.output.write("abc".toByteArray()); assertEquals("abc", read(relay, 3))
                first.close()
                relay.output.write("abcdef".toByteArray())
                socket = server.accept()
                relay.attach(socket.getInputStream(), socket.getOutputStream()) { socket.close() }
                assertEquals("abcdef", read(relay, 6))
                assertTrue(process.waitFor(5, TimeUnit.SECONDS))
                assertEquals(process.inputStream.bufferedReader().readText(), 0, process.exitValue())
            } finally { relay.close(); process.destroyForcibly() }
        }
    }

    private fun read(stream: ReplayByteStream, count: Int): String =
        ByteArray(count).also { DataInputStream(stream.input).readFully(it) }.decodeToString()

    private fun connect(left: ReplayByteStream, right: ReplayByteStream): ReplayByteStream.Link {
        val workers = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            val client = Socket(java.net.InetAddress.getLoopbackAddress(), server.localPort)
            val accepted = server.accept()
            try {
                val other = workers.submit<ReplayByteStream.Link> {
                    right.attach(accepted.getInputStream(), accepted.getOutputStream()) { accepted.close() }
                }
                val link = left.attach(client.getInputStream(), client.getOutputStream()) { client.close() }
                other.get(3, TimeUnit.SECONDS)
                return link
            } finally { workers.shutdownNow() }
        }
    }
}
