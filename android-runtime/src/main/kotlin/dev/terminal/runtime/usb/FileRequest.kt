package dev.terminal.runtime.usb

import org.json.JSONObject

internal object FileRequest {
    fun validate(value: JSONObject) {
        val op = value.getString("op")
        val fields = when (op) {
            "files-open" -> setOf("root", "mode")
            "ls" -> setOf("parent")
            "get" -> setOf("handle")
            "put" -> setOf("parent", "name", "size")
            "file-data" -> setOf("offset", "data")
            "ping", "close" -> emptySet()
            else -> error("INVALID_FILE_OPERATION")
        }
        require(value.keys().asSequence().toSet() == fields + setOf("version", "token", "op")) { "INVALID_FILE_REQUEST" }
        require(value.get("version") == 1 && value.get("token") is String) { "INVALID_FILE_IDENTITY" }
        for (key in fields) require(if (key in setOf("size", "offset")) value.get(key) is Int else value.get(key) is String) { "INVALID_FILE_FIELD" }
        if (op == "files-open") require(value.getString("mode") in setOf("read", "write")) { "INVALID_FILE_MODE" }
        if (op == "put") { IphonePolicy.size(value.get("size")); IphonePolicy.uploadName(value.getString("name")) }
        for (key in fields - setOf("data", "size", "offset")) require(value.getString(key).length <= 256) { "FILE_FIELD_TOO_LONG" }
    }
}
