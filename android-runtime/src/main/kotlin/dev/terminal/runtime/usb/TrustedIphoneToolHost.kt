package dev.terminal.runtime.usb

import android.content.Context
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDevice
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import dev.terminal.runtime.native.ApkNativeExecutableLoader
import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import dev.terminal.runtime.usb.TrustedIphoneToolPolicy.Error
import dev.terminal.runtime.usb.TrustedIphoneToolPolicy.Phase

/** Registered host tools only. Clients NEVER receive the connector or USB FD. */
internal class TrustedIphoneToolHost(
    private val context: Context, private val broker: UsbBroker, private val workChanged: (Int) -> Unit,
) : AutoCloseable {
    private val active = TrustedIphoneToolSlot<Operation>()
    private val operations = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Operation>>()
    @Volatile private var closed = false
    @Volatile private var cleanupBlocked = false
    init {
        runCatching {
            val root = File(context.cacheDir, "host-tool-work")
            if (root.exists()) {
                val metadata = Os.lstat(root.absolutePath)
                check(OsConstants.S_ISDIR(metadata.st_mode) && metadata.st_uid == Process.myUid())
                Os.chmod(root.absolutePath, 448)
                checkNotNull(root.canonicalFile.listFiles()).filter { it.name.matches(Regex("[0-9a-f-]{36}")) }
                    .forEach { TrustedIphoneToolPolicy.deleteTemporary(root, it) }
            }
        }.onFailure { cleanupBlocked = true }
    }

    @Synchronized fun start(tool: String, device: String, session: String, access: () -> Unit): JSONObject {
        check(!closed) { "USB_HOST_UNAVAILABLE" }; require(tool == "isideload") { "UNSUPPORTED_TOOL_OPERATION" }
        check(!cleanupBlocked) { "ARTIFACT_CLEANUP" }
        operations.entries.filter { it.value.second.conversation!!.terminal() && active.get() !== it.value.second }
            .take((operations.size - 31).coerceAtLeast(0)).forEach { operations.remove(it.key) }
        check(operations.size < 32) { "DEVICE_BUSY" }
        val record = HostToolConversation(UUID.randomUUID().toString(), tool, device)
        val operation = Operation(null, access, record)
        check(active.compareAndSet(null, operation)) { "DEVICE_BUSY" }
        var reserved = false; var counted = false
        try {
            val (manager, usb) = broker.reserveBootstrap(device) { operation.stop(Error.USB_DETACHED) }
            reserved = true; check(manager.hasPermission(usb)) { "USB_PERMISSION_DENIED" }
            workChanged(1); counted = true
            operations[record.id] = session to operation
            Thread({
                try { operation.run(manager, usb) }
                catch (failure: Exception) { operation.stop(symbol(operation.error()?.name ?: record.failure() ?: failure.message)) }
                finally {
                    try { operation.cleanup() } catch (_: Exception) { cleanupBlocked = true; record.fail("ARTIFACT_CLEANUP") }
                    finally { broker.releaseBootstrap(device); active.compareAndSet(operation, null); workChanged(-1) }
                    if (!record.terminal()) runCatching { record.complete() }.onFailure { record.fail("WORKER_PROTOCOL") }
                }
            }, "host-tool-${record.id}").start()
            return record.snapshot()
        } catch (failure: Exception) {
            operations.remove(record.id)
            try { operation.cleanup() } catch (_: Exception) { cleanupBlocked = true }
            finally {
                if (reserved) broker.releaseBootstrap(device)
                active.compareAndSet(operation, null); if (counted) workChanged(-1)
            }
            throw IllegalStateException(symbol(failure.message).name)
        }
    }
    private fun operation(session: String, id: String): Operation = operations[id]?.takeIf { it.first == session }?.second
        ?: error("NO_TOOL_OPERATION")
    fun status(session: String, id: String) = operation(session, id).conversation!!.snapshot()
    fun cancel(session: String, id: String): JSONObject {
        val op = operation(session, id); op.stop(Error.CANCELED); return op.conversation!!.snapshot()
    }
    fun select(session: String, id: String, choices: String): JSONObject {
        require(choices.length in 1..12900) { "INVALID_TOOL_INPUT" }
        val op = operation(session, id); op.conversation!!.submit(null, choices.split(',').toTypedArray(), false)
        return op.conversation.snapshot()
    }
    fun hostState() = org.json.JSONArray(operations.values.sortedBy { it.second.conversation!!.terminal() }
        .map { it.second.conversation!!.snapshot(true).put("session", it.first) }).toString()
    fun hostInput(id: String, nonce: String, values: Array<String>) {
        try {
            val conversation = operations[id]?.second?.conversation ?: error("NO_TOOL_OPERATION")
            val kind = conversation.snapshot(true).optJSONObject("prompt")?.optString("kind")
            conversation.submit(nonce, values, kind in setOf("LOGIN", "CODE"))
        }
        finally { values.fill("") }
    }
    fun hostCancel(id: String) { (operations[id]?.second ?: error("NO_TOOL_OPERATION")).stop(Error.CANCELED) }
    private fun symbol(code: String?): Error = runCatching { Error.valueOf(code.orEmpty()) }.getOrDefault(Error.WORKER_PROTOCOL)

    fun check(deviceKey: String, client: LocalSocket, access: () -> Unit): JSONObject {
        check(!closed) { "USB_HOST_UNAVAILABLE" }
        val operation = Operation(client, access)
        check(active.compareAndSet(null, operation)) { "DEVICE_BUSY" }
        var reserved = false
        var counted = false
        try {
            val (manager, device) = broker.reserveBootstrap(deviceKey) { operation.stop(Error.USB_DETACHED) }
            reserved = true
            workChanged(1); counted = true
            return operation.run(manager, device)
        } catch (failure: Exception) {
            val error = operation.error() ?: runCatching { Error.valueOf(failure.message.orEmpty()) }.getOrDefault(Error.WORKER_PROTOCOL)
            throw IllegalStateException(error.name)
        } finally {
            try { operation.cleanup() } finally {
                if (reserved) broker.releaseBootstrap(deviceKey)
                active.compareAndSet(operation, null)
                if (counted) workChanged(-1)
            }
        }
    }
    override fun close() { closed = true; active.get()?.stop(Error.CANCELED) }

    private inner class Operation(private val client: LocalSocket?, private val access: () -> Unit,
        val conversation: HostToolConversation? = null) {
        private val state = TrustedIphoneToolStateMachine()
        private val resources = mutableListOf<AutoCloseable>()
        private val children = mutableListOf<java.lang.Process>()
        private val timer = Executors.newSingleThreadScheduledExecutor()
        private var failure: Error? = null
        private var temporary: File? = null
        @Synchronized fun error(): Error? = failure
        @Synchronized private fun checkActive() { check(failure == null) { failure!!.name } }
        @Synchronized private fun <T : AutoCloseable> own(value: T): T {
            if (failure != null) { value.close(); error(failure!!.name) }
            resources.add(value); return value
        }
        @Synchronized private fun own(value: java.lang.Process): java.lang.Process {
            children.add(value)
            if (failure != null) { value.destroyForcibly(); error(failure!!.name) }
            return value
        }
        @Synchronized fun stop(error: Error) {
            if (failure != null || state.phase == Phase.COMPLETED) return
            failure = error; state.stop(error); conversation?.fail(error.name)
            children.forEach { runCatching { it.destroyForcibly() } }
            resources.asReversed().forEach { runCatching { it.close() } }
        }
        fun run(manager: UsbManager, device: UsbDevice): JSONObject {
            state.transition(Phase.WAITING_FOR_USB_PERMISSION)
            check(manager.hasPermission(device)) { "USB_PERMISSION_DENIED" }
            timer.schedule({ stop(if (conversation == null) Error.CANCELED else Error.INPUT_TIMEOUT) },
                if (conversation == null) 25 else 1800, TimeUnit.SECONDS)
            timer.scheduleWithFixedDelay({
                runCatching {
                    access()
                    if (client == null) return@runCatching
                    val poll = StructPollfd().apply { fd = client.fileDescriptor; events = 0 }
                    Os.poll(arrayOf(poll), 0)
                    if (poll.revents.toInt() and (OsConstants.POLLHUP or OsConstants.POLLERR or OsConstants.POLLNVAL) != 0) stop(Error.CANCELED)
                }.onFailure { stop(Error.CANCELED) }
            }, 100, 100, TimeUnit.MILLISECONDS)
            state.transition(Phase.CONNECTING)
            val usb = manager.openDevice(device) ?: error("USB_DETACHED")
            own(AutoCloseable { usb.close() })
            val directory = File(context.noBackupFilesDir, "iphone-pairing")
            check(directory.mkdirs() || directory.isDirectory) { "WORKER_PROTOCOL" }
            val metadata = Os.lstat(directory.absolutePath)
            check(OsConstants.S_ISDIR(metadata.st_mode) && metadata.st_uid == Process.myUid()) { "WORKER_PROTOCOL" }
            Os.chmod(directory.absolutePath, 448)
            val prefs = context.getSharedPreferences("iphone-host", Context.MODE_PRIVATE)
            val buid = synchronized(AndroidIphoneOperation::class.java) {
                prefs.getString("buid", null) ?: UUID.randomUUID().toString().also {
                    check(prefs.edit().putString("buid", it).commit())
                }
            }
            val loader = ApkNativeExecutableLoader(context)
            val endpoints = ParcelFileDescriptor.createSocketPair().map { own(it) }
            val address = "terminal-tool-mux-${UUID.randomUUID()}"
            val server = own(LocalServerSocket(address))
            val token = UUID.randomUUID().toString()
            val mux = launch(listOf(loader.resolvePackagedExecutable("iphone-worker").getOrThrow().absolutePath,
                "mux-owner", directory.canonicalPath, address, buid))
            mux.outputStream.use { it.write((token + "\n").toByteArray()); it.flush() }
            val owner = handshake(server, token)
            ParcelFileDescriptor.fromFd(usb.fileDescriptor).use { send(owner, arrayOf(it.fileDescriptor, endpoints[0].fileDescriptor), 'B') }
            require(line(mux.inputStream) == "{\"ok\":true,\"result\":{\"muxReady\":true}}") { "WORKER_PROTOCOL" }
            if (conversation != null) {
                state.transition(Phase.RUNNING)
                runInteractive(loader, endpoints)
                synchronized(this) { checkActive() }
                return conversation.snapshot()
            }
            val probeAddress = "terminal-tool-probe-${UUID.randomUUID()}"
            val listener = own(LocalServerSocket(probeAddress))
            val probeToken = UUID.randomUUID().toString()
            val probe = launch(listOf(loader.resolvePackagedExecutable("iphone-tool-probe").getOrThrow().absolutePath, "probe", probeAddress))
            probe.outputStream.use { it.write((probeToken + "\n").toByteArray()); it.flush() }
            send(handshake(listener, probeToken), arrayOf(endpoints[1].fileDescriptor), 'M')
            endpoints.forEach { it.close() }
            state.transition(Phase.RUNNING)
            val reply = JSONObject(line(probe.inputStream) ?: error("WORKER_PROTOCOL"))
            check(probe.waitFor(2, TimeUnit.SECONDS) && probe.exitValue() == 0) { "WORKER_PROTOCOL" }
            check(reply.keys().asSequence().toSet() == setOf("ok", "privateConnector", "publicMuxSocket") &&
                reply.getBoolean("ok") && reply.getBoolean("privateConnector") && !reply.getBoolean("publicMuxSocket")) { "WORKER_PROTOCOL" }
            synchronized(this) { checkActive(); state.transition(Phase.COMPLETED) }
            return JSONObject().put("tool", "idevice-check").put("completed", true)
                .put("privateConnector", true).put("publicMuxSocket", false).put("ideviceVersion", "0.1.65")
        }
        private fun privateDirectory(directory: File): File {
            check(directory.mkdirs() || directory.isDirectory) { "WORKER_PROTOCOL" }
            val metadata = Os.lstat(directory.absolutePath)
            check(OsConstants.S_ISDIR(metadata.st_mode) && metadata.st_uid == Process.myUid()) { "WORKER_PROTOCOL" }
            Os.chmod(directory.absolutePath, 448); return directory.canonicalFile
        }
        private fun runInteractive(loader: ApkNativeExecutableLoader, endpoints: List<ParcelFileDescriptor>) {
            val record = conversation!!
            val root = privateDirectory(File(context.cacheDir, "host-tool-work"))
            val temp = privateDirectory(File(root, record.id)); temporary = temp
            val storage = privateDirectory(File(context.noBackupFilesDir, "iphone-tool-state"))
            val address = "terminal-tool-${UUID.randomUUID()}"; val listener = own(LocalServerSocket(address))
            val token = UUID.randomUUID().toString()
            val worker = launch(listOf(loader.resolvePackagedExecutable("isideload").getOrThrow().absolutePath,
                "bootstrap", storage.absolutePath, address), mapOf("TMPDIR" to temp.absolutePath))
            worker.outputStream.write((token + "\n").toByteArray()); worker.outputStream.flush()
            send(handshake(listener, token), arrayOf(endpoints[1].fileDescriptor), 'M'); endpoints.forEach { it.close() }
            while (true) {
                checkActive()
                val event = JSONObject(line(worker.inputStream) ?: break); record.event(event)
                record.failure()?.let { error(it) }
                val command = when (event.getString("phase")) {
                    "WAITING_FOR_LOGIN" -> "start"
                    "WAITING_FOR_2FA" -> "code"
                    "WAITING_FOR_TEAM_SELECTION" -> "team"
                    "WAITING_FOR_CERTIFICATE_REVOCATION" -> "revoke"
                    else -> null
                }
                if (command != null) {
                    val values = record.awaitReply()
                    try {
                        val reply = JSONObject().put("op", command)
                        when (command) {
                            "start" -> reply.put("email", values[0]).put("password", values[1]).put("anisette", values[2])
                            "code" -> reply.put("code", values[0])
                            "team" -> reply.put("id", values[0])
                            "revoke" -> reply.put("ids", org.json.JSONArray(values.toList()))
                        }
                        val bytes = (reply.toString() + "\n").toByteArray()
                        try { worker.outputStream.write(bytes); worker.outputStream.flush() } finally { bytes.fill(0) }
                    } finally { values.fill("") }
                }
            }
            check(worker.waitFor(2, TimeUnit.SECONDS) && worker.exitValue() == 0) { "WORKER_PROTOCOL" }
        }
        private fun launch(arguments: List<String>, variables: Map<String, String> = emptyMap()) = own(ProcessBuilder(arguments).apply {
            environment().clear(); environment().putAll(variables); redirectError(File("/dev/null"))
        }.start())
        private fun handshake(listener: LocalServerSocket, token: String): LocalSocket {
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (SystemClock.elapsedRealtime() < deadline) {
                checkActive()
                val poll = StructPollfd().apply { fd = listener.fileDescriptor; events = OsConstants.POLLIN.toShort() }
                if (Os.poll(arrayOf(poll), 100) <= 0) continue
                val socket = own(listener.accept()); socket.soTimeout = 500
                val expected = (token + "\n").toByteArray(); val actual = ByteArray(expected.size)
                val valid = runCatching { socket.peerCredentials.uid == Process.myUid() &&
                    DataInputStream(socket.inputStream).let { it.readFully(actual); MessageDigest.isEqual(expected, actual) } }.getOrDefault(false)
                expected.fill(0); actual.fill(0)
                if (valid) { listener.close(); return socket }
                socket.close()
            }
            error("WORKER_AUTHENTICATION")
        }
        private fun send(socket: LocalSocket, descriptors: Array<java.io.FileDescriptor>, marker: Char) {
            synchronized(this) {
                checkActive(); socket.setFileDescriptorsForSend(descriptors)
                try { socket.outputStream.write(marker.code); socket.outputStream.flush() }
                finally { socket.setFileDescriptorsForSend(null) }
            }
        }
        fun cleanup() {
            timer.shutdownNow()
            synchronized(this) {
                children.forEach { runCatching { it.destroyForcibly() } }
                resources.asReversed().forEach { runCatching { it.close() } }
            }
            HostToolProcessCleanup.reap(children)
            children.forEach { child -> listOf(child.inputStream, child.outputStream, child.errorStream).forEach { runCatching { it.close() } } }
            temporary?.let { TrustedIphoneToolPolicy.deleteTemporary(it.parentFile!!, it) }
        }
    }
    companion object {
        internal fun line(input: java.io.InputStream): String? {
            val bytes = java.io.ByteArrayOutputStream()
            while (bytes.size() <= 8192) {
                val value = input.read()
                if (value < 0) { check(bytes.size() == 0) { "WORKER_PROTOCOL" }; return null }
                if (value == 10) return bytes.toByteArray().toString(Charsets.UTF_8)
                bytes.write(value)
            }
            error("WORKER_PROTOCOL")
        }
    }
}
