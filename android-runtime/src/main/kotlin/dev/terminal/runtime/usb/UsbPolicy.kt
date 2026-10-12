package dev.terminal.runtime.usb

/** Wire limits are shared by the Android transport, broker and tests. */
object UsbPolicy {
    const val MAX_BYTES = 16 * 1024
    const val MAX_FRAME = 48 * 1024
    const val MAX_CLIENTS = 8
    const val MAX_PENDING = 16
    const val MIN_TIMEOUT = 10
    const val MAX_TIMEOUT = 1000

    /** All requests are flat objects. Reject nesting before the platform JSON parser recurses. */
    fun flatObject(json: String) {
        var quoted = false
        var escaped = false
        var depth = 0
        var started = false
        var ended = false
        for (char in json) {
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> { require(started && !ended); quoted = true }
                '{' -> {
                    require(!started) { "Nested or trailing objects are not supported" }
                    started = true; depth++
                }
                '}' -> { depth--; require(depth == 0) { "Invalid object" }; ended = true }
                '[', ']' -> error("Arrays are not supported")
                else -> require(char.isWhitespace() || (started && !ended && char in ":,0123456789.-+eEtruefalsn")) {
                    "Invalid JSON character"
                }
            }
        }
        require(!quoted && depth == 0 && json.trim().startsWith('{') && json.trim().endsWith('}'))
    }

    fun transfer(length: Int, timeout: Int) {
        require(length in 0..MAX_BYTES) { "Transfer length must be 0..$MAX_BYTES" }
        require(timeout in MIN_TIMEOUT..MAX_TIMEOUT) { "Timeout must be $MIN_TIMEOUT..$MAX_TIMEOUT ms" }
    }

    /** Device-wide requests cannot be authorized by a single-interface grant. */
    fun control(type: Int, request: Int, value: Int, index: Int, interfaceId: Int, endpoints: Set<Int>, hid: Boolean = false) {
        require(type in 0..255 && request in 0..255 && value in 0..65535 && index in 0..65535)
        // Only the read-only HID report descriptor standard request is added. No reset,
        // SET_CONFIGURATION, SET_INTERFACE or device/string descriptor access.
        if (hid && type == 0x81 && request == 6 && value == 0x2200 && index == interfaceId) return
        require(type and 0x60 in setOf(0x20, 0x40)) { "Only class/vendor control requests are supported" }
        when (type and 0x1f) {
            1 -> require(index and 255 == interfaceId) { "Control request targets another interface" }
            2 -> require(index and 255 in endpoints) { "Control request targets another endpoint" }
            else -> error("Device-wide control requests require a dedicated driver")
        }
    }

    fun lineCoding(baud: Int, dataBits: Int, stopBits: Int, parity: Int): ByteArray {
        require(baud in 1..4_000_000) { "Baud rate must be 1..4000000" }
        require(dataBits in 5..8 && stopBits in 0..2 && parity in 0..4) { "Invalid CDC ACM line coding" }
        return byteArrayOf(baud.toByte(), (baud shr 8).toByte(), (baud shr 16).toByte(),
            (baud shr 24).toByte(), stopBits.toByte(), parity.toByte(), dataBits.toByte())
    }
}

data class UsbEndpointInfo(val address: Int, val type: Int, val packetSize: Int)
data class UsbInterfaceInfo(val id: Int, val alternate: Int, val deviceClass: Int, val subclass: Int,
    val endpoints: List<UsbEndpointInfo>)

/** The first driver deliberately rejects ambiguous composite/multi-port layouts. */
object CdcAcmDriver {
    fun interfaces(interfaces: List<UsbInterfaceInfo>): Pair<UsbInterfaceInfo, UsbInterfaceInfo> {
        val control = interfaces.filter { it.alternate == 0 && it.deviceClass == 2 && it.subclass == 2 }.singleOrNull()
            ?: error("Expected exactly one CDC ACM control interface; vendor serial drivers are not supported")
        val data = interfaces.filter { it.alternate == 0 && it.deviceClass == 10 }.singleOrNull()
            ?: error("Expected exactly one CDC data interface; multi-port devices are not supported")
        require(data.endpoints.count { it.type == 2 && it.address and 128 != 0 } == 1 &&
            data.endpoints.count { it.type == 2 && it.address and 128 == 0 } == 1) { "CDC bulk endpoints are missing" }
        return control to data
    }
}
