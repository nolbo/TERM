package dev.terminal.runtime.forwarding

import dev.terminal.runtime.api.GuestByteStream
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

interface PortForwardDriver : Closeable {
    fun replace(id: String, guestPort: Int, address: String, hostPort: Int, commit: () -> Unit = {}): Result<Unit>
    fun stop(id: String)
}

/** Only owns TERM's loopback sockets. Replacement binds first, preserving a working old mapping on failure. */
class LocalhostPortForwarder(
    private val openGuestStream: suspend (String, Int) -> Result<GuestByteStream>,
    private val connectionBudget: Semaphore = Semaphore(64),
    private val listenerBudget: Semaphore = Semaphore(128),
    private val onFailure: (String, Int, Boolean) -> Unit = { _, _, _ -> },
) : PortForwardDriver {
    private class Connection(val client: Socket) : Closeable {
        private var closed = false
        private var guest: GuestByteStream? = null
        @Synchronized fun attach(stream: GuestByteStream): Boolean {
            if (closed) { runCatching { stream.close() }; return false }
            guest = stream
            return true
        }
        @Synchronized override fun close() {
            closed = true
            runCatching { client.close() }
            runCatching { guest?.close() }
        }
    }
    private class Listener(val socket: ServerSocket) : Closeable {
        val connections = ConcurrentHashMap.newKeySet<Connection>()
        override fun close() {
            runCatching { socket.close() }
            connections.toList().forEach { it.close() }
        }
    }
    private val listeners = ConcurrentHashMap<String, Listener>()

    @Synchronized override fun replace(id: String, guestPort: Int, address: String, hostPort: Int, commit: () -> Unit): Result<Unit> = runCatching {
        require(guestPort in 1..65_535 && hostPort in 1024..65_535 && dev.terminal.protocol.PortPayloadCodec.validAddress(address)) { "INVALID_PORT" }
        if (listeners[id]?.socket?.localPort == hostPort) { commit(); return@runCatching }
        check(listeners.size < PortForwardRegistry.MAX_PER_ENVIRONMENT || listeners.containsKey(id)) { "SERVICE_LIMIT" }
        val acquiredSlot = !listeners.containsKey(id)
        if (acquiredSlot) check(listenerBudget.tryAcquire()) { "SERVICE_LIMIT" }
        val socket = try { ServerSocket() } catch (failure: Exception) { if (acquiredSlot) listenerBudget.release(); throw failure }
        try {
            socket.reuseAddress = true // Reconnect may follow accepted connections still in TCP TIME_WAIT.
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), hostPort), 16)
        } catch (failure: Exception) { socket.close(); if (acquiredSlot) listenerBudget.release(); throw failure }
        val listener = Listener(socket)
        try { commit() } catch (failure: Exception) { listener.close(); if (acquiredSlot) listenerBudget.release(); throw failure }
        val previous = listeners.put(id, listener)
        thread(name = "term-port-$hostPort", isDaemon = true) {
            try {
                while (!socket.isClosed) {
                    val client = socket.accept()
                    if (!connectionBudget.tryAcquire()) { client.close(); continue }
                    val connection = Connection(client)
                    listener.connections.add(connection)
                    if (socket.isClosed) connection.close()
                    thread(name = "term-proxy-$hostPort", isDaemon = true) {
                        try { proxy(connection, guestPort, address) }
                        catch (_: Exception) { if (!socket.isClosed) onFailure(id, hostPort, false) }
                        finally {
                            connection.close()
                            listener.connections.remove(connection)
                            connectionBudget.release()
                        }
                    }
                }
            } catch (_: Exception) {
                if (!socket.isClosed) {
                    listener.close()
                    if (listeners.remove(id, listener)) listenerBudget.release()
                    onFailure(id, hostPort, true)
                }
            }
        }
        previous?.close()
    }

    @Synchronized override fun stop(id: String) { listeners.remove(id)?.let { it.close(); listenerBudget.release() } }
    @Synchronized override fun close() { listeners.keys.toList().forEach(::stop) }

    private fun proxy(connection: Connection, port: Int, address: String) {
        val guest = runBlocking { withTimeout(5_000) { openGuestStream(address, port).getOrThrow() } }
        if (!connection.attach(guest)) return
        val socket = connection.client
        val upload = thread(name = "term-proxy-upload", isDaemon = true) {
            try { socket.getInputStream().copyTo(guest.output); guest.output.flush(); guest.shutdownOutput() }
            catch (_: Exception) { connection.close() }
        }
        try { guest.input.copyTo(socket.getOutputStream()); socket.getOutputStream().flush() }
        finally { connection.close(); upload.join(1000) }
    }
}
