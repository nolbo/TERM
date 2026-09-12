package dev.terminal.runtime.usb

import org.junit.Assert.*
import org.junit.Test

class UsbPolicyTest {
    @Test fun onlyHidReportDescriptorIsAnAllowedStandardRequest() {
        UsbPolicy.control(0x81, 6, 0x2200, 3, 3, emptySet(), hid = true)
        for ((type, request, value, index) in listOf(listOf(0x81, 6, 0x2200, 4), listOf(0x81, 6, 0x0100, 3),
            listOf(0x01, 11, 0, 3), listOf(0x80, 6, 0x2200, 3), listOf(0x81, 6, 0x2201, 3)))
            assertTrue(runCatching { UsbPolicy.control(type, request, value, index, 3, emptySet(), hid = true) }.isFailure)
        assertTrue(runCatching { UsbPolicy.control(0x81, 6, 0x2200, 3, 3, emptySet()) }.isFailure)
    }
    @Test fun transfersCannotBeUnbounded() {
        for ((size, timeout) in listOf(-1 to 100, 16385 to 100, 1 to 0, 1 to 1001)) {
            assertTrue(runCatching { UsbPolicy.transfer(size, timeout) }.isFailure)
        }
        UsbPolicy.transfer(16384, 1000)
    }

    @Test fun rawControlCannotEscapeInterfaceGrant() {
        UsbPolicy.control(0x21, 0x20, 0, 3, 3, setOf(0x81))
        UsbPolicy.control(0xc2, 1, 0, 0x81, 3, setOf(0x81))
        for ((type, index) in listOf(0x40 to 0, 0x21 to 4, 0xc2 to 0x82, 0x01 to 3, 0x61 to 3)) {
            assertTrue(runCatching { UsbPolicy.control(type, 1, 0, index, 3, setOf(0x81)) }.isFailure)
        }
    }

    @Test fun lineCodingUsesLittleEndianAndValidatesSettings() {
        assertArrayEquals(byteArrayOf(0, 0xc2.toByte(), 1, 0, 0, 0, 8), UsbPolicy.lineCoding(115200, 8, 0, 0))
        assertTrue(runCatching { UsbPolicy.lineCoding(0, 8, 0, 0) }.isFailure)
        assertTrue(runCatching { UsbPolicy.lineCoding(9600, 9, 0, 0) }.isFailure)
    }

    @Test fun driverRejectsVendorSerialAndAmbiguousCompositeDevices() {
        val control = UsbInterfaceInfo(3, 0, 2, 2, emptyList())
        val data = UsbInterfaceInfo(4, 0, 10, 0, listOf(UsbEndpointInfo(0x81, 2, 64), UsbEndpointInfo(2, 2, 64)))
        assertEquals(control to data, CdcAcmDriver.interfaces(listOf(control, data)))
        assertTrue(runCatching { CdcAcmDriver.interfaces(listOf(control.copy(deviceClass = 255), data)) }.isFailure)
        assertTrue(runCatching { CdcAcmDriver.interfaces(listOf(control, data, data.copy(id = 5))) }.isFailure)
        assertTrue(runCatching { CdcAcmDriver.interfaces(listOf(control, data.copy(endpoints = emptyList()))) }.isFailure)
    }

    @Test fun rejectRecursiveFramesBeforeParsingButAllowEscapedStrings() {
        UsbPolicy.flatObject("{\"op\":\"list\",\"token\":\"braces {} [] and \\\"quoted\\\"\"}")
        listOf("[]", "{\"a\":{}}", "{\"a\":[]}", "{", "}", "{\"a\":\"unterminated}",
            "{}{}", "{'a':'\"', 'b':{}, 'c':'\"'}", "{/*\"*/\"a\":{}/*\"*/}").forEach {
            assertTrue(it, runCatching { UsbPolicy.flatObject(it) }.isFailure)
        }
    }
}
