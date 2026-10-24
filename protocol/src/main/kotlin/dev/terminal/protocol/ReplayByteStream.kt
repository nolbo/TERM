package dev.terminal.protocol

import java.io.Closeable
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture

/** A process-owned byte stream whose consumers and file handles survive socket replacement.
 * Receive and consumption offsets are distinct: only consumed bytes release sender credit.
 * This is not persistence across owner death and never re-executes application requests.
 */
class ReplayByteStream(private val capacity: Int = WINDOW) : Closeable {
    /** Stable metadata only; neither peer bytes nor exception messages form these codes. */
    enum class FailureReason(val code: String) {
        HANDSHAKE_MAGIC("handshake.magic"), HANDSHAKE_WINDOW("handshake.window"),
        HANDSHAKE_OFFSETS("handshake.offsets"), READ_HEADER("read.header"),
        READ_PAYLOAD("read.payload"), FRAME_BOUNDS("frame.bounds"), FRAME_ACK("frame.ack"),
        FRAME_DATA("frame.data"), FRAME_CREDIT("frame.credit"), FRAME_PING("frame.ping"),
        FRAME_KIND("frame.kind"), WRITE_FRAME("write.frame"), HEARTBEAT_TIMEOUT("heartbeat.timeout"),
    }
    class HandshakeException internal constructor(val reason: FailureReason, message: String,
        val details: Map<String, String> = emptyMap()) : IllegalArgumentException(message)
    data class Failure(val reason: FailureReason, val cause: Exception? = null, val details: Map<String, String> = emptyMap())

    private class Ring(private val capacity: Int) {
        private val bytes = ByteArray(capacity)
        var base = 0L
        var end = 0L
        val size get() = (end - base).toInt()
        fun append(source: ByteArray, offset: Int, count: Int) {
            check(count <= capacity - size && end <= Long.MAX_VALUE - count)
            val start = (end % capacity).toInt()
            val first = minOf(count, capacity - start)
            source.copyInto(bytes, start, offset, offset + first)
            if (first < count) source.copyInto(bytes, 0, offset + first, offset + count)
            end += count
        }
        fun copyInto(position: Long, target: ByteArray, offset: Int, count: Int) {
            require(position >= base && position <= end && count <= end - position)
            val start = (position % capacity).toInt()
            val first = minOf(count, capacity - start)
            bytes.copyInto(target, offset, start, start + first)
            if (first < count) bytes.copyInto(target, offset + first, 0, count - first)
        }
        fun copy(position: Long, count: Int): ByteArray = ByteArray(count).also { copyInto(position, it, 0, count) }
        fun discard(position: Long) { require(position in base..end); base = position }
    }

    class Link internal constructor(private val disconnect: () -> Unit) : Closeable {
        internal val stopped = CountDownLatch(1)
        /** First failure of this raw link, published before awaitClosed returns. Explicit close has none. */
        @Volatile var failure: Failure? = null
            internal set
        override fun close() = disconnect()
        fun awaitClosed(timeout: Long, unit: TimeUnit) = stopped.await(timeout, unit)
    }
    private class Connection(val input: DataInputStream, val output: DataOutputStream,
        val closeSocket: () -> Unit, var cursor: Long, var consumed: Long, val window: Int) {
        val closed = AtomicBoolean()
        lateinit var link: Link
        @Volatile var lastRead = System.nanoTime()
        @Volatile var watchdog: ScheduledFuture<*>? = null
    }
    private val lock = java.lang.Object()
    private val attachLock = Any()
    private val received = Ring(capacity)
    private val sent = Ring(capacity)
    private var closed = false
    private var connection: Connection? = null
    private var connecting: (() -> Unit)? = null

    init { require(capacity in 1..WINDOW) }

