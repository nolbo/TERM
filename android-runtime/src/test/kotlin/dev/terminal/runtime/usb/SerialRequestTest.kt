package dev.terminal.runtime.usb

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SerialRequestTest {
    private fun request(op: String) = JSONObject().put("op", op).put("version", 1).put("token", "test")

    @Test fun serialOpenRequiresExplicitDriverAndPort() {
        val value = request("serial-open").put("device", "opaque").put("driver", "ftdi").put("port", 1)
        assertEquals("serial-open", SerialRequest.validate(value))
        value.put("interface", 0)
        assertTrue(runCatching { SerialRequest.validate(value) }.isFailure)
        value.remove("interface"); value.put("port", "1")
        assertTrue(runCatching { SerialRequest.validate(value) }.isFailure)
    }

    @Test fun streamRejectsOtherCapabilitiesAndUnboundedIo() {
        for (op in listOf("control", "transfer", "iphone", "reset", "setConfiguration", "detach"))
            assertTrue(runCatching { SerialRequest.validate(request(op)) }.isFailure)
        val read = request("read").put("length", 16384).put("timeout", 1000)
        assertEquals("read", SerialRequest.validate(read))
        for (length in listOf(-1, 16385)) {
            read.put("length", length)
            assertTrue(runCatching { SerialRequest.validate(read) }.isFailure)
        }
        read.put("length", 1).put("timeout", 0)
        assertTrue(runCatching { SerialRequest.validate(read) }.isFailure)
    }

    @Test fun binaryAndEmptyWritesRoundTripWithoutCoercion() {
        val write = request("write").put("timeout", 1000).put("data", "00Ff80")
        SerialRequest.validate(write)
        assertArrayEquals(byteArrayOf(0, -1, -128), SerialRequest.bytes(write))
        write.put("data", ""); SerialRequest.validate(write)
        for (value in listOf("0", "gg", 123)) {
            write.put("data", value)
            assertTrue(runCatching { SerialRequest.validate(write) }.isFailure)
        }
    }

    @Test fun statusAndHeartbeatDoNotAcceptExtraArguments() {
        for (op in listOf("status", "ping", "close")) {
            val value = request(op)
            assertEquals(op, SerialRequest.validate(value))
            value.put("port", 1)
            assertTrue(runCatching { SerialRequest.validate(value) }.isFailure)
        }
    }

    @Test fun configurationRejectsCoercedBooleans() {
        val value = request("configure").put("baud", 115200).put("bits", 8).put("stops", 0)
            .put("parity", 0).put("dtr", true).put("rts", false)
        SerialRequest.validate(value)
        value.put("dtr", "true")
        assertTrue(runCatching { SerialRequest.validate(value) }.isFailure)
    }
}
