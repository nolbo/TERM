package dev.terminal.runtime.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import dev.terminal.runtime.native.ApkNativeExecutableLoader
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** One bounded process owns an operation. No Activity, guest FD, or raw mux socket. */
internal class AndroidIphoneOperation(
    context: Context, manager: UsbManager, device: UsbDevice, private val operation: String,
    private val path: String = "", private val uploadSize: Int = 0,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val token = UUID.randomUUID().toString()
    private val address = "terminal-iphone-${UUID.randomUUID()}"
    private var server: LocalServerSocket? = null
    @Volatile private var peer: LocalSocket? = null
    private var activeGuest: LocalSocket? = null
    private var usb: android.hardware.usb.UsbDeviceConnection? = null
    private var process: java.lang.Process? = null
    private val timer = Executors.newSingleThreadScheduledExecutor()

    init {
        try {
            IphonePolicy.operation(operation)
            val binary = ApkNativeExecutableLoader(context).resolvePackagedExecutable("iphone-worker").getOrThrow()
            val directory = File(context.noBackupFilesDir, "iphone-pairing")
            check(directory.mkdirs() || directory.isDirectory) { "PAIR_STORE_UNAVAILABLE" }
            Os.chmod(directory.absolutePath, 448) // 0700; excluded from backup and Rootless binds.
            val prefs = context.getSharedPreferences("iphone-host", Context.MODE_PRIVATE)
            val buid = synchronized(AndroidIphoneOperation::class.java) {
                prefs.getString("buid", null) ?: UUID.randomUUID().toString().also {
                    check(prefs.edit().putString("buid", it).commit()) { "PAIR_STORE_UNAVAILABLE" }
                }
            }
            server = LocalServerSocket(address)
            usb = manager.openDevice(device) ?: error("USB_OPEN_FAILED")
            val arguments = mutableListOf(binary.absolutePath, operation, directory.absolutePath, address, buid)
            if (operation.startsWith("afc-")) arguments.addAll(listOf(path, uploadSize.toString()))
            process = ProcessBuilder(arguments)
                .apply { environment().clear(); redirectError(File("/dev/null")) }.start()
            process!!.outputStream.apply { write((token + "\n").toByteArray()); flush() }
            if (operation != "afc-put") process!!.outputStream.close()
            timer.schedule({ close() }, if (operation.startsWith("afc-")) IphonePolicy.AFC_DEADLINE_MS else IphonePolicy.DEADLINE_MS,
                TimeUnit.MILLISECONDS)
        } catch (failure: Exception) { close(); throw failure }
    }

    fun execute(guestSocket: LocalSocket, event: (JSONObject) -> Unit = {}, upload: (Int) -> ByteArray = { error("NOT_UPLOADING") }): JSONObject {
        synchronized(this) {
            check(!closed.get()) { "IPHONE_OPERATION_CANCELLED" }
            activeGuest = guestSocket
        }
        try {
            return exchange(guestSocket, event, upload)
        } finally {
            synchronized(this) { activeGuest = null }
        }
    }

    private fun exchange(guestSocket: LocalSocket, event: (JSONObject) -> Unit, upload: (Int) -> ByteArray): JSONObject {
        timer.scheduleWithFixedDelay({
            runCatching {
                val poll = StructPollfd().apply { fd = guestSocket.fileDescriptor; events = 0 }
                Os.poll(arrayOf(poll), 0)
                if (poll.revents.toInt() and (OsConstants.POLLHUP or OsConstants.POLLERR or OsConstants.POLLNVAL) != 0) close()
            }.onFailure { close() }
        }, 100, 100, TimeUnit.MILLISECONDS)
        val listener = checkNotNull(server)
        val deadline = SystemClock.elapsedRealtime() + 3000
        var authenticated = false
        while (!closed.get() && SystemClock.elapsedRealtime() < deadline) {
            val poll = StructPollfd().apply { fd = listener.fileDescriptor; events = OsConstants.POLLIN.toShort() }
            if (Os.poll(arrayOf(poll), 100) <= 0) continue
            val socket = listener.accept()
            synchronized(this) {
                if (closed.get()) { socket.close(); error("IPHONE_OPERATION_CANCELLED") }
                peer = socket
            }
            socket.soTimeout = 500
            val valid = runCatching {
                check(socket.peerCredentials.uid == Process.myUid())
                val expected = (token + "\n").toByteArray()
                val actual = ByteArray(expected.size)
                java.io.DataInputStream(socket.inputStream).readFully(actual)
                java.security.MessageDigest.isEqual(expected, actual)
            }.getOrDefault(false)
            if (!valid) { socket.close(); peer = null; continue }
            synchronized(this) {
                check(!closed.get()) { "IPHONE_OPERATION_CANCELLED" }
                val connection = usb ?: error("IPHONE_OPERATION_CANCELLED")
                ParcelFileDescriptor.fromFd(connection.fileDescriptor).use { descriptor ->
                    socket.setFileDescriptorsForSend(arrayOf(descriptor.fileDescriptor))
                    socket.outputStream.write('U'.code)
                    socket.outputStream.flush()
                    socket.setFileDescriptorsForSend(null)
                }
            }
            authenticated = true
            break
        }
        check(authenticated) { "IPHONE_WORKER_HANDSHAKE_FAILED" }
        listener.close()
        server = null
        val child = process ?: error("IPHONE_OPERATION_CANCELLED")
        val result = IphoneWorkerProtocol.exchange(child.inputStream, child.outputStream, operation, path, uploadSize, event, upload)
        check(!closed.get()) { "IPHONE_OPERATION_CANCELLED_OR_TIMED_OUT" }
        check(child.waitFor(1, TimeUnit.SECONDS)) { "IPHONE_WORKER_DID_NOT_EXIT" }
        check(child.exitValue() == 0) { "IPHONE_OPERATION_FAILED" }
        return result
    }

    fun awaitExit(): Boolean = process?.let { runCatching { it.waitFor(1, TimeUnit.SECONDS) }.getOrDefault(false) } ?: true
    fun isStopped(): Boolean = closed.get() && process?.isAlive != true

    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Cancellation must also unblock a guest that stopped reading/writing frames.
        runCatching { activeGuest?.close() }
        runCatching { peer?.close() }
        runCatching { server?.close() }
        runCatching { process?.destroyForcibly() }
        runCatching { usb?.close() }
        timer.shutdownNow()
    }
}