    val connected get() = synchronized(lock) { connection != null && !closed }
    val ownerClosed get() = synchronized(lock) { closed }
    fun diagnostics(): Map<String, String> = synchronized(lock) { state(connection) }
    private fun state(current: Connection?): Map<String, String> = buildMap {
        put("sent_base", sent.base.toString()); put("sent_end", sent.end.toString())
        put("received_base", received.base.toString()); put("received_end", received.end.toString())
        put("capacity", capacity.toString())
        current?.let {
            put("send_cursor", it.cursor.toString()); put("peer_consumed", it.consumed.toString())
            put("peer_window", it.window.toString())
            put("read_idle_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - it.lastRead).toString())
        }
    }
    val input: InputStream = object : InputStream() {
        override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 255 }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            if (length == 0) return 0
            synchronized(lock) {
                while (received.size == 0 && !closed) lock.wait()
                if (closed) return -1
                val count = minOf(length, received.size)
                received.copyInto(received.base, bytes, offset, count)
                received.discard(received.base + count)
                lock.notifyAll()
                return count
            }
        }
        override fun close() = this@ReplayByteStream.close()
    }
    val output: OutputStream = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()))
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            var position = offset
            var remaining = length
            synchronized(lock) {
                while (remaining > 0) {
                    while (sent.size == capacity && !closed) lock.wait()
                    if (closed) throw IOException("Replay stream owner closed")
                    val count = minOf(remaining, capacity - sent.size)
                    sent.append(bytes, position, count)
                    position += count; remaining -= count
                    lock.notifyAll()
                }
            }
        }
        override fun close() = this@ReplayByteStream.close()
    }

    /** The caller authenticates the boot owner and folder before attaching a raw socket. */
    fun attach(input: InputStream, output: OutputStream, closeSocket: () -> Unit): Link = synchronized(attachLock) {
        synchronized(lock) { if (closed) { closeSocket(); throw IOException("Replay stream owner closed") } }
        synchronized(lock) { connection }?.let(::detach)
        synchronized(lock) { connecting = closeSocket }
        try {
            // Parsing primitives directly on a raw vsock splits one replay frame
            // into several kernel/VM crossings. Buffer each link, but flush every
            // handshake/frame so interactive input never waits for a batch timer.
            val source = DataInputStream(BufferedInputStream(input, CHUNK + FRAME_HEADER_SIZE))
            val sink = DataOutputStream(BufferedOutputStream(output, CHUNK + FRAME_HEADER_SIZE))
            val offsets = synchronized(lock) { received.end to received.base }
            sink.writeInt(MAGIC); sink.writeInt(capacity)
            sink.writeLong(offsets.first); sink.writeLong(offsets.second)
            sink.flush()
            if (source.readInt() != MAGIC) throw HandshakeException(FailureReason.HANDSHAKE_MAGIC, "Invalid replay handshake")
            val window = source.readInt()
            val peerReceived = source.readLong(); val peerConsumed = source.readLong()
            val current = synchronized(lock) {
                check(!closed)
                if (window !in 1..WINDOW) throw HandshakeException(FailureReason.HANDSHAKE_WINDOW,
                    "Replay peer lost its stream generation", state(null) + mapOf("peer_window" to window.toString(),
                        "peer_received" to peerReceived.toString(), "peer_consumed" to peerConsumed.toString()))
                if (peerConsumed < sent.base || peerConsumed > peerReceived || peerReceived > sent.end)
                    throw HandshakeException(FailureReason.HANDSHAKE_OFFSETS, "Replay peer lost its stream generation",
                        state(null) + mapOf("peer_window" to window.toString(), "peer_received" to peerReceived.toString(),
                            "peer_consumed" to peerConsumed.toString()))
                sent.discard(peerConsumed)
                Connection(source, sink, closeSocket, peerReceived, peerConsumed, window).also {
                    it.link = Link { detach(it) }
                    connection = it; connecting = null
                }
            }
            current.watchdog = WATCHDOG.scheduleAtFixedRate({
                if (System.nanoTime() - current.lastRead > TimeUnit.SECONDS.toNanos(20))
                    detach(current, Failure(FailureReason.HEARTBEAT_TIMEOUT))
            }, 5, 5, TimeUnit.SECONDS)
            if (current.closed.get()) current.watchdog?.cancel(false)
            thread("share-replay-read") { readFrames(current) }
            thread("share-replay-write") { writeFrames(current) }
            current.link
        } catch (error: Throwable) {
            synchronized(lock) { connecting = null }
            runCatching(closeSocket)
            throw error
        }
    }

    private fun readFrames(current: Connection) {
        var reason = FailureReason.READ_HEADER
        try {
            while (true) {
                reason = FailureReason.READ_HEADER
                val kind = current.input.readUnsignedByte()
                val position = current.input.readLong()
                val length = current.input.readInt()
                reason = FailureReason.FRAME_BOUNDS
                require(position >= 0 && length in 0..CHUNK && position <= Long.MAX_VALUE - length)
                reason = FailureReason.READ_PAYLOAD
                val bytes = ByteArray(length).also { current.input.readFully(it) }
                current.lastRead = System.nanoTime()
                synchronized(lock) {
                    if (closed || connection !== current) return
                    when (kind) {
                        ACK -> {
                            reason = FailureReason.FRAME_ACK
                            require(length == 0 && position in sent.base..current.cursor)
                            sent.discard(position); current.consumed = position
                        }
                        DATA -> {
                            reason = FailureReason.FRAME_DATA
                            require(length > 0 && position <= received.end)
                            val skip = minOf(length.toLong(), received.end - position).toInt()
                            reason = FailureReason.FRAME_CREDIT
                            require(length - skip <= capacity - received.size) { "Replay peer exceeded receive credit" }
                            received.append(bytes, skip, length - skip)
                        }
                        PING -> {
                            reason = FailureReason.FRAME_PING
                            require(length == 0 && position == 0L)
                        }
                        else -> {
                            reason = FailureReason.FRAME_KIND
                            throw IOException("Unknown replay frame")
                        }
                    }
                    lock.notifyAll()
                }
            }
        } catch (error: Exception) { detach(current, Failure(reason, error)) }
        finally { detach(current) }
    }

    private fun writeFrames(current: Connection) {
        var acknowledged = -1L
        var lastWrite = System.nanoTime()
        try {
            while (true) {
                var kind = ACK
                var position = 0L
                var bytes = byteArrayOf()
                synchronized(lock) {
                    while (true) {
                        if (closed || connection !== current) return
                        if (received.base > acknowledged) {
                            position = received.base; acknowledged = position
                            break
                        }
                        val available = minOf(sent.end - current.cursor,
                            current.window.toLong() - (current.cursor - current.consumed))
                        if (available > 0) {
                            kind = DATA; position = current.cursor
                            bytes = sent.copy(position, minOf(CHUNK.toLong(), available).toInt())
                            current.cursor += bytes.size
                            break
                        }
                        val remaining = TimeUnit.SECONDS.toNanos(5) - (System.nanoTime() - lastWrite)
                        if (remaining <= 0) { kind = PING; break }
                        lock.wait(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1))
                    }
                }
                current.output.writeByte(kind); current.output.writeLong(position)
                current.output.writeInt(bytes.size); current.output.write(bytes); current.output.flush()
                lastWrite = System.nanoTime()
            }
        } catch (error: Exception) { detach(current, Failure(FailureReason.WRITE_FRAME, error)) }
        finally { detach(current) }
    }

    private fun detach(current: Connection, failure: Failure? = null) {
        if (!current.closed.compareAndSet(false, true)) return
        current.watchdog?.cancel(false)
        synchronized(lock) {
            if (connection === current) {
                if (!closed) current.link.failure = failure?.copy(details = state(current))
                connection = null
            }
            lock.notifyAll()
        }
        runCatching(current.closeSocket)
        current.link.stopped.countDown()
    }

    override fun close() {
        val active: Connection?
        val pending: (() -> Unit)?
        synchronized(lock) {
            if (closed) return
            closed = true; active = connection; pending = connecting; lock.notifyAll()
        }
        pending?.let { runCatching(it) }
        active?.let(::detach)
    }

    private fun thread(name: String, action: () -> Unit) = Thread(null, action, name, 256 * 1024).apply { isDaemon = true; start() }

    companion object {
        const val WINDOW = 256 * 1024
        const val CHUNK = 32 * 1024
        private const val FRAME_HEADER_SIZE = 13
        private const val MAGIC = 0x54525332 // TRS2
        private const val DATA = 1
        private const val ACK = 2
        private const val PING = 3
        private val WATCHDOG = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "share-replay-watchdog").apply { isDaemon = true }
        }
    }
}
