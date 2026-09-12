package dev.terminal.runtime.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

internal fun UsbDevice.interfaces(): List<UsbInterface> = (0 until interfaceCount).map(::getInterface)
internal fun UsbDevice.configurations() = (0 until configurationCount).map(::getConfiguration)
internal fun UsbInterface.endpoints(): List<UsbEndpoint> = (0 until endpointCount).map(::getEndpoint)
internal fun UsbInterface.info() = UsbInterfaceInfo(id, alternateSetting, interfaceClass, interfaceSubclass,
    endpoints().map { UsbEndpointInfo(it.address, it.type, it.maxPacketSize) })

internal fun UsbDevice.description(key: String) = JSONObject().put("id", key)
    .put("vendor", vendorId).put("product", productId)
    .put("class", deviceClass).put("subclass", deviceSubclass).put("protocol", deviceProtocol)
    .put("configurations", JSONArray(configurations().map { configuration ->
        JSONObject().put("value", configuration.id).put("maxPower", configuration.maxPower)
            .put("selfPowered", configuration.isSelfPowered).put("remoteWakeup", configuration.isRemoteWakeup)
            .put("interfaces", JSONArray((0 until configuration.interfaceCount).map { n ->
                val intf = configuration.getInterface(n)
                JSONObject().put("id", intf.id).put("alternate", intf.alternateSetting)
                    .put("class", intf.interfaceClass).put("subclass", intf.interfaceSubclass).put("protocol", intf.interfaceProtocol)
                    .put("endpoints", JSONArray(intf.endpoints().map { ep -> JSONObject()
                        .put("address", ep.address).put("type", ep.type).put("packetSize", ep.maxPacketSize).put("interval", ep.interval) }))
            }))
    }))
    .put("iphone", IphonePolicy.candidate(vendorId, productId))
    .put("serialPorts", SerialDrivers.ports(this))
    .put("interfaces", JSONArray(interfaces().map { intf ->
        JSONObject().put("id", intf.id).put("alternate", intf.alternateSetting)
            .put("class", intf.interfaceClass).put("subclass", intf.interfaceSubclass).put("protocol", intf.interfaceProtocol)
            .put("endpoints", JSONArray(intf.endpoints().map {
                JSONObject().put("address", it.address).put("type", it.type).put("packetSize", it.maxPacketSize)
            }))
    })).put("cdcAcm", runCatching { CdcAcmDriver.interfaces(interfaces().map { it.info() }) }.isSuccess)

