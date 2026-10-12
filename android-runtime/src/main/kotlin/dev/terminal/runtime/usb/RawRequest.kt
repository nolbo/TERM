package dev.terminal.runtime.usb

import org.json.JSONObject

/** Exact schemas for the legacy raw/CDC route; unknown fields never widen a grant. */
internal object RawRequest {
    fun validate(value: JSONObject) {
        val op = value.getString("op")
        val fields = when (op) {
            "list" -> emptySet()
            "open" -> setOf("device", "interface", "serial")
            "raw-open" -> setOf("device", "interface", "configuration", "alternate")
            "configuration" -> setOf("device", "configuration")
            "metadata", "ping", "close", "diagnostics", "storage-roots" -> emptySet()
            "poll" -> setOf("endpoint", "length", "timeout")
            "configure" -> setOf("baud", "bits", "stops", "parity", "dtr", "rts")
            "read" -> setOf("length", "timeout")
            "write" -> setOf("data", "timeout")
            "transfer", "control" -> {
                val directionKey = if (op == "transfer") "endpoint" else "type"
                require(value.get(directionKey) is Int) { "INVALID_USB_INTEGER" }
                val direction = value.getInt(directionKey)
                require(direction in 0..255) { "INVALID_USB_ADDRESS_OR_TYPE" }
                (if (op == "transfer") setOf("endpoint") else setOf("type", "request", "value", "index")) +
                    setOf("timeout", if (direction and 128 != 0) "length" else "data")
            }
            else -> error("UNKNOWN_OPERATION")
        }
        require(value.keys().asSequence().toSet() == fields + setOf("op", "version", "token")) { "INVALID_USB_REQUEST" }
        require(value.get("token") is String && value.get("version") is Int) { "INVALID_USB_IDENTITY" }
        for (key in fields) when (key) {
            "device", "data" -> require(value.get(key) is String) { "INVALID_USB_STRING" }
            "serial", "dtr", "rts" -> require(value.get(key) is Boolean) { "INVALID_USB_BOOLEAN" }
            else -> require(value.get(key) is Int) { "INVALID_USB_INTEGER" }
        }
        if (op == "open") require(value.getInt("interface") in 0..255) { "INVALID_USB_INTERFACE" }
        if (op == "raw-open") require(value.getInt("interface") in 0..255 && value.getInt("alternate") in 0..255) { "INVALID_USB_INTERFACE" }
        if (op in setOf("raw-open", "configuration")) require(value.getInt("configuration") in 1..255) { "INVALID_USB_CONFIGURATION" }
        if (op == "poll") {
            require(value.getInt("endpoint") in 129..255 && value.getInt("length") > 0) { "INVALID_USB_POLL" }
            UsbPolicy.transfer(value.getInt("length"), value.getInt("timeout"))
        }
    }
}
