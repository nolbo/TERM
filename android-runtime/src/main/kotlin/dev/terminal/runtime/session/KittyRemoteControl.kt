package dev.terminal.runtime.session

import dev.terminal.protocol.SessionDescriptor
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Base64

/** PTY-authenticated remote control. The host grants authority, never the JSON caller. */
internal class KittyRemoteControl(private val host: Host) {
    data class Window(val id: Int, val runtime: SessionRuntime, val session: SessionDescriptor)
    interface Host {
        fun allowed(source: String): Boolean
        suspend fun windows(): List<Window>
        fun focused(): String?
        suspend fun launch(source: Window, args: List<String>, cwd: String, env: Map<String, String>, title: String): Window
        suspend fun focus(window: Window)
        suspend fun title(window: Window, title: String)
        suspend fun close(window: Window)
        suspend fun resize(window: Window, rows: Int, cols: Int)
        suspend fun write(window: Window, bytes: ByteArray)
        fun keys(window: Window, keys: List<String>): ByteArray
    }

    suspend fun execute(sourceId: String, json: String): ByteArray? {
        var noResponse = false
        val response = try {
            require(json.toByteArray().size <= 65536) { "Request too large" }
            validateNesting(json)
            val tokener = JSONTokener(json)
            val request = tokener.nextValue()
            require(request is JSONObject && tokener.nextClean() == '\u0000') { "Invalid request envelope" }
            noResponse = request.bool("no_response")
            require(host.allowed(sourceId)) { "Remote control is disabled for this session" }
            require(!request.has("encrypted") && !request.has("async") && !request.has("stream") && !request.has("password")) { "Unsupported remote transport" }
            val version = request.getJSONArray("version")
            require(version.length() == 3 && (0..2).all { version.get(it) is Number && version.getDouble(it) == version.getInt(it).toDouble() && version.getInt(it) >= 0 }) { "Invalid protocol version" }
            require(version.getInt(0) == 0) { "Unsupported protocol version" }
            val windows = backend { host.windows() }
            val source = windows.singleOrNull { it.session.id == sourceId } ?: error("Source session is no longer available")
            if (request.has("kitty_window_id")) require(request.get("kitty_window_id") is Number && request.getDouble("kitty_window_id") == source.id.toDouble()) { "Source identity mismatch" }
            val scope = windows.filter { it.runtime == source.runtime }
            val payload = if (request.has("payload")) request.getJSONObject("payload") else JSONObject()
            val cmd = request.getString("cmd")
            val data: Any? = when (cmd) {
                "ls" -> {
                    require(payload.string("output_format", "json") == "json") { "Only JSON output is supported" }
                    val selected = if (payload.bool("self")) listOf(source) else targets(payload, source, scope, defaultAll = true)
                    JSONArray().put(JSONObject().put("id", 1).put("is_focused", true).put("tabs", JSONArray().apply {
                        selected.forEach { window ->
                            val active = window.session.id == host.focused()
                            val item = JSONObject().put("id", window.id).put("title", window.session.title)
                                .put("is_focused", active).put("cwd", window.session.cwd).put("pid", 0)
                                .put("lines", window.session.rows.toInt()).put("columns", window.session.cols.toInt())
                                .put("cmdline", JSONArray().put(window.session.command)).put("env", JSONObject())
                                .put("foreground_processes", JSONArray())
                            put(JSONObject().put("id", window.id).put("title", window.session.title)
                                .put("is_focused", active).put("layout", "stack").put("windows", JSONArray().put(item)))
                        }
                    })).toString()
                }
                "launch", "new-window" -> {
                    require(scope.size < 64) { "Remote window limit reached" }
                    require(payload.string("type", "window") in setOf("window", "tab")) { "Only terminal windows and tabs are supported" }
                    require(!payload.bool("allow_remote_control") && !payload.bool("copy_cmdline")) { "Delegating remote authority or copying process state is unsupported" }
                    require(payload.string("stdin_source", "none") == "none" && payload.string("location", "default") == "default") { "Unsupported launch placement or stdin source" }
                    require(payload.string("window_type", "kitty") == "kitty") { "OS windows are unsupported" }
                    for (flag in listOf("copy_colors", "stdin_add_formatting", "stdin_add_line_wrap_markers", "hold_after_ssh"))
                        require(!payload.bool(flag)) { "Unsupported launch option: $flag" }
                    require(!payload.bool("wait_for_child_to_exit")) { "Waiting for child exit is unsupported" }
                    for (name in listOf("copy_env", "source_window", "remote_control_password", "next_to", "logo", "watcher", "var", "os_panel", "spacing", "color", "marker", "os_window_title", "os_window_name", "os_window_class", "os_window_position")) {
                        val value = payload.opt(name)
                        require(value == null || value == JSONObject.NULL || value == "" || value is JSONArray && value.length() == 0) { "Unsupported launch option: $name" }
                    }
                    val args = payload.strings("args", 64)
                    if (args.isNotEmpty()) SessionCommand.encode(args)
                    val env = payload.strings("env", 64).associate { assignment ->
                        val key = assignment.substringBefore('=')
                        require('=' in assignment && key.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) &&
                            !key.startsWith("DROID_") && !key.startsWith("KITTY_") && !key.startsWith("PROOT_") &&
                            key !in setOf("LD_PRELOAD", "LD_LIBRARY_PATH")) { "Invalid or reserved environment variable" }
                        key to assignment.substringAfter('=')
                    }
                    require(env.values.sumOf { it.toByteArray().size } <= 16384) { "Environment too large" }
                    val cwd = payload.string("cwd", source.session.cwd)
                    require(cwd.startsWith('/') && cwd.length <= 4096 && '\u0000' !in cwd) { "An absolute working directory is required" }
                    val title = checkedTitle(payload.string("window_title", payload.string("tab_title", payload.string("title", "Terminal"))))
                    val tabTitle = payload.string("tab_title", "").takeIf { it.isNotBlank() }?.let(::checkedTitle)
                    checkAllowed(sourceId)
                    val child = backend { host.launch(source, args, cwd, env, title) }
                    if (tabTitle != null) {
                        checkAllowed(sourceId)
                        backend { host.title(child, tabTitle) }
                    }
                    if (!payload.bool("keep_focus")) backend { host.focus(child) }
                    child.id.toString()
                }
                "focus-window", "focus-tab", "set-window-title", "set-tab-title", "close-window", "close-tab", "resize-window", "send-text", "send-key" -> {
                    val selected = targets(payload, source, scope)
                    val title = if (cmd.startsWith("set-")) checkedTitle(payload.string("title", "Terminal")) else ""
                    val text = if (cmd == "send-text") decodeText(payload) else null
                    val keys = if (cmd == "send-key") payload.strings("keys", 64).also { require(it.isNotEmpty()) } else emptyList()
                    // Validate every key for every target before causing any input side effects.
                    val encodedKeys = if (cmd == "send-key") selected.associateWith { host.keys(it, keys) } else emptyMap()
                    val ordered = when {
                        cmd.startsWith("focus-") -> selected.take(1)
                        cmd.startsWith("close-") -> selected.sortedBy { it.session.id == sourceId }
                        else -> selected
                    }
                    for (window in ordered) {
                        checkAllowed(sourceId)
                        when (cmd) {
                            "focus-window", "focus-tab" -> backend { host.focus(window) }
                            "set-window-title", "set-tab-title" -> backend { host.title(window, title) }
                            "close-window", "close-tab" -> backend { host.close(window) }
                            "send-text" -> backend { host.write(window, text!!) }
                            "send-key" -> backend { host.write(window, encodedKeys.getValue(window)) }
                            "resize-window" -> {
                                val axis = payload.string("axis", "horizontal")
                                require(axis in setOf("horizontal", "vertical")) { "Unsupported resize axis" }
                                val increment = payload.getInt("increment")
                                require(increment in -4096..4096) { "Resize increment out of range" }
                                backend { host.resize(window,
                                    (window.session.rows.toInt() + if (axis == "vertical") increment else 0).coerceIn(1, 4096),
                                    (window.session.cols.toInt() + if (axis == "horizontal") increment else 0).coerceIn(1, 4096)) }
                            }
                        }
                    }
                    null
                }
                else -> error("Unsupported remote command: ${cmd.take(64)}")
            }
            JSONObject().put("ok", true).apply { if (data != null) put("data", data) }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            // Backend errors can include host paths; only protocol validation messages are exposed.
            JSONObject().put("ok", false).put("error", if (error is IllegalArgumentException || error is IllegalStateException)
                error.message?.take(200) ?: "Invalid request" else "Remote command failed")
        }
        return if (noResponse) null else ("\u001bP@kitty-cmd" + response.toString() + "\u001b\\").toByteArray()
    }

    private suspend fun <T> backend(action: suspend () -> T): T = try { action() }
        catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { throw IllegalStateException("Runtime operation failed") }

    private fun validateNesting(json: String) {
        var depth = 0
        var quote = '\u0000'
        var escaped = false
        for (ch in json) {
            if (quote != '\u0000') {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == quote) quote = '\u0000'
            } else when (ch) {
                '\"', '\'' -> quote = ch
                '{', '[' -> { depth++; require(depth <= 16) { "Request nesting too deep" } }
                '}', ']' -> { depth--; require(depth >= 0) { "Invalid request nesting" } }
            }
        }
        require(depth == 0 && quote == '\u0000') { "Incomplete request" }
    }

    private fun checkAllowed(source: String) { require(host.allowed(source)) { "Remote control permission revoked" } }
    private fun targets(p: JSONObject, source: Window, scope: List<Window>, defaultAll: Boolean = false): List<Window> {
        val match = p.string("match", p.string("match_tab", ""))
        val selected = when {
            p.bool("self") -> listOf(source)
            p.bool("all") -> scope
            match.isEmpty() -> if (defaultAll) scope else listOf(source)
            match == "all" -> scope
            match == "state:focused" || match == "state:active" -> scope.filter { it.session.id == host.focused() }
            match.startsWith("id:") -> scope.filter { it.id == match.substringAfter(':').toIntOrNull() }
            match.startsWith("title:") -> scope.filter { it.session.title == match.substringAfter(':') }
            else -> throw IllegalArgumentException("Unsupported match; use id:, exact title:, state:focused or all")
        }.filterNot { p.bool("exclude_active") && it.session.id == host.focused() }
        require(selected.isNotEmpty()) { "No matching window in the permitted runtime" }
        return selected
    }
    private fun decodeText(p: JSONObject): ByteArray {
        require(!p.has("session_id") || p.string("session_id", "").isEmpty()) { "Broadcast sessions are unsupported" }
        val raw = p.getString("data")
        val bytes = when {
            raw.startsWith("text:") -> raw.substring(5).toByteArray()
            raw.startsWith("base64:") -> Base64.getDecoder().decode(raw.substring(7))
            else -> throw IllegalArgumentException("Expected text: or base64: data")
        }
        require(bytes.size <= 32768) { "Text too large" }
        return when (p.string("bracketed_paste", "disable")) {
            "disable" -> bytes
            "enable" -> "\u001b[200~".toByteArray() + bytes + "\u001b[201~".toByteArray()
            else -> throw IllegalArgumentException("Use explicit enable or disable bracketed_paste")
        }
    }
    private fun checkedTitle(value: String): String {
        require(value.codePointCount(0, value.length) in 1..64 && value.toByteArray().size <= 256 && value.none { it.code < 32 || it.code in 127..159 }) { "Invalid window title" }
        return value
    }
}

private fun JSONObject.string(key: String, default: String): String = if (!has(key) || isNull(key)) default else get(key).let {
    require(it is String) { "Expected string: $key" }; it
}
private fun JSONObject.bool(key: String): Boolean = if (!has(key) || isNull(key)) false else get(key).let {
    require(it is Boolean) { "Expected boolean: $key" }; it
}
private fun JSONObject.strings(key: String, max: Int): List<String> {
    if (!has(key)) return emptyList()
    val array = getJSONArray(key)
    require(array.length() <= max) { "Too many $key" }
    return List(array.length()) { index -> array.get(index).let {
        require(it is String && '\u0000' !in it) { "Invalid $key" }; it
    } }
}