/** Owns the raw descriptor entirely on Android. One broker client owns one connection. */
internal class AndroidUsbConnection(
    manager: UsbManager, device: UsbDevice, interfaceId: Int, private val serial: Boolean,
    val configurationId: Int = device.getConfiguration(0).id, private val alternate: Int = 0,
) : AutoCloseable {
    private val connection: UsbDeviceConnection = manager.openDevice(device) ?: error("USB_OPEN_FAILED")
    private val closed = AtomicBoolean()
    private val claimed = mutableListOf<UsbInterface>()
    private val data: UsbInterface
    private val control: UsbInterface?
    private val alternatives: Int
    private var pending: UsbRequest? = null
    private var pendingBuffer: ByteBuffer? = null
    private var pendingEndpoint = -1

    init {
        try {
            val config = device.configurations().singleOrNull { it.id == configurationId } ?: error("CONFIGURATION_NOT_FOUND")
            val interfaces = (0 until config.interfaceCount).map(config::getInterface)
            alternatives = interfaces.count { it.id == interfaceId }
            if (serial) {
                val pair = CdcAcmDriver.interfaces(interfaces.map { it.info() })
                control = interfaces.first { it.id == pair.first.id && it.alternateSetting == 0 }
                data = interfaces.first { it.id == pair.second.id && it.alternateSetting == 0 }
                require(interfaceId == control.id) { "Select the CDC control interface" }
            } else {
                control = null
                data = interfaces.firstOrNull { it.id == interfaceId && it.alternateSetting == alternate }
                    ?: error("INTERFACE_NOT_FOUND")
            }
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    /** Metadata construction/reservation is separate from potentially blocking device I/O. */
    fun initialize() {
        check(!closed.get()) { "USB_CONNECTION_CLOSED" }
        val active = ByteArray(1)
        check(connection.controlTransfer(0x80, 8, 0, 0, active, 1, 1000) == 1) { "CONFIGURATION_QUERY_FAILED" }
        check(active[0].toInt() and 255 == configurationId) { "CONFIGURATION_CHANGE_REQUIRED" }
        control?.let(::claim)
        claim(data)
        if (!serial && (alternatives > 1 || alternate != 0)) check(connection.setInterface(data)) { "ALTERNATE_SETTING_FAILED" }
        check(!closed.get()) { "USB_CONNECTION_CLOSED" }
    }

    /** Keep an asynchronous IN request queued across idle polls; do not lose a packet on timeout. */
    fun poll(endpoint: Int, size: Int, timeout: Int): JSONObject {
        check(!closed.get()) { "USB_CONNECTION_CLOSED" }
        UsbPolicy.transfer(size, timeout)
        require(size > 0)
        val ep = data.endpoints().singleOrNull { it.address == endpoint && it.direction == 128 && it.type in setOf(2, 3) }
            ?: error("ENDPOINT_NOT_GRANTED")
        if (pending == null) {
            val request = UsbRequest()
            try {
                check(request.initialize(connection, ep)) { "USB_REQUEST_FAILED" }
                val buffer = ByteBuffer.allocateDirect(size)
                check(request.queue(buffer)) { "USB_QUEUE_FAILED" }
                pending = request; pendingBuffer = buffer; pendingEndpoint = endpoint
            } catch (error: Exception) { request.close(); throw error }
        }
        require(pendingEndpoint == endpoint && pendingBuffer!!.capacity() == size) { "PENDING_READ_SHAPE_MISMATCH" }
        return try {
            check(connection.requestWait(timeout.toLong()) === pending) { "USB_REQUEST_FAILED" }
            val buffer = pendingBuffer!!
            val bytes = ByteArray(buffer.position())
            buffer.flip(); buffer.get(bytes)
            pending!!.close(); pending = null; pendingBuffer = null
            JSONObject().put("pending", false).put("count", bytes.size).put("data", bytes.joinToString("") { "%02x".format(it.toInt() and 255) })
        } catch (_: TimeoutException) {
            JSONObject().put("pending", true).put("count", 0).put("data", "")
        }
    }

    fun metadata(): JSONObject = JSONObject().put("configuration", configurationId).put("alternate", alternate)

    private fun claim(intf: UsbInterface) {
        // Never detach a kernel driver automatically.
        check(connection.claimInterface(intf, false)) { "INTERFACE_BUSY_OR_UNAVAILABLE" }
        claimed += intf
    }

    fun configure(baud: Int, bits: Int, stops: Int, parity: Int, dtr: Boolean, rts: Boolean) {
        check(!closed.get()) { "USB_CONNECTION_CLOSED" }
        val intf = control ?: error("NOT_SERIAL")
        val coding = UsbPolicy.lineCoding(baud, bits, stops, parity)
        check(connection.controlTransfer(0x21, 0x20, 0, intf.id, coding, coding.size, 1000) == coding.size) {
            "CDC_LINE_CODING_FAILED"
        }
        check(connection.controlTransfer(0x21, 0x22, (if (dtr) 1 else 0) or (if (rts) 2 else 0),
            intf.id, null, 0, 1000) >= 0) { "CDC_CONTROL_LINE_FAILED" }
    }

    fun serialEndpoint(input: Boolean): Int {
        check(serial) { "NOT_SERIAL" }
        return data.endpoints().single { it.type == 2 && (it.direction == 128) == input }.address
    }

    fun transfer(endpoint: Int, bytes: ByteArray, timeout: Int): Int {
        check(!closed.get()) { "USB_CONNECTION_CLOSED" }
        check(pending == null || endpoint and 128 == 0) { "READ_ALREADY_PENDING" }
        UsbPolicy.transfer(bytes.size, timeout)
        val ep = data.endpoints().firstOrNull { it.address == endpoint } ?: error("ENDPOINT_NOT_GRANTED")
        require(ep.type == 2 || ep.type == 3) { "Only bulk and interrupt transfers are supported" }
        check(ep.type != 3 || pending == null) { "READ_ALREADY_PENDING" }
        if (ep.type == 2) {
            val count = connection.bulkTransfer(ep, bytes, bytes.size, timeout)
            check(count >= 0) { "USB_TIMEOUT_OR_IO_ERROR" }
            return count
        }
        val request = UsbRequest()
        try {
            check(request.initialize(connection, ep)) { "USB_REQUEST_FAILED" }
            val buffer = ByteBuffer.allocateDirect(bytes.size)
            if (ep.direction == 0) { buffer.put(bytes); buffer.flip() }
            check(request.queue(buffer)) { "USB_QUEUE_FAILED" }
            try {
                check(connection.requestWait(timeout.toLong()) === request) { "USB_REQUEST_FAILED" }
                val count = buffer.position()
                if (ep.direction == 128) { buffer.flip(); buffer.get(bytes, 0, count) }
                return count
            } catch (error: TimeoutException) {
                request.cancel()
                // Do not reuse a connection with an outstanding cancelled request.
                close()
                error("USB_TIMEOUT")
            }
        } finally { request.close() }
    }

    fun control(type: Int, request: Int, value: Int, index: Int, bytes: ByteArray, timeout: Int): Int {
        check(!closed.get()) { "USB_CONNECTION_CLOSED" }
        check(!serial) { "Raw control is not available through a serial grant" }
        UsbPolicy.transfer(bytes.size, timeout)
        UsbPolicy.control(type, request, value, index, data.id, data.endpoints().map { it.address }.toSet(), data.interfaceClass == 3)
        val count = connection.controlTransfer(type, request, value, index, bytes, bytes.size, timeout)
        check(count >= 0) { "USB_TIMEOUT_OR_IO_ERROR" }
        return count
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Closing the descriptor releases all claims and unblocks in-flight I/O. Do not
        // iterate mutable claim state while initialization is running on another thread.
        connection.close()
    }

    fun finish() { close(); runCatching { pending?.close() }; pending = null; pendingBuffer = null }
}
