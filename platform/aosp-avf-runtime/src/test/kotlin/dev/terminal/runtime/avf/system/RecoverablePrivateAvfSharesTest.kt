package dev.terminal.runtime.avf.system

import dev.terminal.protocol.ReplayByteStream
import dev.terminal.runtime.api.GuestByteStream
import dev.terminal.runtime.api.SharedDirectoryExport
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class RecoverablePrivateAvfSharesTest {
    @Test(timeout = 10_000) fun expiredControlRecoveryStopsAllHostRetriesAndReleasesActors() {
        Broker().use { peer ->
            val events = CopyOnWriteArrayList<String>()
            val failed = CountDownLatch(1)
            val owner = owner(peer, events) { if (it == "share.owner.unavailable") failed.countDown() }
            try {
                owner.start(peer::open)
                assertTrue(owner.ready)
                peer.rejectControl.set(true); peer.control.get().close()
                assertTrue(failed.await(3, TimeUnit.SECONDS))
                assertFalse(owner.ready)
                val attempts = peer.attempts.get()
                Thread.sleep(150)
                assertEquals(attempts, peer.attempts.get())
                assertEquals(1, events.count { it == "share.owner.unavailable" })
            } finally { owner.close() }
        }
    }

    @Test(timeout = 10_000) fun expiredDataRecoveryDisablesThatFolderWhileControlRemainsLive() {
        Broker().use { peer ->
            val events = CopyOnWriteArrayList<String>()
            val failed = CountDownLatch(1)
            val owner = owner(peer, events) { if (it == "share.data.unavailable") failed.countDown() }
            try {
                owner.start(peer::open)
                peer.rejectData.set(true); peer.data.get().close()
                assertTrue(failed.await(3, TimeUnit.SECONDS))
                assertFalse(owner.ready)
                val before = peer.heartbeats.get()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (peer.heartbeats.get() <= before && System.nanoTime() < deadline) Thread.sleep(10)
                assertTrue(peer.heartbeats.get() > before)
                assertFalse(events.contains("share.owner.unavailable"))
                assertEquals(1, events.count { it == "share.data.unavailable" })
            } finally { owner.close() }
        }
    }

    private fun owner(peer: Broker, events: MutableList<String>, event: (String) -> Unit) =
        RecoverablePrivateAvfShares(listOf(SharedDirectoryExport(peer.folder.toString(), "Code", "/unused")),
            report = { name, _ -> events += name; event(name) }, recoveryTimeoutMillis = 500, heartbeatMillis = 20,
            serveFolder = { _, relay -> while (relay.input.read() >= 0) Unit })

    /** Exercises the real TSB2/TSF2/TRS2 host code without requiring a guest FUSE mount. */
    private class Broker : Closeable {
        val folder = UUID.randomUUID()
        val rejectControl = AtomicBoolean()
        val rejectData = AtomicBoolean()
        val attempts = AtomicInteger()
        val heartbeats = AtomicInteger()
        val control = AtomicReference<Socket>()
        val data = AtomicReference<Socket>()
        private val stopped = AtomicBoolean()
        private val listener = ServerSocket(0)
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val handlers = CopyOnWriteArrayList<Thread>()
        private val dataReady = CountDownLatch(1)
        private val replay = ReplayByteStream()
        private val accept = thread(isDaemon = true) {
            while (!stopped.get()) {
                val socket = try { listener.accept() } catch (_: Exception) { break }
                sockets += socket
                handlers += thread(isDaemon = true) { runCatching { handle(socket) } }
            }
        }

        fun open(): GuestByteStream {
            attempts.incrementAndGet()
            val socket = Socket("127.0.0.1", listener.localPort)
            return object : GuestByteStream {
                override val input = socket.getInputStream()
                override val output = socket.getOutputStream()
                override fun close() { socket.close() }
            }
        }

        private fun handle(socket: Socket) {
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            when (input.readInt()) {
                0x54534232 -> {
                    input.readUnsignedShort()
                    val count = input.readUnsignedShort()
                    input.readLong(); input.readLong()
                    repeat(count) {
                        input.readLong(); input.readLong()
                        val name = ByteArray(input.readUnsignedByte()); input.readFully(name)
                    }
                    input.readUnsignedByte()
                    if (rejectControl.get()) { socket.close(); return }
                    output.writeByte(0); output.flush()
                    check(dataReady.await(2, TimeUnit.SECONDS))
                    control.set(socket)
                    output.writeByte(0); output.flush()
                    while (!stopped.get()) {
                        when (input.readUnsignedByte()) {
                            1 -> { heartbeats.incrementAndGet(); output.writeByte(0); output.writeShort(0); output.flush() }
                            3 -> { input.readLong(); input.readLong(); output.writeByte(0); output.flush() }
                            else -> return
                        }
                    }
                }
                0x54534632 -> {
                    repeat(4) { input.readLong() }
                    input.readUnsignedByte()
                    if (rejectData.get()) { output.writeByte(1); output.flush(); socket.close(); return }
                    data.set(socket)
                    output.writeByte(0); output.flush()
                    replay.attach(input, socket.getOutputStream()) { socket.close() }
                    dataReady.countDown()
                }
                else -> error("Unexpected share wire")
            }
        }

        override fun close() {
            stopped.set(true); replay.close(); listener.close()
            sockets.forEach { runCatching { it.close() } }
            accept.join(1_000); handlers.forEach { it.join(1_000) }
        }
    }
}
