package dev.terminal.runtime.avf.system

import dev.terminal.runtime.api.GuestByteStream
import dev.terminal.runtime.api.SharedDirectoryExport
import dev.terminal.runtime.storage.ScopedSftpServer
import java.io.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A boot-owned control stream and one scoped SFTP stream per folder. No Android storage grant. */
internal class PrivateAvfShares(private val folders: List<SharedDirectoryExport>) : Closeable {
    private val closed = AtomicBoolean()
    private val streams = CopyOnWriteArrayList<GuestByteStream>()
    private val workers = CopyOnWriteArrayList<Thread>()
    @Volatile private var acquired = false
    @Volatile var ready = false
        private set

    fun start(connect: () -> GuestByteStream) {
        val timeout = WATCHDOG.schedule({ runCatching { close() } }, 50, TimeUnit.SECONDS)
        try { attach(connect) } finally { timeout.cancel(false) }
    }

    private fun attach(connect: () -> GuestByteStream) {
        check(!closed.get() && !ready && folders.isNotEmpty())
        require(folders.size <= 128)
        acquireStreams()
        val control = track(connect())
        val token = UUID.randomUUID()
        val output = DataOutputStream(control.output)
        output.writeInt(0x54534231); output.writeShort(1); output.writeShort(folders.size)
        output.writeLong(token.mostSignificantBits); output.writeLong(token.leastSignificantBits)
        folders.forEach {
            val id = UUID.fromString(it.id); val name = it.name.toByteArray(Charsets.US_ASCII)
            output.writeLong(id.mostSignificantBits); output.writeLong(id.leastSignificantBits)
            output.writeByte(name.size); output.write(name)
        }
        output.flush()
        check(control.input.read() == 0) { "Guest rejected private sharing configuration" }
        folders.forEach { folder ->
            val stream = track(connect())
            val header = DataOutputStream(stream.output)
            val id = UUID.fromString(folder.id)
            header.writeInt(0x54534631)
            header.writeLong(token.mostSignificantBits); header.writeLong(token.leastSignificantBits)
            header.writeLong(id.mostSignificantBits); header.writeLong(id.leastSignificantBits); header.flush()
            check(stream.input.read() == 0) { "Guest rejected private share stream" }
            val worker = Thread(null, {
                try { ScopedSftpServer(File(folder.directory)).use { it.serve(stream.input, stream.output) } }
                catch (_: Exception) { /* Closing the control stream makes guest mounts fail closed. */ }
                finally { runCatching { control.close() }; runCatching { stream.close() } }
            }, "avf-share", 256 * 1024).apply { isDaemon = true }
            startWorker(worker)
        }
        check(control.input.read() == 0) { "Guest private share mounts did not become ready" }
        check(!closed.get() && workers.all(Thread::isAlive)) { "Private share connection was lost" }
        ready = true
    }

    @Synchronized private fun track(stream: GuestByteStream): GuestByteStream {
        if (closed.get()) { stream.close(); error("Private shares were stopped") }
        streams += stream; return stream
    }

    @Synchronized private fun acquireStreams() {
        check(!closed.get()) { "Private shares were stopped" }
        check(STREAMS.tryAcquire(folders.size)) { "AVF share connection limit reached" }
        acquired = true
    }

    @Synchronized private fun startWorker(worker: Thread) {
        check(!closed.get()) { "Private shares were stopped" }
        workers += worker
        worker.start()
    }

    @Synchronized override fun close() {
        closed.set(true)
        streams.forEach { runCatching { it.close() } }
        workers.forEach(Thread::interrupt)
        val deadline = System.nanoTime() + 5_000_000_000L
        workers.forEach { worker ->
            val remaining = (deadline - System.nanoTime()) / 1_000_000L
            if (remaining > 0 && worker !== Thread.currentThread()) worker.join(remaining)
        }
        check(workers.none(Thread::isAlive)) { "Private share I/O has not stopped; folder leases retained" }
        if (acquired) { acquired = false; STREAMS.release(folders.size) }
    }
    private companion object {
        val STREAMS = Semaphore(256)
        val WATCHDOG = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "avf-share-timeout").apply { isDaemon = true } }
    }
}
