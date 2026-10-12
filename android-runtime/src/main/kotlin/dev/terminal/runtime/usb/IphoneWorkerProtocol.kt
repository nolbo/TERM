package dev.terminal.runtime.usb

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.json.JSONObject

/** Bounded NDJSON between host and its trusted worker; never an arbitrary mux tunnel. */
internal object IphoneWorkerProtocol {
    fun exchange(input: InputStream, output: OutputStream, action: String, path: String, uploadSize: Int,
        event: (JSONObject) -> Unit, upload: (Int) -> ByteArray): JSONObject {
        var started = false
        var size = -1
        var count = 0
        val stream = input.buffered()
        while (true) {
            val line = ByteArrayOutputStream()
            while (true) {
                val b = stream.read()
                check(b >= 0) { "IPHONE_WORKER_INCOMPLETE" }
                if (b == 10) break
                check(line.size() < UsbPolicy.MAX_FRAME - 512) { "IPHONE_REPLY_TOO_LARGE" }
                line.write(b)
            }
            val reply = JSONObject(line.toString("UTF-8"))
            if (reply.has("event")) {
                when (reply.getString("event")) {
                    "start" -> {
                        check(!started && action == "afc-get" && reply.keys().asSequence().toSet() == setOf("event", "size"))
                        size = IphonePolicy.size(reply.get("size")); started = true
                        event(reply)
                    }
                    "data" -> {
                        check(started && action == "afc-get" && reply.keys().asSequence().toSet() == setOf("event", "offset", "data"))
                        check(reply.get("offset") == count)
                        val bytes = IphonePolicy.hex(reply.getString("data"))
                        check(count + bytes.size <= size) { "IPHONE_FILE_SIZE_MISMATCH" }
                        count += bytes.size
                        event(reply)
                    }
                    "ready" -> {
                        check(!started && action == "afc-put" && reply.keys().asSequence().toSet() == setOf("event"))
                        started = true; size = uploadSize
                        event(JSONObject().put("event", "ready").put("path", path).put("size", size))
                        while (count < size) {
                            val data = upload(count)
                            check(data.isNotEmpty() && data.size <= IphonePolicy.CHUNK && count + data.size <= size)
                            output.write(data); output.flush()
                            count += data.size
                            event(JSONObject().put("event", "accepted").put("offset", count))
                        }
                        output.close()
                    }
                    else -> error("INVALID_IPHONE_EVENT")
                }
                continue
            }
            check(reply.get("ok") is Boolean) { "INVALID_IPHONE_REPLY" }
            if (!reply.getBoolean("ok")) {
                val error = reply.getString("error")
                check(error.matches(Regex("[A-Z_]{1,80}"))) { "INVALID_IPHONE_REPLY" }
                error("$error (${reply.optInt("code")})")
            }
            val result = reply.getJSONObject("result")
            when (action) {
                "afc-list" -> {
                    check(result.keys().asSequence().toSet() == setOf("entries", "truncated"))
                    val entries = result.getJSONArray("entries")
                    check(entries.length() <= 256 && result.get("truncated") is Boolean)
                    for (i in 0 until entries.length()) {
                        val name = entries.get(i)
                        check(name is String && name.toByteArray().size <= 255 && name.isNotEmpty() &&
                            name !in setOf(".", "..") && '/' !in name && name.none { it.code < 32 || it.code == 127 })
                    }
                }
                "afc-get", "afc-put" -> check(started && count == size && result.keys().asSequence().toSet() == setOf("bytes", "completed") &&
                    result.get("bytes") == size && result.get("completed") == true) { "IPHONE_FILE_SIZE_MISMATCH" }
                "info" -> {
                    check(result.keys().asSequence().toSet() == IphonePolicy.infoKeys)
                    IphonePolicy.infoKeys.forEach { check(result.get(it) is String && result.getString(it).length <= 256) }
                }
                else -> check(result.keys().asSequence().toSet() == setOf("operation", "completed") &&
                    result.getString("operation") == action && result.get("completed") == true)
            }
            check(stream.read() == -1) { "IPHONE_TRAILING_REPLY" }
            return result
        }
    }
}
