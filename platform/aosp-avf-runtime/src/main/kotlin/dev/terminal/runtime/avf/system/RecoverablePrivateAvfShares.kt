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

/** Fixed metadata codes only; exception messages/protocol contents never become diagnostics. */
internal class ShareControlFailure(val phase: Phase, val reply: Int?, val attempt: Int,
    val outageMillis: Long, cause: Exception) : IOException(cause) {
    val diagnosticCode get() = "${phase.code}.reply_${reply ?: "none"}.attempt_$attempt.outage_ms_$outageMillis"
    enum class Phase(val code: String) {
        CONNECT("control.connect"), MANIFEST("control.manifest"), OWNER_ACCEPT("control.owner_accept"),
        FOLDERS_START("control.folders_start"), MOUNTS_READY("control.mounts_ready"),
        FOLDER_REVOKE("control.folder_revoke"), HEARTBEAT("control.heartbeat"),
        REVOKED_FOLDERS("control.revoked_folders"),
    }
}
internal class ShareControlStall(val idleMillis: Long) : IOException("Share control progress timed out")

/** Broker v2 retains each SFTP server and its descriptors across a transient vsock reset. */
internal class RecoverablePrivateAvfShares(private val folders: List<SharedDirectoryExport>,
    private val report: (String, Throwable?) -> Unit,
    private val recoveryTimeoutMillis: Long = 60_000,
    private val heartbeatMillis: Long = 5_000,
    private val serveFolder: (SharedDirectoryExport, ReplayByteStream) -> Unit = { folder, relay ->
        ScopedSftpServer(File(folder.directory)).use { it.serve(relay.input, relay.output) }
    }) : Closeable {
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
    private val controlLostAt = AtomicLong()
    private val dataLostAt = java.util.concurrent.ConcurrentHashMap<UUID, Long>()
    private val folderSockets = java.util.concurrent.ConcurrentHashMap<UUID, GuestByteStream>()
    private val folderWorkers = java.util.concurrent.ConcurrentHashMap<UUID, Thread>()
    private val everReady = AtomicBoolean()
    @Volatile private var mounted = false
    val ready get() = mounted && !closed.get() && disabled.isEmpty()
    private var permits = false
    init { require(recoveryTimeoutMillis > 0 && heartbeatMillis > 0) }

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
        val interval = minOf(1_000L, recoveryTimeoutMillis / 4).coerceAtLeast(10)
        val watchdog = PrivateAvfShares.WATCHDOG.scheduleAtFixedRate({
            val now = System.nanoTime()
            if (expired(controlLostAt.get(), now)) ownerUnavailable()
            dataLostAt.forEach { (id, since) -> if (expired(since, now)) disableFolder(id, "share.data.unavailable") }
            val idleNanos = now - progress.get()
            if (idleNanos > TimeUnit.SECONDS.toNanos(15))
                control.get()?.let { socket ->
                    if (control.compareAndSet(socket, null)) {
                        record("share.control.stalled", ShareControlStall(TimeUnit.NANOSECONDS.toMillis(idleNanos)))
                        runCatching { socket.close() }
                    }
                }
        }, interval, interval, TimeUnit.MILLISECONDS)
        var attempt = 0
        try {
            while (!closed.get()) {
                var socket: GuestByteStream? = null
                var phase = ShareControlFailure.Phase.CONNECT
                var reply: Int? = null
                try {
                    socket = connect(); track(socket); control.set(socket); progress.set(System.nanoTime())
                    val output = DataOutputStream(socket.output)
                    val input = DataInputStream(socket.input)
                    phase = ShareControlFailure.Phase.MANIFEST
                    output.writeInt(0x54534232); output.writeShort(2); output.writeShort(folders.size)
                    output.writeLong(token.mostSignificantBits); output.writeLong(token.leastSignificantBits)
                    folders.forEach { folder ->
                        val id = UUID.fromString(folder.id); val name = folder.name.toByteArray(Charsets.US_ASCII)
                        output.writeLong(id.mostSignificantBits); output.writeLong(id.leastSignificantBits)
                        output.writeByte(name.size); output.write(name)
                    }
                    output.writeByte(if (ownerEstablished.get()) 0 else 1)
                    output.flush()
                    phase = ShareControlFailure.Phase.OWNER_ACCEPT
                    reply = input.read()
                    check(reply == 0) { "Guest rejected share owner generation" }
                    reply = null
                    ownerEstablished.set(true)
                    phase = ShareControlFailure.Phase.FOLDERS_START
                    if (dataStarted.compareAndSet(false, true)) startFolders(connect)
                    phase = ShareControlFailure.Phase.MOUNTS_READY
                    reply = input.read()
                    check(reply == 0) { "Guest did not confirm private mounts" }
                    reply = null
                    progress.set(System.nanoTime()); controlLostAt.set(0)
                    everReady.set(true); mounted = true; initialized.countDown(); attempt = 0
                    record("share.control.ready")
                    while (!closed.get()) {
                        Thread.sleep(heartbeatMillis)
                        disabled.forEach { identity ->
                            phase = ShareControlFailure.Phase.FOLDER_REVOKE
                            output.writeByte(3); output.writeLong(identity.mostSignificantBits)
                            output.writeLong(identity.leastSignificantBits); output.flush()
                            reply = input.read()
                            check(reply == 0) { "Guest did not revoke failed share" }
                            reply = null
                        }
                        phase = ShareControlFailure.Phase.HEARTBEAT
                        output.writeByte(1); output.flush()
                        reply = input.read()
                        check(reply == 0) { "Guest share owner lease unavailable" }
                        reply = null
                        phase = ShareControlFailure.Phase.REVOKED_FOLDERS
                        val count = input.readUnsignedShort()
                        require(count <= folders.size)
                        repeat(count) {
                            val identity = UUID(input.readLong(), input.readLong())
                            check(foldersById.containsKey(identity))
                            disableFolder(identity, "share.data.revoked")
                        }
                        progress.set(System.nanoTime())
                    }
                } catch (error: Exception) { if (!closed.get()) {
                    val now = System.nanoTime()
                    if (everReady.get()) controlLostAt.compareAndSet(0, now)
                    val lostAt = controlLostAt.get()
                    val outageMillis = if (lostAt == 0L) 0 else TimeUnit.NANOSECONDS.toMillis(now - lostAt).coerceAtLeast(0)
                    record("share.control.reconnecting", ShareControlFailure(phase, reply, attempt, outageMillis, error))
                } }
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
                try { serveFolder(folder, relay) }
                catch (error: Exception) { if (!closed.get()) record("share.sftp.failed", error) }
                finally { if (!closed.get()) disableFolder(identity, "share.data.unavailable"); relay.close() }
            }
            launch("share-reconnect") {
                folderWorkers[identity] = Thread.currentThread()
                dataLostAt[identity] = System.nanoTime()
                var attempt = 0
                var established = false
                while (!closed.get() && !relay.ownerClosed) {
                    var socket: GuestByteStream? = null
                    try {
                        socket = connect(); track(socket); folderSockets[identity] = socket
                        if (relay.ownerClosed) break
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
                            disableFolder(identity, "share.data.revoked")
                            break
                        }
                        check(accepted == 0) { "Guest rejected share stream generation" }
                        val link = relay.attach(socket.input, socket.output) { active.close() }
                        established = true
                        dataLostAt.remove(identity)
                        handshake.cancel(false)
                        attempt = 0
                        record("share.data.connected")
                        link.awaitClosed()
                        if (!closed.get()) {
                            dataLostAt.putIfAbsent(identity, System.nanoTime())
                            record("share.data.reconnecting")
                        }
                        } finally { handshake.cancel(false) }
                    } catch (error: Exception) {
                        if (!closed.get() && !relay.ownerClosed) record("share.data.reconnecting", error)
                        if (!relay.ownerClosed) dataLostAt.putIfAbsent(identity, System.nanoTime())
                        if (error is IllegalArgumentException) disableFolder(identity, "share.data.unavailable")
                    }
                    finally { socket?.let { folderSockets.remove(identity, it); sockets.remove(it); runCatching { it.close() } } }
                    if (!closed.get() && !relay.ownerClosed) retry(attempt++)
                }
                folderWorkers.remove(identity)
                dataLostAt.remove(identity)
            }
        }
    }

    private fun retry(attempt: Int) {
        // Startup retains its 50-second readiness budget; a long reconnect
        // backoff must not skip a broker that becomes ready near that deadline.
        val delay = if (!everReady.get()) 100L else longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)[attempt.coerceAtMost(4)]
        try { Thread.sleep(delay) }
        catch (_: InterruptedException) { /* close wakes all owner workers. */ }
    }
    private fun record(event: String, error: Throwable? = null) { runCatching { report(event, error) } }
    private fun expired(since: Long, now: Long) = since != 0L && now - since >= TimeUnit.MILLISECONDS.toNanos(recoveryTimeoutMillis)

    private fun disableFolder(identity: UUID, event: String) {
        if (closed.get() || !disabled.add(identity)) return
        mounted = false
        foldersById[identity]?.close()
        folderSockets.remove(identity)?.let { runCatching { it.close() } }
        folderWorkers[identity]?.interrupt()
        record(if (everReady.get()) event else "share.start.failed")
    }

    private fun ownerUnavailable() {
        if (!closed.compareAndSet(false, true)) return
        stopStreams()
        record("share.owner.unavailable")
    }

    private fun stopStreams() {
        mounted = false
        relays.forEach { it.close() }
        sockets.forEach { runCatching { it.close() } }
        workers.forEach { it.interrupt() }
        initialized.countDown()
    }
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
            stopStreams()
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
