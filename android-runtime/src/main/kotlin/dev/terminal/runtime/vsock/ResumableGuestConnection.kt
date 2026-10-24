package dev.terminal.runtime.vsock

import dev.terminal.protocol.Protocol
import dev.terminal.protocol.ProtocolFrame
import dev.terminal.protocol.ReplayByteStream
import dev.terminal.runtime.api.GuestByteStream
import dev.terminal.runtime.api.GuestConnection
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runInterruptible

/** Retains the protocol client and byte positions while replacing only the vsock. */
class ResumableGuestConnection(
    override val guestCid: Int,
    private val connect: () -> GuestByteStream,
    private val report: (String, String?, Throwable?) -> Unit = { _, _, _ -> },
    private val recoveryTimeoutMillis: Long = 60_000L,
    private val handshakeTimeoutMillis: Long = 10_000L,
) : GuestConnection {
    override val requestTimeoutMillis: Long get() = recoveryTimeoutMillis + handshakeTimeoutMillis
    private val replay = ReplayByteStream()
    private val token = UUID.randomUUID()
    private val closed = AtomicBoolean()
    private val established = AtomicBoolean()
    private val socket = AtomicReference<GuestByteStream?>()
    private val input = DataInputStream(replay.input)
    private val writes = Mutex()
    private val worker = AtomicReference<Thread?>()

    init {
        require(recoveryTimeoutMillis > 0 && handshakeTimeoutMillis > 0)
        val first = try { attach() } catch (error: Throwable) { replay.close(); throw error }
        val thread = Thread(null, { reconnect(first) }, "session-reconnect", 256 * 1024).apply { isDaemon = true }
        worker.set(thread)
        thread.start()
    }

    private fun attach(deadline: Long = Long.MAX_VALUE, attempt: Int = 0): ReplayByteStream.Link {
        val started = System.nanoTime()
        var phase = "connect"
        var reply: Int? = null
        val timedOut = AtomicBoolean()
        fun remaining(): Long = (if (deadline == Long.MAX_VALUE) handshakeTimeoutMillis else
            minOf(handshakeTimeoutMillis, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))).also {
            if (it <= 0) { timedOut.set(true); throw IOException("Session recovery budget expired") }
        }
        try {
            val raw = GuestConnectionOpener.open(remaining(), connect)
            socket.set(raw)
            if (closed.get()) { raw.close(); throw IOException("Session owner closed") }
            val budget = try { remaining() } catch (error: Exception) { raw.close(); throw error }
            val timeout = WATCHDOG.schedule({ timedOut.set(true); runCatching { raw.close() } }, budget, TimeUnit.MILLISECONDS)
            try {
                phase = "owner_write"
                val output = DataOutputStream(raw.output)
                output.writeInt(0x54535231) // TSR1 + same process-owner token on every link
                output.writeLong(token.mostSignificantBits); output.writeLong(token.leastSignificantBits)
                output.writeByte(if (established.get()) 0 else 1); output.flush()
                phase = "owner_status"
                reply = raw.input.read()
                when (reply) {
                    0 -> Unit
                    2 -> throw IllegalArgumentException("Guest lost session replay generation")
                    else -> throw IOException("Guest session owner unavailable")
                }
                phase = "replay"
                return replay.attach(raw.input, raw.output) { raw.close() }.also { established.set(true) }
            } catch (error: Throwable) {
                runCatching { raw.close() }
                throw error
            } finally { timeout.cancel(false) }
        } catch (error: Exception) {
            if (!closed.get()) {
                val reason = when {
                    timedOut.get() || error is java.util.concurrent.TimeoutException -> "timeout"
                    error is ReplayByteStream.HandshakeException -> error.reason.code
                    else -> "error"
                }
                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                record("session.attach.failed", error,
                    "$phase.$reason.reply_${reply ?: "none"}.attempt_$attempt.elapsed_ms_$elapsed")
            }
            throw error
        }
    }

    private fun reconnect(initial: ReplayByteStream.Link) {
        var link = initial
        try {
            while (!closed.get()) {
                while (!closed.get() && !link.awaitClosed(1, TimeUnit.SECONDS)) Unit
                if (closed.get()) break
                record("session.link.reconnecting", link.failure?.cause, link.failure?.reason?.code)
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(recoveryTimeoutMillis)
                var attempt = 0
                while (!closed.get()) {
                    try { link = attach(deadline, attempt + 1); record("session.link.restored"); break }
                    catch (error: Exception) {
                        if (error is IllegalArgumentException || System.nanoTime() >= deadline) throw error
                        val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                        if (remaining <= 0) throw error
                        Thread.sleep(minOf(remaining, longArrayOf(250, 1_000, 2_000, 4_000)[minOf(attempt++, 3)]))
                    }
                }
            }
        } catch (error: Exception) { if (!closed.get()) record("session.link.failed", error) }
        finally { close() }
    }

    override suspend fun send(frame: ProtocolFrame): Result<Unit> = runCatching {
        writes.withLock { runInterruptible(Dispatchers.IO) { replay.output.write(Protocol.encode(frame)) } }
    }

    override fun incomingFrames(): Flow<ProtocolFrame> = channelFlow {
        val reader = launch(Dispatchers.IO) {
            try {
                while (!closed.get()) {
                    send(Protocol.read(input))
                }
            } catch (_: EOFException) { /* Permanent owner loss. */ }
            catch (error: Exception) { channel.close(error) }
            finally { this@ResumableGuestConnection.close(); channel.close() }
        }
        // Cancellation must close the blocking reader before waiting for its child job.
        awaitClose { this@ResumableGuestConnection.close(); reader.cancel() }
    }

    private fun record(event: String, error: Throwable? = null, code: String? = null) { runCatching { report(event, code, error) } }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        replay.close()
        socket.getAndSet(null)?.let { runCatching { it.close() } }
        worker.getAndSet(null)?.interrupt()
    }

    companion object {
        private val WATCHDOG = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "session-handshake-timeout").apply { isDaemon = true }
        }
    }
}
