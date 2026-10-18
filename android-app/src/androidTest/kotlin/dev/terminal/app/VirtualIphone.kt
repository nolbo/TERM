package dev.terminal.app

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import dev.terminal.runtime.usb.UsbBroker
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Test APK only. Simulates bounded iPhone broker results, NOT USB/iOS or Apple servers.
 * Every request authenticates against a real UsbBroker session; no device is opened.
 * No product constructor, endpoint, device enumeration or permission check is changed.
 */
internal class VirtualIphone(private val real: UsbBroker) : AutoCloseable {
    val session = "virtual-iphone-${UUID.randomUUID()}"
    val environment = real.register(session, "Test")
    private val address = "virtual-iphone-${UUID.randomUUID()}"
    private val listener = LocalServerSocket(address)
    val clientEnvironment = environment + ("TERMINAL_USB_SOCKET" to address)
    val files = ConcurrentHashMap<String, ByteArray>()
    val requests = CopyOnWriteArrayList<String>() // Operations only, never tokens or payloads.
    val errors = CopyOnWriteArrayList<Throwable>()
    private val clients = ConcurrentHashMap.newKeySet<LocalSocket>()
    private val executor = Executors.newFixedThreadPool(3)
    @Volatile private var closed = false
    @Volatile var fault = "NONE"
    @Volatile var pairError: String? = null
    @Volatile var uploads = 0
    companion object { const val DEVICE = "virtual-iphone"; const val FRAME = 48 * 1024 }

    init {
        files["/sample.bin"] = byteArrayOf(0, 1, -1, -128)
        executor.execute {
            try {
                while (!closed) {
                    val socket = listener.accept()
                    if (closed) { socket.close(); break }
                    clients.add(socket)
                    executor.execute { serve(socket) }
                }
            } catch (e: Exception) { if (!closed) errors.add(e) }
        }
    }

    private fun read(socket: LocalSocket): JSONObject {
        val input = DataInputStream(socket.inputStream)
        val size = input.readInt()
        require(size in 1..FRAME)
        return JSONObject(String(ByteArray(size).also(input::readFully), Charsets.UTF_8))
    }
    private fun reply(socket: LocalSocket, result: JSONObject, ok: Boolean = true) {
        val value = if (ok) JSONObject().put("ok", true).put("result", result)
            else JSONObject().put("ok", false).put("error", result.getString("error"))
        val bytes = value.toString().toByteArray()
        require(bytes.size in 1..FRAME)
        val output = DataOutputStream(socket.outputStream)
        // Exercise real read_exact paths with deliberately fragmented framing.
        val header = java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()
        for (byte in header) { output.write(byte.toInt()); output.flush() }
        for (chunk in bytes.asList().chunked(7)) { output.write(chunk.toByteArray()); output.flush() }
    }
    private fun authenticate(request: JSONObject): Boolean = LocalSocket().use { socket ->
        socket.connect(LocalSocketAddress(environment.getValue("TERMINAL_USB_SOCKET")))
        socket.soTimeout = 3000
        val bytes = JSONObject().put("op", "list").put("version", request.get("version"))
            .put("token", request.get("token")).toString().toByteArray()
        DataOutputStream(socket.outputStream).apply { writeInt(bytes.size); write(bytes); flush() }
        read(socket).getBoolean("ok")
    }
    private fun authorized(socket: LocalSocket, request: JSONObject): Boolean {
        if (authenticate(request)) return true
        reply(socket, JSONObject().put("error", "SESSION_EXPIRED"), false)
        return false
    }
    private fun serve(socket: LocalSocket) {
        try {
            check(socket.peerCredentials.uid == android.os.Process.myUid())
            socket.soTimeout = 5000
            while (!closed) {
                val request = read(socket)
                if (!authorized(socket, request)) return
                val op = request.getString("op")
                val action = request.optString("action")
                requests.add("$op/$action")
                if (request.has("device") && request.getString("device") != DEVICE) {
                    reply(socket, JSONObject().put("error", "USB_DETACHED"), false); continue
                }
                when {
                    op == "list" -> reply(socket, JSONObject().put("devices", JSONArray().put(
                        JSONObject().put("id", DEVICE).put("iphone", true).put("name", "Synthetic iPhone"))))
                    op == "tooling" && action == "check" -> reply(socket, JSONObject().put("tool", "idevice-check")
                        .put("completed", true).put("privateConnector", true).put("publicMuxSocket", false))
                    op == "iphone" && action in setOf("pair", "info") -> {
                        val error = pairError
                        if (error != null) reply(socket, JSONObject().put("error", error), false)
                        else reply(socket, JSONObject().put("paired", true).put("name", "Synthetic iPhone").put("ios", "17.4"))
                    }
                    op == "iphone" && action == "afc-get" -> {
                        val data = files[request.getString("path")] ?: error("Missing test fixture")
                        reply(socket, JSONObject().put("event", "start").put("size", data.size))
                        if (fault == "DETACH_READ") return
                        if (fault == "OVERSIZE") { DataOutputStream(socket.outputStream).writeInt(FRAME + 1); return }
                        reply(socket, JSONObject().put("event", "data").put("offset", if (fault == "BAD_OFFSET") 1 else 0)
                            .put("data", data.joinToString("") { "%02x".format(it.toInt() and 255) }))
                        reply(socket, JSONObject().put("completed", true).put("bytes", data.size))
                    }
                    op == "iphone" && action == "afc-put" -> {
                        val size = request.getInt("size"); require(size in 0..65536)
                        val name = request.getString("name"); require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")))
                        val path = "/TerminalImports/${UUID.randomUUID()}-$name"
                        uploads++; files[path] = ByteArray(0)
                        reply(socket, JSONObject().put("event", "ready").put("size", size).put("path", path))
                        var offset = 0
                        while (offset < size) {
                            val chunk = read(socket)
                            if (!authorized(socket, chunk)) return
                            require(chunk.getString("op") == "iphone-data" && chunk.getInt("offset") == offset)
                            val hex = chunk.getString("data"); require(hex.matches(Regex("(?:[0-9a-f]{2}){1,16384}")))
                            val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                            require(offset + bytes.size <= size)
                            files[path] = files.getValue(path) + bytes; offset += bytes.size
                            // Device accepted bytes but its acknowledgment never reached the caller.
                            if (fault == "DETACH_WRITE") return
                            reply(socket, JSONObject().put("event", "accepted").put("offset", offset))
                        }
                        reply(socket, JSONObject().put("completed", true).put("bytes", size))
                    }
                    else -> reply(socket, JSONObject().put("error", "UNSUPPORTED_IPHONE_OPERATION"), false)
                }
            }
        } catch (_: EOFException) { /* Client exit/cancel: no replay or target cleanup commands. */ }
        catch (_: IOException) { /* Injected detach/client cancellation. */ }
        catch (e: Exception) { if (!closed) errors.add(e) }
        finally { clients.remove(socket); socket.close() }
    }

    override fun close() {
        closed = true
        // Wake accept explicitly: close from another Android thread need not interrupt it.
        runCatching { LocalSocket().use { it.connect(LocalSocketAddress(address)) } }
        listener.close(); clients.forEach { it.close() }
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Simulator threads leaked" }
        real.unregister(session)
        check(errors.isEmpty()) { "Simulator failed: ${errors.first().javaClass.simpleName}" }
    }
}
