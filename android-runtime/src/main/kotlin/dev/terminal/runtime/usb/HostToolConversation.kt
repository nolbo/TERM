package dev.terminal.runtime.usb

import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Only validated metadata crosses the terminal boundary. Secret replies never enter snapshots. */
internal class HostToolConversation(val id: String, val tool: String, val device: String) {
    private var phase = "CONNECTING"
    private var state = "RUNNING"
    private var error: String? = null
    private var detail: String? = null
    private var progress: Double? = null
    private var information: JSONObject? = null
    private var prompt: JSONObject? = null
    private var reply: Array<String>? = null
    private var completed = false
    private var result: JSONObject? = null
    private val monitor = java.lang.Object()

    fun event(event: JSONObject) = synchronized(monitor) {
        if (state != "RUNNING") return@synchronized
        val next = event.getString("phase")
        require(next in PHASES) { "WORKER_PROTOCOL" }
        if (next == "FAILED") {
            detail = event.optString("detail").takeIf { it in DETAILS }
            fail(event.optString("error")); return@synchronized
        }
        require(next in transitions(phase)) { "WORKER_PROTOCOL" }
        if (next == "COMPLETED") {
            result = JSONObject().also { dest ->
                if (event.has("bundleId")) {
                    val id = event.getString("bundleId")
                    require(id.matches(Regex("[A-Za-z0-9._-]{1,255}"))) { "WORKER_PROTOCOL" }; dest.put("bundleId", id)
                }
                if (event.has("version")) dest.put("version", label(event.getString("version"), 128))
            }
            completed = true; return@synchronized
        }
        phase = next; progress = null
        if (event.has("progress")) {
            val value = event.getDouble("progress")
            require(value.isFinite() && value in 0.0..1.0) { "WORKER_PROTOCOL" }; progress = value
        }
        if (event.has("device")) {
            val source = event.getJSONObject("device")
            information = JSONObject().also { dest -> for (key in listOf("name", "model", "ios")) {
                dest.put(key, label(source.getString(key), 256))
            } }
        }
        val kind = when (next) {
            "WAITING_FOR_LOGIN" -> "LOGIN"
            "WAITING_FOR_2FA" -> "CODE"
            "WAITING_FOR_TEAM_SELECTION" -> "TEAM"
            "WAITING_FOR_CERTIFICATE_REVOCATION" -> "CERTIFICATES"
            else -> null
        }
        if (kind != null) {
            check(prompt == null && reply == null) { "WORKER_PROTOCOL" }
            val requested = JSONObject().put("kind", kind).put("nonce", UUID.randomUUID().toString())
            if (kind in setOf("TEAM", "CERTIFICATES")) {
                val choices = event.getJSONArray("choices")
                if (choices.length() == 0) error(if (kind == "CERTIFICATES") "CERTIFICATE_LIMIT" else "NO_DEVELOPER_TEAM")
                require(choices.length() in 1..100) { "WORKER_PROTOCOL" }
                val seen = mutableSetOf<String>()
                requested.put("choices", JSONArray().also { dest -> repeat(choices.length()) { index ->
                    val choice = choices.getJSONObject(index); val id = choice.getString("id")
                    require(id.matches(Regex("[A-Za-z0-9._:-]{1,128}")) && seen.add(id)) { "WORKER_PROTOCOL" }
                    dest.put(JSONObject().put("id", id).put("label", label(choice.getString("label"), 128)))
                } })
            }
            prompt = requested
        }
    }
    fun snapshot(host: Boolean = false): JSONObject = synchronized(monitor) {
        JSONObject().put("operation", id).put("tool", tool).put("device", device).put("state", state)
            .put("phase", phase).also { result ->
                detail?.let { result.put("detail", it) }
                result.put("serviceRevision", TrustedIphoneToolPolicy.SERVICE_REVISION)
                error?.let { result.put("error", it) }; progress?.let { result.put("progress", it) }
                information?.let { result.put("information", JSONObject(it.toString())) }
                if (state == "COMPLETED") this.result?.let { result.put("result", JSONObject(it.toString())) }
                prompt?.let { input -> result.put("prompt", JSONObject(input.toString()).also {
                    if (!host) it.remove("nonce")
                }) }
            }
    }
    fun pending(): Boolean = synchronized(monitor) { prompt != null }
    fun terminal(): Boolean = synchronized(monitor) { state != "RUNNING" }
    fun failure(): String? = synchronized(monitor) { error }
    fun submit(nonce: String?, values: Array<String>, secret: Boolean) = synchronized(monitor) {
        val input = prompt ?: error("STALE_TOOL_INPUT")
        require(state == "RUNNING" && reply == null) { "STALE_TOOL_INPUT" }
        val kind = input.getString("kind")
        require(secret == (kind in setOf("LOGIN", "CODE"))) { "HOST_INPUT_REQUIRED" }
        if (secret || nonce != null) require(nonce == input.getString("nonce")) { "STALE_TOOL_INPUT" }
        when (kind) {
            "LOGIN" -> {
                require(values.size == 3 && values[0].length in 1..320 && values[1].length in 1..1024) { "INVALID_TOOL_INPUT" }
                require(endpoint(values[2])) { "ANISETTE" }
            }
            "CODE" -> require(values.size == 1 && values[0].matches(Regex("[0-9]{6}"))) { "TWO_FACTOR" }
            else -> {
                val choices = input.getJSONArray("choices")
                val allowed = (0 until choices.length()).map { choices.getJSONObject(it).getString("id") }.toSet()
                require(values.isNotEmpty() && values.size <= 100 && values.toSet().size == values.size && values.all { it in allowed } &&
                    (kind != "TEAM" || values.size == 1)) { "INVALID_TOOL_INPUT" }
            }
        }
        reply = values.copyOf(); prompt = null; monitor.notifyAll()
    }
    /** Runner consumes immediately into a private worker pipe, never a terminal or diagnostic frame. */
    fun awaitReply(timeoutMillis: Long = 300_000): Array<String> = synchronized(monitor) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (reply == null && state == "RUNNING") {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) { fail(if (phase == "WAITING_FOR_2FA") "TWO_FACTOR_TIMEOUT" else "INPUT_TIMEOUT"); break }
            TimeUnit.NANOSECONDS.timedWait(monitor, remaining)
        }
        val value = reply ?: error(error ?: "CANCELED")
        reply = null; value
    }
    fun complete() = synchronized(monitor) {
        check(completed && state == "RUNNING" && prompt == null && reply == null) { "WORKER_PROTOCOL" }
        state = "COMPLETED"; phase = "COMPLETED"
    }
    fun fail(code: String) = synchronized(monitor) {
        if (state != "RUNNING") return@synchronized
        val mapped = when (code) { "SIDESTORE_DOWNLOAD" -> "DOWNLOAD"; "HOUSE_ARREST_AFC" -> "AFC_PLACEMENT"; else -> code }
        error = mapped.takeIf { it in ERRORS } ?: "WORKER_PROTOCOL"
        state = if (error == "CANCELED") "CANCELED" else "FAILED"
        prompt = null; reply?.fill(""); reply = null; monitor.notifyAll()
    }
    companion object {
        const val DEFAULT_ENDPOINT = "https://ani.sidestore.io"
        val PHASES = setOf("PAIRING", "CHECKING_PAIR_RECORD", "WAITING_FOR_TRUST", "SAVING_PAIR_RECORD", "VALIDATING_PAIRING", "READING_DEVICE_INFORMATION", "WAITING_FOR_LOGIN", "APPLE_LOGIN",
            "WAITING_FOR_2FA", "WAITING_FOR_TEAM_SELECTION", "WAITING_FOR_CERTIFICATE_REVOCATION", "REGISTERING_DEVICE",
            "DOWNLOADING_SIDESTORE", "SIGNING", "INSTALLING", "GENERATING_PAIRING", "PLACING_PAIRING", "COMPLETED", "FAILED")
        private fun transitions(phase: String): Set<String> = when (phase) {
            "CONNECTING" -> setOf("PAIRING")
            "PAIRING" -> setOf("CHECKING_PAIR_RECORD", "WAITING_FOR_TRUST", "READING_DEVICE_INFORMATION")
            "CHECKING_PAIR_RECORD" -> setOf("WAITING_FOR_TRUST", "VALIDATING_PAIRING")
            "WAITING_FOR_TRUST" -> setOf("SAVING_PAIR_RECORD", "READING_DEVICE_INFORMATION")
            "SAVING_PAIR_RECORD" -> setOf("VALIDATING_PAIRING")
            "VALIDATING_PAIRING" -> setOf("READING_DEVICE_INFORMATION")
            "READING_DEVICE_INFORMATION" -> setOf("WAITING_FOR_LOGIN")
            "WAITING_FOR_LOGIN" -> setOf("APPLE_LOGIN")
            "APPLE_LOGIN" -> setOf("WAITING_FOR_2FA", "WAITING_FOR_TEAM_SELECTION", "REGISTERING_DEVICE")
            "WAITING_FOR_2FA" -> setOf("APPLE_LOGIN")
            "WAITING_FOR_TEAM_SELECTION" -> setOf("REGISTERING_DEVICE")
            "REGISTERING_DEVICE" -> setOf("DOWNLOADING_SIDESTORE")
            "DOWNLOADING_SIDESTORE" -> setOf("DOWNLOADING_SIDESTORE", "SIGNING")
            "SIGNING" -> setOf("WAITING_FOR_CERTIFICATE_REVOCATION", "INSTALLING")
            "WAITING_FOR_CERTIFICATE_REVOCATION" -> setOf("SIGNING")
            "INSTALLING" -> setOf("INSTALLING", "GENERATING_PAIRING")
            "GENERATING_PAIRING" -> setOf("PLACING_PAIRING")
            "PLACING_PAIRING" -> setOf("COMPLETED")
            else -> emptySet()
        }
        val ERRORS = setOf("USB_HOST_UNAVAILABLE", "USB_PERMISSION_DENIED", "UNSUPPORTED_USB_DEVICE", "DEVICE_BUSY", "DEVICE_LOCKED",
            "PAIR_RECORD_READ_FAILED", "PAIR_RECORD_INVALID", "PAIR_RECORD_SAVE_FAILED", "PAIR_RECORD_REJECTED",
            "PAIRING_TRANSPORT", "PAIRING_RESPONSE", "PAIRING_TIMEOUT", "PAIR_SESSION_FAILED",
            "TRUST_DENIED", "PAIRING_FAILED", "UNSUPPORTED_IOS_PAIRING", "NETWORK", "ANISETTE", "APPLE_CREDENTIALS", "TWO_FACTOR",
            "NO_DEVELOPER_TEAM", "CERTIFICATE_LIMIT", "APP_ID_LIMIT", "PROVISIONING", "IOS_CERTIFICATE_REJECTED", "DOWNLOAD",
            "SIGNING", "INSTALLATION_PROXY", "RP_PAIRING", "WIFI_DEBUGGING", "AFC_PLACEMENT", "CANCELED", "USB_DETACHED",
            "WORKER_AUTHENTICATION", "WORKER_PROTOCOL", "INPUT_TIMEOUT", "TWO_FACTOR_TIMEOUT", "ARTIFACT_CLEANUP")
        val DETAILS = setOf("IO_EOF", "IO_RESET", "IO_BROKEN_PIPE", "IO_TIMEOUT", "IO_OTHER", "UNEXPECTED_RESPONSE",
            "UNKNOWN_DEVICE_ERROR", "INVALID_RECORD", "INVALID_HOST_ID", "DEVICE_ERROR", "STORE_PERMISSION", "STORE_IO", "STORE_RESPONSE")
        fun endpoint(value: String): Boolean = runCatching { URI(value).let {
            value.length <= 2048 && it.scheme == "https" && !it.host.isNullOrEmpty() && it.rawUserInfo == null &&
                it.rawQuery == null && it.rawFragment == null && it.port in setOf(-1, 443)
        } }.getOrDefault(false)
        private fun label(value: String, maximum: Int): String {
            require(value.length in 1..maximum && value.none { it.isISOControl() }) { "WORKER_PROTOCOL" }; return value
        }
    }
}
