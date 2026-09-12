package dev.terminal.runtime.usb

import com.hoho.android.usbserial.driver.SerialTimeoutException
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class SerialPortIoTest {
    @Test fun unsupportedControlLinesFailBeforeAnyMutation() {
        for ((dtr, rts) in listOf(true to false, false to true)) {
            val io = SerialPortIo(port { method, _ ->
                check(method == "getSupportedControlLines") { "Unexpected mutation: $method" }
                java.util.EnumSet.noneOf(UsbSerialPort.ControlLine::class.java)
            }) {}
            val error = runCatching { io.configure(115200, 8, 0, 0, dtr, rts) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
        }
    }
    private fun port(call: (String, Array<out Any?>) -> Any?): UsbSerialPort = Proxy.newProxyInstance(
        UsbSerialPort::class.java.classLoader, arrayOf(UsbSerialPort::class.java)
    ) { _, method, args -> call(method.name, args ?: emptyArray()) } as UsbSerialPort

    @Test fun shortReadsPreserveRemainderAndDoNotIssueExtraUsbReads() {
        var reads = 0
        val io = SerialPortIo(port { method, args ->
            check(method == "read"); reads++
            val buffer = args[0] as ByteArray
            assertEquals(16384, buffer.size)
            byteArrayOf(0, -1, -128).copyInto(buffer)
            3
        }) {}
        val one = ByteArray(1)
        assertEquals(1, io.read(one, 1000)); assertArrayEquals(byteArrayOf(0), one)
        val rest = ByteArray(10)
        assertEquals(2, io.read(rest, 1000)); assertArrayEquals(byteArrayOf(-1, -128), rest.copyOf(2))
        assertEquals(1, reads)
    }

    @Test fun cancelledReadDoesNotExposeBufferedBytes() {
        var cancelled = false
        val io = SerialPortIo(port { _, args -> (args[0] as ByteArray)[0] = 42; cancelled = true; 1 }) {
            check(!cancelled)
        }
        val output = ByteArray(1)
        assertTrue(runCatching { io.read(output, 500) }.isFailure)
        assertEquals(0, output[0].toInt())
    }

    @Test fun partialTimeoutReportsUncertainDeliveryWithoutReplay() {
        var writes = 0
        val io = SerialPortIo(port { method, _ ->
            check(method == "write"); writes++
            throw SerialTimeoutException("test", 2)
        }) {}
        val result = io.write(byteArrayOf(1, 2, 3), 500)
        assertEquals(2, result.getInt("count"))
        assertTrue(result.getBoolean("timedOut"))
        assertTrue(result.getBoolean("deliveryUncertain"))
        assertEquals(1, writes)
    }

    @Test fun emptyIoDoesNotIssueUsbTransactions() {
        val io = SerialPortIo(port { _, _ -> error("Unexpected USB I/O") }) {}
        assertEquals(0, io.read(ByteArray(0), 500))
        assertEquals(0, io.write(ByteArray(0), 500).getInt("count"))
    }
}
