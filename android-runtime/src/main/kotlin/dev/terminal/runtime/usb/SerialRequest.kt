package dev.terminal.runtime.usb

import org.json.JSONObject

/** Strict streaming schema, validated before permission or device I/O. */
internal object SerialRequest {
    fun validate(request: JSONObject): String {
        val op = request.getString("op")
        val fields = when (op) {
            "serial-open" -> setOf("device", "driver", "port")
            "configure" -> setOf("baud", "bits", "stops", "parity", "dtr", "rts")
            "read" -> setOf("length", "timeout")
            "write" -> setOf("data", "timeout")
            "status", "ping", "close" -> emptySet()
            else -> error("UNSUPPORTED_SERIAL_OPERATION")
        }
        require(request.keys().asSequence().toSet() == fields + setOf("op", "version", "token")) { "INVALID_SERIAL_REQUEST" }
        require(request.get("version") == 1) { "UNSUPPORTED_VERSION" }
        require(request.get("token") is String) { "INVALID_TOKEN" }
        for (key in fields.intersect(setOf("port", "baud", "bits", "stops", "parity", "length", "timeout")))
            require(request.get(key) is Int) { "INVALID_SERIAL_INTEGER" }
        for (key in fields.intersect(setOf("dtr", "rts"))) require(request.get(key) is Boolean) { "INVALID_SERIAL_BOOLEAN" }
        when (op) {
            "serial-open" -> require(request.get("device") is String && request.get("driver") is String && request.getInt("port") in 0..255)
            "configure" -> UsbPolicy.lineCoding(request.getInt("baud"), request.getInt("bits"), request.getInt("stops"), request.getInt("parity"))
            "read" -> UsbPolicy.transfer(request.getInt("length"), request.getInt("timeout"))
            "write" -> UsbPolicy.transfer(bytes(request).size, request.getInt("timeout"))
        }
        return op
    }
    fun bytes(request: JSONObject): ByteArray {
        require(request.get("data") is String) { "INVALID_HEX" }
        val hex = request.getString("data")
        require(hex.length <= UsbPolicy.MAX_BYTES * 2 && hex.length % 2 == 0 && hex.all { it in "0123456789abcdefABCDEF" }) { "INVALID_HEX" }
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
