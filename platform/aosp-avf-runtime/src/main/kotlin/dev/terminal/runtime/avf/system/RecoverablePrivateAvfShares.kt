package dev.terminal.runtime.avf.system

import dev.terminal.protocol.ReplayByteStream
import dev.terminal.runtime.api.GuestByteStream
import dev.terminal.runtime.api.SharedDirectoryExport
import dev.terminal.runtime.storage.ScopedSftpServer
import java.io.Closeable
import java.io.DataOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Broker v2 retains each SFTP server and its descriptors across a transient vsock reset. */
internal class RecoverablePrivateAvfShares(private val folders: List<SharedDirectoryExport>,
    private val report: (String, Throwable?) -> Unit) : Closeable {
    private val token = UUID.randomUUID()
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    private val dataStarted = AtomicBoolean()
    private val ownerEstablished = AtomicBoolean()
    private val initialized = CountDownLatch(1)
    private val workers = CopyOnWriteArrayList<Thread>()
    private val relays = CopyOnWriteArrayList<ReplayByteStream>()
    private val foldersById = java.util.concurrent.ConcurrentHashMap<UUID, ReplayByteStream>()
    private val disabled = java.util.concurrent.ConcurrentHashMap.newKeySet<UUID>()
    private val sockets = CopyOnWriteArrayList<GuestByteStream>()
    private val control = AtomicReference<GuestByteStream?>()
    private val progress = AtomicLong(System.nanoTime())
    @Volatile var ready = false
        private set
    private var permits = false

    fun start(connect: () -> GuestByteStream) {
        synchronized(this) {
            check(!closed.get())
            if (started.compareAndSet(false, true)) {
            require(folders.isNotEmpty() && folders.size <= 128)
            check(PrivateAvfShares.STREAMS.tryAcquire(folders.size)) { "AVF share connection limit reached" }
            permits = true
            launch("share-owner") { controlLoop(connect) }
            }
        }
        if (!initialized.await(50, TimeUnit.SECONDS) || !ready || closed.get()) {
            close()
            error("Recoverable private shares did not become ready")
        }
    }

    private fun controlLoop(connect: () -> GuestByteStream) {
        val watchdog = PrivateAvfShares.WATCHDOG.scheduleAtFixedRate({
            if (System.nanoTime() - progress.get() > TimeUnit.SECONDS.toNanos(15))
                control.get()?.let { runCatching { it.close() } }
        }, 1, 1, TimeUnit.SECONDS)
        var attempt = 0
        try {
            while (!closed.get()) {
                var socket: GuestByteStream? = null
                try {
                    socket = connect(); track(socket); control.set(socket); progress.set(System.nanoTime())
                    val output = DataOutputStream(socket.output)
                    val input = DataInputStream(socket.input)
                    output.writeInt(0x54534232); output.writeShort(2); output.writeShort(folders.size)
                    output.writeLong(token.mostSignificantBits); output.writeLong(token.leastSignificantBits)
                    folders.forEach { folder ->
                        val id = UUID.fromString(folder.id); val name = folder.name.toByteArray(Charsets.US_ASCII)
                        output.writeLong(id.mostSignificantBits); output.writeLong(id.leastSignificantBits)
                        output.writeByte(name.size); output.write(name)
                    }
                    output.writeByte(if (ownerEstablished.get()) 0 else 1)
                    output.flush()
                    check(input.read() == 0) { "Guest rejected share owner generation" }
                    ownerEstablished.set(true)
                    if (dataStarted.compareAndSet(false, true)) startFolders(connect)
                    check(input.read() == 0) { "Guest did not confirm private mounts" }
                    progress.set(System.nanoTime()); ready = true; initialized.countDown(); attempt = 0
                    record("share.control.ready")
                    while (!closed.get()) {
                        Thread.sleep(5_000)
                        disabled.forEach { identity ->
                            output.writeByte(3); output.writeLong(identity.mostSignificantBits)
                            output.writeLong(identity.leastSignificantBits); output.flush()
                            check(input.read() == 0) { "Guest did not revoke failed share" }
                        }
                        output.writeByte(1); output.flush()
                        check(input.read() == 0) { "Guest share owner lease unavailable" }
                        val count = input.readUnsignedShort()
                        require(count <= folders.size)
                        repeat(count) {
                            val identity = UUID(input.readLong(), input.readLong())
                            check(foldersById.containsKey(identity))
                            disabled.add(identity); foldersById[identity]?.close()
                        }
                        progress.set(System.nanoTime())
                    }
                } catch (error: Exception) { if (!closed.get()) record("share.control.reconnecting", error) }
                finally {
                    control.compareAndSet(socket, null)
                    socket?.let { sockets.remove(it); runCatching { it.close() } }
                }
                if (!closed.get()) retry(attempt++)
            }
        } finally { watchdog.cancel(false); initialized.countDown() }
    }

    private fun startFolders(connect: () -> GuestByteStream) {
        folders.forEach { folder ->
            val relay = ReplayByteStream(); relays += relay
            val identity = UUID.fromString(folder.id)
            foldersById[identity] = relay
            launch("share-sftp") {
                try { ScopedSftpServer(File(folder.directory)).use { it.serve(relay.input, relay.output) } }
                catch (error: Exception) { if (!closed.get()) record("share.sftp.failed", error) }
                finally { if (!closed.get()) disabled.add(identity); relay.close() }
            }
            launch("share-reconnect") {
                var attempt = 0
                var established = false
                while (!closed.get() && !relay.ownerClosed) {
                    var socket: GuestByteStream? = null
                    try {
                        socket = connect(); track(socket)
                        val active = socket
                        val handshake = PrivateAvfShares.WATCHDOG.schedule({ runCatching { active.close() } },
                            10, TimeUnit.SECONDS)
                        try {
                        val output = DataOutputStream(socket.output)
                        val id = UUID.fromString(folder.id)
                        output.writeInt(0x54534632)
                        output.writeLong(token.mostSignificantBits); output.writeLong(token.leastSignificantBits)
                        output.writeLong(id.mostSignificantBits); output.writeLong(id.leastSignificantBits)
                        output.writeByte(if (established) 0 else 1); output.flush()
                        val accepted = socket.input.read()
                        if (accepted == 1) {
                            disabled.add(identity); relay.close(); record("share.data.revoked")
                            break
                        }
                        check(accepted == 0) { "Guest rejected share stream generation" }
                        val link = relay.attach(socket.input, socket.output) { active.close() }
                        established = true
                        handshake.cancel(false)
                        attempt = 0
                        record("share.data.connected")
                        while (!closed.get() && !link.awaitClosed(1, TimeUnit.SECONDS)) Unit
                        if (!closed.get()) record("share.data.reconnecting")
                        } finally { handshake.cancel(false) }
                    } catch (error: Exception) {
                        if (!closed.get()) record("share.data.reconnecting", error)
                        if (error is IllegalArgumentException) { disabled.add(identity); relay.close() }
                    }
                    finally { socket?.let { sockets.remove(it); runCatching { it.close() } } }
                    if (!closed.get()) retry(attempt++)
                }
            }
        }
    }

    private fun retry(attempt: Int) {
        // Startup retains its 50-second readiness budget; a long reconnect
        // backoff must not skip a broker that becomes ready near that deadline.
        val delay = if (!ready) 100L else longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)[attempt.coerceAtMost(4)]
        try { Thread.sleep(delay) }
        catch (_: InterruptedException) { /* close wakes all owner workers. */ }
    }
    private fun record(event: String, error: Throwable? = null) { runCatching { report(event, error) } }
    private fun track(socket: GuestByteStream) {
        synchronized(this) {
            if (closed.get()) { socket.close(); throw IOException("Private share owner closed") }
            sockets += socket
        }
    }
    private fun launch(name: String, action: () -> Unit) {
        synchronized(this) {
            check(!closed.get())
            Thread(null, { try { action() } finally { workers.remove(Thread.currentThread()) } }, name, 256 * 1024)
                .apply { isDaemon = true; workers += this; start() }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            ready = false
            relays.forEach { it.close() }
            sockets.forEach { runCatching { it.close() } }
            workers.forEach { it.interrupt() }
            initialized.countDown()
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        workers.toList().forEach { worker ->
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remaining > 0 && worker !== Thread.currentThread()) worker.join(remaining)
        }
        check(workers.none { it.isAlive && it !== Thread.currentThread() }) { "Private share I/O has not stopped; leases retained" }
        synchronized(this) { if (permits) { permits = false; PrivateAvfShares.STREAMS.release(folders.size) } }
    }
}
