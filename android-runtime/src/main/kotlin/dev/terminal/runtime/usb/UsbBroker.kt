package dev.terminal.runtime.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import org.json.JSONArray
import org.json.JSONObject

/** Service-owned broker. UI may approve requests but never owns a connection. */
class UsbBroker(private val context: Context, bootstrapWorkChanged: (Int) -> Unit = {}) : AutoCloseable {
    private val manager = context.getSystemService(UsbManager::class.java)
    private val supported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
    private val socketName = "terminal-usb-${UUID.randomUUID()}"
    private val server = LocalServerSocket(socketName)
    private val workers = Executors.newFixedThreadPool(UsbPolicy.MAX_CLIENTS)
    private val slots = Semaphore(UsbPolicy.MAX_CLIENTS)
    private val deadlines = Executors.newSingleThreadScheduledExecutor()
    private val sockets = ConcurrentHashMap.newKeySet<LocalSocket>()
    @Volatile private var closed = false
    private data class Session(val id: String, val runtime: String)
    private data class Device(val key: String, val usb: UsbDevice)
    private data class Grant(val session: String, val device: String, val interfaceId: Int, val serial: Boolean,
        val iphone: String = "", val scope: String = "", val driver: String = "", val port: Int = 0,
        val configuration: Int = 0, val alternate: Int = 0, val tool: String = "")
    private data class Pending(val id: String, val grant: Grant, val created: Long,
        var approved: Boolean = false, var permission: PendingIntent? = null)
    private val sessions = mutableMapOf<String, Session>()
    private val devices = mutableMapOf<String, Device>()
    private val grants = mutableSetOf<Grant>()
    private val pending = mutableMapOf<String, Pending>()
    private val connections = mutableMapOf<AndroidUsbConnection, Grant>()
    private val iphoneOperations = mutableMapOf<AndroidIphoneOperation, Grant>()
    private val bootstrapReservations = mutableMapOf<String, () -> Unit>()
    internal val iphoneTools = TrustedIphoneToolHost(context, this, bootstrapWorkChanged)
    private val serialConnections = mutableMapOf<AndroidSerialConnection, Grant>()
    private val configurationOperations = mutableMapOf<String, AutoCloseable>()
    private val recentErrors = ArrayDeque<String>()
    private val rawSockets = mutableMapOf<AndroidUsbConnection, LocalSocket>()
    private val documentTrees = RemovableDocuments(context)
    private val fileConnections = mutableMapOf<DocumentSession, Pair<Grant, LocalSocket>>()
    private val providerCancellation = java.util.concurrent.ThreadPoolExecutor(
        UsbPolicy.MAX_CLIENTS, UsbPolicy.MAX_CLIENTS, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue(UsbPolicy.MAX_CLIENTS),
        java.util.concurrent.ThreadPoolExecutor.DiscardPolicy())
    // Only this bundled script is bound into Rootless; no host directory or raw descriptor.
    val clientFile: File = File.createTempFile("terminal-usb-", ".py", context.filesDir).apply {
        context.assets.open("usb/terminal-usb.pyz").use { input -> outputStream().use(input::copyTo) }
        setReadable(true, true)
        setExecutable(true, true)
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // A detach invalidates the old generation even if the same USB address is reused.
            @Suppress("DEPRECATION")
            val detached = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            synchronized(this@UsbBroker) {
                devices.remove(detached.deviceName)?.let { removed ->
                    bootstrapReservations[removed.key]?.invoke()
                    revoke { it.device == removed.key }
                }
                refresh()
            }
        }
    }
    private val storageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            synchronized(this@UsbBroker) {
                // Media broadcasts do not provide a trustworthy USB identity. Conservatively
                // invalidate every removable-tree grant; persisted picker access alone is not a grant.
                (grants + pending.values.map { it.grant }).filter { it.driver.startsWith("storage-") }
                    .map { it.device }.distinct().forEach { documentTrees.forgetHandles(it) }
                revoke { it.driver.startsWith("storage-") }
            }
        }
    }

    init {
        active = this
        ContextCompat.registerReceiver(context, receiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(context, storageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_UNMOUNTED); addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED); addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        Thread({
            while (!closed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                if (closed || !slots.tryAcquire()) { socket.close(); continue }
                sockets += socket
                try { workers.execute {
                    try { serve(socket) } finally { sockets -= socket; runCatching { socket.close() }; slots.release() }
                } } catch (_: java.util.concurrent.RejectedExecutionException) {
                    sockets -= socket; socket.close(); slots.release()
                }
            }
        }, "terminal-usb-accept").apply { isDaemon = true; start() }
    }

    @Synchronized fun register(sessionId: String, runtime: String): Map<String, String> {
        check(!closed)
        val token = UUID.randomUUID().toString()
        sessions[token] = Session(sessionId, runtime)
        return mapOf("TERMINAL_USB_SOCKET" to socketName, "TERMINAL_USB_TOKEN" to token)
    }

    @Synchronized fun unregister(sessionId: String) {
        sessions.entries.removeAll { it.value.id == sessionId }
        revoke { it.session == sessionId }
    }

    private fun revoke(predicate: (Grant) -> Boolean) {
        fileConnections.filterValues { predicate(it.first) }.forEach { (store, pair) ->
            runCatching { pair.second.close() }
            store.cancelAsync(providerCancellation)
        }
        configurationOperations.filterKeys { key -> grants.any { it.device == key && predicate(it) } }
            .values.toList().forEach { runCatching { it.close() } }
        grants.removeAll(predicate)
        pending.entries.removeAll { (_, request) ->
            predicate(request.grant).also { if (it) request.permission?.cancel() }
        }
        connections.entries.filter { predicate(it.value) }.forEach { (connection, _) ->
            runCatching { connection.close() }
            runCatching { rawSockets[connection]?.close() }
        }
        iphoneOperations.entries.filter { predicate(it.value) }.forEach { (operation, _) ->
            operation.close()
        }
        // Keep the reservation until its serving thread actually finishes.
        serialConnections.filterValues(predicate).keys.forEach { it.cancel() }
    }

    private fun refresh() {
        iphoneOperations.keys.removeAll { it.isStopped() }
        (grants + pending.values.map { it.grant }).filter { it.driver.startsWith("storage-") }
            .map { it.device }.distinct().filter { !documentTrees.available(it) }.forEach { root ->
            revoke { it.device == root }; documentTrees.forgetHandles(root)
        }
        val attached = if (supported) manager?.deviceList.orEmpty() else emptyMap()
        devices.entries.filter { (name, _) -> name !in attached }.forEach { (name, device) ->
            devices.remove(name); bootstrapReservations[device.key]?.invoke(); revoke { it.device == device.key }
        }
        attached.forEach { (name, usb) -> devices.getOrPut(name) { Device(UUID.randomUUID().toString(), usb) } }
        val now = SystemClock.elapsedRealtime()
        pending.entries.removeAll { (_, request) ->
            (now - request.created > 120_000).also { if (it) request.permission?.cancel() }
        }
    }

    private fun device(key: String): Device = devices.values.firstOrNull { it.key == key } ?: error("DEVICE_DISCONNECTED")
    private fun Grant.accessScope() = UsbAccessScope(device, interfaceId, interfaceId < 0 || (serial && driver.isEmpty()))

    /** Host-only snapshot: no pairing/authorization secrets, raw paths or descriptors. */
    @Synchronized fun state(): String {
        refresh()
        return JSONObject().put("supported", supported).put("devices", JSONArray(devices.values.map {
            it.usb.description(it.key)
        })).put("storageRoots", documentTrees.roots()).put("diagnostics", diagnostics()).put("requests", JSONArray(pending.values.map {
            grantJson(it.grant).put("request", it.id).put("waitingForAndroid", it.approved)
        })).put("grants", JSONArray(grants.map(::grantJson))).toString()
    }

    private fun grantJson(grant: Grant): JSONObject {
        val session = sessions.values.firstOrNull { it.id == grant.session }
        val usb = devices.values.firstOrNull { it.key == grant.device }?.usb
        return JSONObject().put("session", grant.session).put("runtime", session?.runtime ?: "")
            .put("device", grant.device).put("interface", grant.interfaceId).put("serial", grant.serial)
            .put("iphone", grant.iphone)
            .put("tool", grant.tool)
            .put("scope", grant.scope)
            .put("driver", grant.driver).put("port", grant.port)
            .put("configuration", grant.configuration).put("alternate", grant.alternate)
            .put("active", (grant.tool.isNotEmpty() && grant.device in bootstrapReservations) || connections.values.contains(grant) || serialConnections.values.contains(grant) ||
                iphoneOperations.values.contains(grant) || fileConnections.values.any { it.first == grant })
            .put("vendor", usb?.vendorId ?: 0).put("product", usb?.productId ?: 0)
    }

    /** Only the non-exported application's AIDL/UI path can call this. */
    @Synchronized fun approve(requestId: String): String = runCatching {
        refresh()
        val request = pending[requestId] ?: error("REQUEST_EXPIRED")
        if (request.grant.driver.startsWith("storage-")) {
            check(documentTrees.available(request.grant.device)) { "STORAGE_UNAVAILABLE" }
            if (request.grant.driver == "storage-write") check(documentTrees.writable(request.grant.device)) { "STORAGE_READ_ONLY" }
            grants += request.grant; pending.remove(requestId)
            return@runCatching ""
        }
        val usb = device(request.grant.device).usb
        request.approved = true
        if (manager.hasPermission(usb)) finishPermission(requestId) else if (request.permission == null) {
            val intent = Intent(context, UsbPermissionReceiver::class.java)
                .setAction("${context.packageName}.USB_PERMISSION.$requestId").putExtra("request", requestId)
            val permission = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ONE_SHOT)
            request.permission = permission
            manager.requestPermission(usb, permission)
        }
        ""
    }.getOrElse { it.message ?: "USB_PERMISSION_FAILED" }

    @Synchronized fun finishPermission(requestId: String) {
        refresh()
        val request = pending[requestId] ?: return
        if (!request.approved) return
        pending.remove(requestId)
        val usb = runCatching { device(request.grant.device).usb }.getOrNull() ?: return
        if (manager.hasPermission(usb)) grants += request.grant
    }

    @Synchronized fun deny(requestId: String) { pending.remove(requestId)?.permission?.cancel() }
    @Synchronized fun revokeSession(sessionId: String) { revoke { it.session == sessionId } }
    fun addStorageTree(uri: String, flags: Int): String = runCatching { documentTrees.add(uri, flags); "" }.getOrElse { it.message ?: "STORAGE_ERROR" }
    @Synchronized fun removeStorageTree(id: String) { revoke { it.device == id }; documentTrees.remove(id) }

    private fun requireGrant(grant: Grant) {
        if (grant in grants) return
        if (pending.values.none { it.grant == grant }) {
            check(pending.size < UsbPolicy.MAX_PENDING) { "TOO_MANY_REQUESTS" }
            val id = UUID.randomUUID().toString()
            pending[id] = Pending(id, grant, SystemClock.elapsedRealtime())
        }
        error("APPROVAL_REQUIRED: open Settings > USB devices, approve this session, then retry")
    }

    private fun iphone(request: JSONObject, socket: LocalSocket): JSONObject {
        val operation = synchronized(this) {
            refresh()
            val session = sessions[request.getString("token")] ?: error("SESSION_EXPIRED")
            require(request.int("version") == 1) { "UNSUPPORTED_VERSION" }
            val action = IphonePolicy.operation(request.getString("action"))
            val extra = when (action) {
                "afc-list", "afc-get" -> setOf("path")
                "afc-put" -> setOf("name", "size")
                else -> emptySet()
            }
            require(request.keys().asSequence().toSet() == setOf("version", "token", "op", "device", "action") + extra) {
                "INVALID_IPHONE_REQUEST"
            }
            val scope = when (action) {
                "afc-list", "afc-get" -> IphonePolicy.path(request.getString("path"))
                "afc-put" -> "/TerminalImports"
                else -> ""
            }
            val size = if (action == "afc-put") IphonePolicy.size(request.get("size")) else 0
            val name = if (action == "afc-put") IphonePolicy.uploadName(request.getString("name")) else ""
            check(supported) { "USB_HOST_UNSUPPORTED" }
            val target = device(request.getString("device"))
            require(IphonePolicy.candidate(target.usb.vendorId, target.usb.productId)) { "NOT_SUPPORTED_IPHONE" }
            val grant = Grant(session.id, target.key, -1, false, action, scope)
            requireGrant(grant)
            check(target.key !in configurationOperations && target.key !in bootstrapReservations) { "DEVICE_BUSY" }
            check(manager.hasPermission(target.usb)) { "ANDROID_PERMISSION_REQUIRED" }
            check(connections.values.none { it.device == target.key } &&
                serialConnections.values.none { it.device == target.key } &&
                iphoneOperations.values.none { it.device == target.key }) { "DEVICE_BUSY" }
            val path = if (action == "afc-put") "$scope/${UUID.randomUUID()}-$name" else scope
            AndroidIphoneOperation(context, manager, target.usb, action, path, size).also { iphoneOperations[it] = grant }
        }
        fun checkAccess() = synchronized(this) {
            refresh()
            val grant = iphoneOperations[operation] ?: error("GRANT_REVOKED_OR_DEVICE_DISCONNECTED")
            check(grant in grants && sessions[request.getString("token")]?.id == grant.session) { "GRANT_REVOKED" }
            check(manager.hasPermission(device(grant.device).usb)) { "ANDROID_PERMISSION_REQUIRED" }
        }
        try {
            // Trust/Lockdown can block. Never hold the broker lock across native I/O:
            // UI revocation, detach and other sessions must remain responsive.
            val result = operation.execute(socket, event = { event ->
                checkAccess()
                val bytes = JSONObject().put("ok", true).put("result", event).toString().toByteArray()
                check(bytes.size <= UsbPolicy.MAX_FRAME)
                DataOutputStream(socket.outputStream).apply { writeInt(bytes.size); write(bytes); flush() }
            }, upload = { offset ->
                checkAccess()
                val input = DataInputStream(socket.inputStream)
                val length = input.readInt()
                require(length in 1..UsbPolicy.MAX_FRAME) { "INVALID_FILE_CHUNK" }
                val json = String(ByteArray(length).also(input::readFully), Charsets.UTF_8)
                UsbPolicy.flatObject(json)
                val chunk = JSONObject(json)
                require(chunk.keys().asSequence().toSet() == setOf("version", "token", "op", "offset", "data") &&
                    chunk.int("version") == 1 && chunk.getString("token") == request.getString("token") &&
                    chunk.getString("op") == "iphone-data" && chunk.int("offset") == offset) { "INVALID_FILE_CHUNK" }
                checkAccess()
                IphonePolicy.hex(chunk.getString("data"))
            })
            checkAccess()
            return JSONObject().put("ok", true).put("result", result)
        } finally {
            operation.close()
            // Keep the device reservation until the process has actually exited.
            // Reaping never blocks permission UI or other device requests.
            if (operation.awaitExit()) synchronized(this) { iphoneOperations.remove(operation) }
        }
    }

    private fun serial(socket: LocalSocket, initial: JSONObject) {
        var opened: AndroidSerialConnection? = null
        fun reply(result: JSONObject) {
            val bytes = result.toString().toByteArray(Charsets.UTF_8)
            check(bytes.size <= UsbPolicy.MAX_FRAME)
            DataOutputStream(socket.outputStream).apply { writeInt(bytes.size); write(bytes); flush() }
        }
        fun success(result: JSONObject) = reply(JSONObject().put("ok", true).put("result", result))
        try {
            SerialRequest.validate(initial)
            val token = initial.getString("token")
            val stream = synchronized(this) {
                refresh()
                val session = sessions[token] ?: error("SESSION_EXPIRED")
                check(supported) { "USB_HOST_UNSUPPORTED" }
                val target = device(initial.getString("device"))
                val driver = SerialDrivers.probe(target.usb) ?: error("NO_SERIAL_DRIVER")
                val name = SerialDrivers.name(driver)
                require(initial.getString("driver") == name) { "SERIAL_DRIVER_MISMATCH" }
                val port = initial.getInt("port")
                require(port in driver.ports.indices) { "SERIAL_PORT_NOT_FOUND" }
                // Only verified port-index/interface-ID mappings can share a device.
                val interfaces = target.usb.interfaces()
                val isolatedInterface = UsbAccessScope.isolatedPort(name, target.usb.configurationCount,
                    interfaces.map { it.id }, interfaces.map { it.alternateSetting }, driver.ports.size, port)
                val grant = Grant(session.id, target.key, isolatedInterface, true, driver = name, port = port)
                requireGrant(grant)
                check(manager.hasPermission(target.usb)) { "ANDROID_PERMISSION_REQUIRED" }
                check(target.key !in configurationOperations && target.key !in bootstrapReservations && connections.values.none { it.accessScope().conflicts(grant.accessScope()) } &&
                    iphoneOperations.values.none { it.device == target.key } &&
                    serialConnections.values.none { it.accessScope().conflicts(grant.accessScope()) }) { "DEVICE_BUSY" }
                AndroidSerialConnection(manager, target.usb, driver, port, socket).also {
                    opened = it; serialConnections[it] = grant
                }
            }
            fun checkAccess() = synchronized(this) {
                refresh()
                val grant = serialConnections[stream] ?: error("SERIAL_CONNECTION_LOST")
                check(sessions[token]?.id == grant.session && grant in grants) { "GRANT_REVOKED" }
                check(manager.hasPermission(device(grant.device).usb)) { "ANDROID_PERMISSION_REQUIRED" }
                stream.checkActive()
            }
            fun bounded(action: () -> Unit) {
                val timer = deadlines.schedule({ stream.cancel() }, 15, java.util.concurrent.TimeUnit.SECONDS)
                try { checkAccess(); action(); checkAccess() } finally { timer.cancel(false) }
            }
            bounded { stream.open(); success(JSONObject().put("opened", true).put("driver", initial.getString("driver")).put("port", initial.getInt("port"))) }
            val input = DataInputStream(socket.inputStream)
            var configured = false
            while (!closed) {
                val size = input.readInt()
                require(size in 1..UsbPolicy.MAX_FRAME) { "FRAME_TOO_LARGE" }
                val text = String(ByteArray(size).also(input::readFully), Charsets.UTF_8)
                UsbPolicy.flatObject(text)
                val request = JSONObject(text)
                val op = SerialRequest.validate(request)
                check(request.getString("token") == token) { "SESSION_MISMATCH" }
                bounded {
                    val result = when (op) {
                        "configure" -> {
                            stream.configure(request.getInt("baud"), request.getInt("bits"), request.getInt("stops"),
                                request.getInt("parity"), request.getBoolean("dtr"), request.getBoolean("rts"))
                            configured = true
                            JSONObject().put("configured", true)
                        }
                        "read" -> {
                            check(configured) { "SERIAL_CONFIGURE_REQUIRED" }
                            val bytes = ByteArray(request.getInt("length"))
                            val count = stream.read(bytes, request.getInt("timeout"))
                            JSONObject().put("count", count).put("data", bytes.take(count).joinToString("") { "%02x".format(it.toInt() and 255) })
                        }
                        "write" -> {
                            check(configured) { "SERIAL_CONFIGURE_REQUIRED" }
                            stream.write(SerialRequest.bytes(request), request.getInt("timeout"))
                        }
                        "status" -> stream.status()
                        "ping" -> JSONObject().put("alive", true)
                        "close" -> JSONObject().put("closed", true)
                        else -> error("ALREADY_OPEN")
                    }
                    checkAccess()
                    success(result)
                }
                if (op == "close") break
            }
        } catch (error: Exception) {
            val message = when (error) {
                is java.io.IOException -> "SERIAL_IO_ERROR_CONNECTION_CLOSED"
                is UnsupportedOperationException -> "SERIAL_OPTION_UNSUPPORTED"
                else -> error.message?.take(300) ?: "SERIAL_ERROR"
            }
            runCatching { reply(JSONObject().put("ok", false).put("error", message)) }
        } finally {
            opened?.let { stream ->
                stream.finish()
                synchronized(this) { serialConnections.remove(stream) }
            }
        }
    }

    private class RawClient(var connection: AndroidUsbConnection? = null)

    private fun files(socket: LocalSocket, initial: JSONObject) {
        var store: DocumentSession? = null
        fun send(result: JSONObject) {
            val data = result.toString().toByteArray()
            require(data.size <= UsbPolicy.MAX_FRAME)
            DataOutputStream(socket.outputStream).apply { writeInt(data.size); write(data); flush() }
        }
        fun emit(result: JSONObject) = send(JSONObject().put("ok", true).put("result", result))
        val input = DataInputStream(socket.inputStream)
        fun receive(): JSONObject {
            val size = input.readInt()
            require(size in 1..UsbPolicy.MAX_FRAME) { "FRAME_TOO_LARGE" }
            val text = String(ByteArray(size).also(input::readFully), Charsets.UTF_8)
            UsbPolicy.flatObject(text)
            return JSONObject(text)
        }
        try {
            FileRequest.validate(initial)
            val token = initial.getString("token")
            val root = initial.getString("root")
            val write = initial.getString("mode") == "write"
            val session = synchronized(this) {
                refresh()
                val identity = sessions[token] ?: error("SESSION_EXPIRED")
                check(documentTrees.available(root)) { "STORAGE_UNAVAILABLE" }
                if (write) check(documentTrees.writable(root)) { "STORAGE_READ_ONLY" }
                val grant = Grant(identity.id, root, -1, false, driver = if (write) "storage-write" else "storage-read", scope = documentTrees.label(root))
                requireGrant(grant)
                check(fileConnections.values.none { it.first.device == root && (write || it.first.driver == "storage-write") }) { "STORAGE_BUSY" }
                documentTrees.session(root, write).also { store = it; fileConnections[it] = grant to socket }
            }
            fun access() = synchronized(this) {
                val grant = fileConnections[session]?.first ?: error("STORAGE_SESSION_LOST")
                check(grant in grants && sessions[token]?.id == grant.session && documentTrees.available(root)) { "STORAGE_ACCESS_REVOKED" }
            }
            fun event(value: JSONObject) { access(); emit(value) }
            event(JSONObject().put("opened", true).put("root", "root"))
            while (!closed) {
                val request = receive()
                FileRequest.validate(request)
                check(request.getString("token") == token) { "SESSION_MISMATCH" }
                val timer = deadlines.schedule({
                    runCatching { socket.close() }; session.cancelAsync(providerCancellation)
                }, 125, java.util.concurrent.TimeUnit.SECONDS)
                try {
                    access()
                    when (request.getString("op")) {
                        "ls" -> event(session.list(request.getString("parent")))
                        "get" -> session.get(request.getString("handle"), ::event)
                        "put" -> session.put(request.getString("parent"), request.getString("name"), request.int("size"), ::event) { offset ->
                            val chunk = receive()
                            FileRequest.validate(chunk)
                            require(chunk.getString("token") == token && chunk.getString("op") == "file-data" && chunk.int("offset") == offset) { "INVALID_FILE_CHUNK" }
                            access(); IphonePolicy.hex(chunk.getString("data"))
                        }
                        "ping" -> event(JSONObject().put("alive", true))
                        "close" -> { event(JSONObject().put("closed", true)); break }
                        else -> error("INVALID_FILE_OPERATION")
                    }
                } finally { timer.cancel(false) }
            }
        } catch (error: Exception) {
            runCatching { send(JSONObject().put("ok", false).put("error", when (error) {
                is java.io.IOException -> "STORAGE_IO_ERROR_PARTIAL_UPLOAD_POSSIBLE"
                is SecurityException -> "STORAGE_PERMISSION_LOST"
                else -> error.message?.takeIf { it.matches(Regex("[A-Z_ :a-z0-9>,.-]{1,300}")) } ?: "STORAGE_ERROR"
            })) }
        } finally { store?.let { it.cancel(); synchronized(this) { fileConnections.remove(it) } } }
    }

    private fun raw(request: JSONObject, socket: LocalSocket, owner: RawClient): JSONObject {
        RawRequest.validate(request)
        val op = request.getString("op")
        if (op == "configuration") { check(owner.connection == null) { "ALREADY_OPEN" }; return changeConfiguration(request) }
        val opened = synchronized(this) {
            refresh()
            val session = sessions[request.getString("token")] ?: error("SESSION_EXPIRED")
            require(request.int("version") == 1) { "UNSUPPORTED_VERSION" }
            if (op == "list") return JSONObject().put("ok", true).put("result", JSONObject().put("supported", supported)
                .put("session", session.id).put("devices", JSONArray(devices.values.map { it.usb.description(it.key) })))
            if (op == "diagnostics") return JSONObject().put("ok", true).put("result", diagnostics())
            if (op == "storage-roots") return JSONObject().put("ok", true).put("result", JSONObject().put("roots", documentTrees.roots()))
            if (op in setOf("open", "raw-open")) {
                check(owner.connection == null) { "ALREADY_OPEN" }
                check(supported) { "USB_HOST_UNSUPPORTED" }
                val target = device(request.getString("device"))
                val interfaceId = request.int("interface")
                val serial = op == "open" && request.getBoolean("serial")
                val configId = if (op == "raw-open") request.int("configuration") else target.usb.getConfiguration(0).id
                val alt = if (op == "raw-open") request.int("alternate") else 0
                require(interfaceId in 0..255 && alt in 0..255)
                val config = target.usb.configurations().singleOrNull { it.id == configId } ?: error("CONFIGURATION_NOT_FOUND")
                val infos = (0 until config.interfaceCount).map { config.getInterface(it).info() }
                if (serial) require(CdcAcmDriver.interfaces(infos).first.id == interfaceId)
                else require(infos.any { it.id == interfaceId && it.alternate == alt }) { "INTERFACE_NOT_FOUND" }
                val grant = Grant(session.id, target.key, interfaceId, serial, configuration = configId, alternate = alt)
                requireGrant(grant)
                check(manager.hasPermission(target.usb)) { "ANDROID_PERMISSION_REQUIRED" }
                check(target.key !in configurationOperations && target.key !in bootstrapReservations && iphoneOperations.values.none { it.device == target.key }) { "DEVICE_BUSY" }
                check(serialConnections.values.none { it.accessScope().conflicts(grant.accessScope()) }) { "DEVICE_BUSY" }
                check(connections.values.none { it.device == target.key && (it.serial || serial || it.interfaceId == interfaceId) }) { "INTERFACE_BUSY" }
                AndroidUsbConnection(manager, target.usb, interfaceId, serial, configId, alt).also {
                    owner.connection = it; connections[it] = grant; rawSockets[it] = socket
                }
            } else owner.connection ?: error("NOT_OPEN")
        }
        fun access() = synchronized(this) {
            refresh()
            val grant = connections[opened] ?: error("USB_CONNECTION_LOST")
            check(grant in grants && sessions[request.getString("token")]?.id == grant.session) { "GRANT_REVOKED" }
            check(manager.hasPermission(device(grant.device).usb)) { "ANDROID_PERMISSION_REQUIRED" }
        }
        try {
            val result = UsbOperationGuard(deadlines).run(::access, {
                runCatching { socket.close() }; opened.close()
            }) { when (op) {
                "open", "raw-open" -> { opened.initialize(); opened.metadata().put("opened", true) }
                "metadata" -> opened.metadata()
                "ping" -> JSONObject().put("alive", true)
                "close" -> { opened.close(); JSONObject().put("closed", true) }
                "poll" -> opened.poll(request.int("endpoint"), request.int("length"), request.int("timeout"))
                "configure" -> {
                    opened.configure(request.int("baud"), request.int("bits"), request.int("stops"), request.int("parity"), request.getBoolean("dtr"), request.getBoolean("rts"))
                    JSONObject().put("configured", true)
                }
                else -> {
                    val timeout = request.int("timeout")
                    val input = when (op) { "read" -> true; "write" -> false; "transfer" -> request.int("endpoint") and 128 != 0; else -> request.int("type") and 128 != 0 }
                    val bytes = if (input) ByteArray(request.int("length").also { UsbPolicy.transfer(it, timeout) }) else SerialRequest.bytes(request)
                    val count = if (op == "control") opened.control(request.int("type"), request.int("request"), request.int("value"), request.int("index"), bytes, timeout)
                        else opened.transfer(if (op in setOf("read", "write")) opened.serialEndpoint(input) else request.int("endpoint"), bytes, timeout)
                    check(count in 0..bytes.size) { "INVALID_USB_COUNT" }
                    JSONObject().put("count", count).also { if (input) it.put("data", bytes.take(count).joinToString("") { b -> "%02x".format(b.toInt() and 255) }) }
                }
            } }
            if (op == "close") {
                opened.finish()
                synchronized(this) { connections.remove(opened); rawSockets.remove(opened); owner.connection = null }
            }
            return JSONObject().put("ok", true).put("result", result)
        } catch (error: Exception) {
            if (op in setOf("open", "raw-open")) {
                opened.finish()
                synchronized(this) { connections.remove(opened); rawSockets.remove(opened); owner.connection = null }
            }
            throw error
        }
    }

    @Synchronized private fun diagnostics() = JSONObject().put("usbHost", supported)
        .put("rawConnections", connections.size).put("serialConnections", serialConnections.size)
        .put("iphoneOperations", iphoneOperations.size).put("pendingApprovals", pending.size)
        .put("fileConnections", fileConnections.size)
        .put("recentErrors", JSONArray(recentErrors.toList()))

    private fun changeConfiguration(request: JSONObject): JSONObject {
        val target: Device
        val config: android.hardware.usb.UsbConfiguration
        val connection: android.hardware.usb.UsbDeviceConnection
        synchronized(this) {
            refresh()
            val session = sessions[request.getString("token")] ?: error("SESSION_EXPIRED")
            require(request.int("version") == 1) { "UNSUPPORTED_VERSION" }
            target = device(request.getString("device"))
            config = target.usb.configurations().singleOrNull { it.id == request.int("configuration") } ?: error("CONFIGURATION_NOT_FOUND")
            requireGrant(Grant(session.id, target.key, -1, false, driver = "configuration", configuration = config.id))
            check(target.key !in configurationOperations && connections.values.none { it.device == target.key } &&
                serialConnections.values.none { it.device == target.key } && iphoneOperations.values.none { it.device == target.key } && target.key !in bootstrapReservations) { "DEVICE_BUSY" }
            check(manager.hasPermission(target.usb)) { "ANDROID_PERMISSION_REQUIRED" }
            connection = manager.openDevice(target.usb) ?: error("USB_OPEN_FAILED")
            configurationOperations[target.key] = AutoCloseable { connection.close() }
        }
        val timer = deadlines.schedule({ connection.close() }, 5, java.util.concurrent.TimeUnit.SECONDS)
        try {
            check(connection.setConfiguration(config)) { "CONFIGURATION_CHANGE_FAILED" }
            return JSONObject().put("ok", true).put("result", JSONObject().put("configured", true).put("configuration", config.id))
        } finally {
            timer.cancel(false); connection.close()
            synchronized(this) {
                configurationOperations.remove(target.key)
                revoke { it.device == target.key } // Even a failed change may have affected device state.
                if (devices[target.usb.deviceName]?.key == target.key) devices.remove(target.usb.deviceName)
                refresh()
            }
        }
    }

    private fun serve(socket: LocalSocket) {
        val owner = RawClient()
        try {
            check(socket.peerCredentials.uid == Process.myUid()) { "UNAUTHORIZED" }
            socket.soTimeout = 30_000
            val input = DataInputStream(socket.inputStream)
            val output = DataOutputStream(socket.outputStream)
            while (!closed) {
                val length = input.readInt()
                require(length in 1..UsbPolicy.MAX_FRAME) { "FRAME_TOO_LARGE" }
                val bytes = ByteArray(length).also(input::readFully)
                val reply = try {
                    val json = String(bytes, Charsets.UTF_8)
                    UsbPolicy.flatObject(json)
                    val request = JSONObject(json)
                    if (request.getString("op") == "files-open") {
                        check(owner.connection == null) { "ALREADY_OPEN" }
                        files(socket, request); return
                    }
                    if (request.getString("op") == "serial-open") {
                        check(owner.connection == null) { "ALREADY_OPEN" }
                        serial(socket, request)
                        return
                    }
                    if (request.getString("op") == "tooling") {
                        check(owner.connection == null) { "ALREADY_OPEN" }
                        tooling(request, socket)
                    } else if (request.getString("op") == "iphone") {
                        check(owner.connection == null) { "ALREADY_OPEN" }
                        iphone(request, socket)
                    } else raw(request, socket, owner)
                } catch (error: Exception) {
                    synchronized(this) {
                        if (recentErrors.size == 16) recentErrors.removeFirst()
                        // Only bounded symbolic categories, never command data or tokens.
                        recentErrors.addLast(error.message?.substringBefore(':')?.takeIf { it.matches(Regex("[A-Z_]{1,80}")) } ?: "USB_REQUEST_FAILED")
                    }
                    JSONObject().put("ok", false).put("error", error.message?.take(300) ?: "USB_ERROR")
                }
                val encoded = reply.toString().toByteArray(Charsets.UTF_8)
                check(encoded.size <= UsbPolicy.MAX_FRAME)
                output.writeInt(encoded.size); output.write(encoded); output.flush()
            }
        } catch (_: Exception) {
            // EOF, malformed framing and idle clients release their interface, never their PTY.
        } finally {
            owner.connection?.let { it.finish(); synchronized(this) { connections.remove(it); rawSockets.remove(it) } }
        }
    }

    @Synchronized override fun close() {
        closed = true
        iphoneTools.close()
        if (active === this) active = null
        runCatching { context.unregisterReceiver(receiver) }
        runCatching { context.unregisterReceiver(storageReceiver) }
        pending.values.forEach { it.permission?.cancel() }
        pending.clear(); grants.clear(); sessions.clear()
        connections.keys.toList().forEach { runCatching { it.close() } }; connections.clear()
        iphoneOperations.keys.toList().forEach { it.close() }; iphoneOperations.clear()
        serialConnections.keys.toList().forEach { it.cancel() }
        configurationOperations.values.toList().forEach { runCatching { it.close() } }
        fileConnections.keys.toList().forEach { store -> store.cancelAsync(providerCancellation) }
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        deadlines.shutdownNow()
        providerCancellation.shutdown()
        clientFile.delete()
    }

    companion object { @Volatile internal var active: UsbBroker? = null }

    private fun tooling(request: JSONObject, socket: LocalSocket): JSONObject {
        val action = request.getString("action")
        var selectedGrant: Grant? = null
        val deviceKey = synchronized(this) {
            refresh()
            val session = sessions[request.getString("token")] ?: error("SESSION_EXPIRED")
            require(request.int("version") == 1) { "UNSUPPORTED_VERSION" }
            TrustedIphoneToolPolicy.validateRequest(action, request.keys().asSequence().toSet())
            if (action in setOf("status", "cancel", "select")) {
                val id = request.getString("operation")
                val result = when (action) {
                    "status" -> iphoneTools.status(session.id, id)
                    "cancel" -> iphoneTools.cancel(session.id, id)
                    else -> iphoneTools.select(session.id, id, request.getString("choices"))
                }
                return JSONObject().put("ok", true).put("result", result)
            }
            if (action == "list") return JSONObject().put("ok", true).put("result", JSONObject()
                .put("tools", JSONArray(listOf("idevice-check", "isideload"))).put("privateTransport", true).put("publicMuxSocket", false))
            val tool = if (action == "start") request.getString("tool") else "idevice-check"
            require(tool in if (action == "start") setOf("isideload") else setOf("idevice-check")) { "UNSUPPORTED_TOOL_OPERATION" }
            check(supported && manager != null) { "USB_HOST_UNAVAILABLE" }
            val target = device(request.getString("device"))
            require(IphonePolicy.candidate(target.usb.vendorId, target.usb.productId)) { "UNSUPPORTED_USB_DEVICE" }
            val grant = Grant(session.id, target.key, -1, false, tool = tool)
            requireGrant(grant); selectedGrant = grant
            check(manager.hasPermission(target.usb)) { "ANDROID_PERMISSION_REQUIRED" }
            target.key
        }
        fun access() = synchronized(this) {
            check(selectedGrant in grants && request.getString("token") in sessions && devices.values.any { it.key == deviceKey }) { "GRANT_REVOKED" }
        }
        val result = if (action == "start") iphoneTools.start(request.getString("tool"), deviceKey,
            synchronized(this) { sessions[request.getString("token")]?.id ?: error("SESSION_EXPIRED") }, ::access)
            else iphoneTools.check(deviceKey, socket, ::access)
        return JSONObject().put("ok", true).put("result", result)
    }

    /** Host-only whole-device lease. Never returned through the guest protocol. */
    @Synchronized internal fun reserveBootstrap(key: String, detached: () -> Unit): Pair<UsbManager, UsbDevice> {
        refresh()
        check(!closed && supported && manager != null) { "USB_HOST_UNAVAILABLE" }
        val usb = devices.values.firstOrNull { it.key == key }?.usb ?: error("USB_DETACHED")
        require(IphonePolicy.candidate(usb.vendorId, usb.productId)) { "UNSUPPORTED_USB_DEVICE" }
        check(key !in bootstrapReservations && key !in configurationOperations &&
            connections.values.none { it.device == key } && serialConnections.values.none { it.device == key } &&
            iphoneOperations.values.none { it.device == key }) { "DEVICE_BUSY" }
        bootstrapReservations[key] = detached
        return manager to usb
    }

    @Synchronized internal fun releaseBootstrap(key: String) { bootstrapReservations.remove(key) }
}

private fun JSONObject.int(key: String): Int {
    val value = get(key)
    require(value is Int) { "$key must be a 32-bit integer" }
    return value
}

/** Explicit PendingIntent destination; broadcast payloads alone never grant access. */
class UsbPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("request")?.let { UsbBroker.active?.finishPermission(it) }
    }
}
