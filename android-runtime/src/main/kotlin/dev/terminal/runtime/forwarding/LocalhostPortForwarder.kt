package dev.terminal.runtime.forwarding

import dev.terminal.runtime.api.GuestByteStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

/** Loopback-only host listener; guest raw-vsock proxying stays behind RuntimeBackend. */
class LocalhostPortForwarder(private val openGuestStream: suspend (Int) -> Result<GuestByteStream>) {
    private val listeners = ConcurrentHashMap<Int, ServerSocket>()

    fun start(port: Int): Result<Unit> = runCatching {
        require(port in 1024..65_535) { "Only non-privileged ports may be forwarded" }
        if (listeners.containsKey(port)) return@runCatching
        val listener = ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))
        listeners[port] = listener
        thread(name = "guest-port-$port", isDaemon = true) {
            while (!listener.isClosed) runCatching { listener.accept() }.getOrNull()?.let { client ->
                thread(isDaemon = true) { proxy(client, port) }
            }
        }
    }

    fun stop(port: Int) { listeners.remove(port)?.close() }
    fun stopAll() { listeners.keys.toList().forEach(::stop) }

    private fun proxy(client: Socket, port: Int) = client.use { socket ->
        runBlocking { openGuestStream(port) }.getOrNull()?.use { guest ->
            val upload = thread(isDaemon = true) { runCatching { socket.getInputStream().copyTo(guest.output); guest.output.flush() } }
            runCatching { guest.input.copyTo(socket.getOutputStream()); socket.getOutputStream().flush() }
            upload.join()
        }
    }
}
