package dev.terminal.runtime.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.LocalSocket
import com.hoho.android.usbserial.driver.*
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** Only pinned, automatically recognized drivers; guests cannot force a VID/PID mapping. */
internal object SerialDrivers {
    fun probe(device: UsbDevice): UsbSerialDriver? = UsbSerialProber.getDefaultProber().probeDevice(device)
    fun name(driver: UsbSerialDriver): String = when (driver) {
        is CdcAcmSerialDriver -> "cdc-acm"
        is FtdiSerialDriver -> "ftdi"
        is Cp21xxSerialDriver -> "cp21xx"
        is Ch34xSerialDriver -> "ch34x"
        is ProlificSerialDriver -> "pl2303"
        is GsmModemSerialDriver -> "gsm-modem"
        is ChromeCcdSerialDriver -> "chrome-ccd"
        else -> error("UNSUPPORTED_SERIAL_DRIVER")
    }
    fun ports(device: UsbDevice): JSONArray {
        val driver = probe(device) ?: return JSONArray()
        return JSONArray(driver.ports.indices.map { JSONObject().put("driver", name(driver)).put("port", it) })
    }
}

/** Serving thread owns driver calls. Cancellation only closes the transport, never races port.close. */
internal class AndroidSerialConnection(manager: UsbManager, device: UsbDevice, driver: UsbSerialDriver,
    portIndex: Int, private val socket: LocalSocket) {
    private val connection = manager.openDevice(device) ?: error("USB_OPEN_FAILED")
    private val port = driver.ports[portIndex]
    private val cancelled = AtomicBoolean()
    private val io = SerialPortIo(port, ::checkActive)

    fun open() { checkActive(); port.open(connection); checkActive() }
    fun checkActive() { check(!cancelled.get()) { "SERIAL_CONNECTION_CANCELLED" } }
    fun configure(baud: Int, bits: Int, stops: Int, parity: Int, dtr: Boolean, rts: Boolean) = io.configure(baud, bits, stops, parity, dtr, rts)
    fun read(bytes: ByteArray, timeout: Int) = io.read(bytes, timeout)
    fun write(bytes: ByteArray, timeout: Int) = io.write(bytes, timeout)
    fun status() = io.status()
    fun cancel() {
        if (cancelled.compareAndSet(false, true)) {
            runCatching { connection.close() }
            runCatching { socket.close() }
        }
    }
    /** Called only on the serving thread, after its last operation. */
    fun finish() {
        runCatching { connection.close() } // prevent driver cleanup issuing further device commands
        runCatching { port.close() }
    }
}

/** Testable byte-stream adapter; owns no Android lifecycle and never reconnects. */
internal class SerialPortIo(private val port: UsbSerialPort, private val checkActive: () -> Unit) {
    private var remainder = ByteArray(0)
    fun configure(baud: Int, bits: Int, stops: Int, parity: Int, dtr: Boolean, rts: Boolean) {
        UsbPolicy.lineCoding(baud, bits, stops, parity)
        checkActive()
        port.setParameters(baud, bits, when (stops) { 0 -> UsbSerialPort.STOPBITS_1; 1 -> UsbSerialPort.STOPBITS_1_5; else -> UsbSerialPort.STOPBITS_2 }, parity)
        val supported = port.supportedControlLines
        if (UsbSerialPort.ControlLine.DTR in supported) port.setDTR(dtr)
        else require(!dtr) { "DTR_UNSUPPORTED_USE_NO_DTR" }
        if (UsbSerialPort.ControlLine.RTS in supported) port.setRTS(rts)
        else require(!rts) { "RTS_UNSUPPORTED_USE_NO_RTS" }
        checkActive()
    }
    fun read(bytes: ByteArray, timeout: Int): Int {
        UsbPolicy.transfer(bytes.size, timeout); checkActive()
        if (bytes.isEmpty()) return 0
        if (remainder.isEmpty()) {
            // FTDI packets include status bytes, even when the caller wants one byte.
            // Preserve surplus payload locally instead of discarding a short caller's data.
            val packet = ByteArray(UsbPolicy.MAX_BYTES)
            val count = port.read(packet, timeout)
            check(count in 0..packet.size); checkActive()
            remainder = packet.copyOf(count)
        }
        val count = minOf(bytes.size, remainder.size)
        remainder.copyInto(bytes, endIndex = count)
        remainder = remainder.copyOfRange(count, remainder.size)
        return count
    }
    fun write(bytes: ByteArray, timeout: Int): JSONObject {
        UsbPolicy.transfer(bytes.size, timeout); checkActive()
        return try {
            if (bytes.isNotEmpty()) port.write(bytes, timeout)
            checkActive()
            JSONObject().put("count", bytes.size).put("timedOut", false)
        } catch (error: SerialTimeoutException) {
            // Never replay uncertain output automatically. Upstream count is a lower bound
            // when a USB transaction failed after earlier chunks completed.
            JSONObject().put("count", error.bytesTransferred.coerceIn(0, bytes.size))
                .put("timedOut", true).put("deliveryUncertain", true)
        }
    }
    fun status(): JSONObject {
        checkActive()
        return JSONObject().put("lines", JSONArray(port.controlLines.map { it.name }))
            .put("supported", JSONArray(port.supportedControlLines.map { it.name }))
    }
}
