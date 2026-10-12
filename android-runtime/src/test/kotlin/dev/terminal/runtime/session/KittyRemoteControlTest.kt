package dev.terminal.runtime.session

import dev.terminal.protocol.SessionDescriptor
import dev.terminal.protocol.SessionStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KittyRemoteControlTest {
    private class Host : KittyRemoteControl.Host {
        var enabled = false
        var focused = "native-a"
        val windows = mutableListOf(window(1, "native-a", SessionRuntime.NATIVE), window(2, "native-b", SessionRuntime.NATIVE), window(3, "rootless-a", SessionRuntime.ROOTLESS))
        val actions = mutableListOf<String>()
        var argv = emptyList<String>()
        override fun allowed(source: String) = enabled && source == "native-a"
        override suspend fun windows() = windows.toList()
        override fun focused() = focused
        override suspend fun launch(source: KittyRemoteControl.Window, args: List<String>, cwd: String, env: Map<String, String>, title: String): KittyRemoteControl.Window {
            argv = args; actions += "launch"; return window(4, "native-child", source.runtime).also { windows += it }
        }
        override suspend fun focus(window: KittyRemoteControl.Window) { focused = window.session.id; actions += "focus:${window.id}" }
        override suspend fun title(window: KittyRemoteControl.Window, title: String) { actions += "title:${window.id}:$title" }
        override suspend fun close(window: KittyRemoteControl.Window) { actions += "close:${window.id}"; if (window.session.id == "native-a") enabled = false }
        override suspend fun resize(window: KittyRemoteControl.Window, rows: Int, cols: Int) { actions += "resize:$rows:$cols" }
        override suspend fun write(window: KittyRemoteControl.Window, bytes: ByteArray) { actions += "write:${window.id}:${bytes.decodeToString()}" }
        override fun keys(window: KittyRemoteControl.Window, keys: List<String>) = KittyRemoteKeys.encode(keys, 3)
    }
    private suspend fun call(host: Host, cmd: String, p: JSONObject = JSONObject(), extra: JSONObject = JSONObject()): JSONObject {
        val request = extra.put("cmd", cmd).put("version", JSONArray(listOf(0, 46, 0))).put("payload", p)
        val bytes = KittyRemoteControl(host).execute("native-a", request.toString())!!
        return JSONObject(bytes.decodeToString().removePrefix("\u001bP@kitty-cmd").removeSuffix("\u001b\\"))
    }
    @Test fun defaultDenyAndSpoofedIdentityCauseNoEffects() = runBlocking {
        val host = Host()
        assertFalse(call(host, "close-window").getBoolean("ok"))
        host.enabled = true
        assertFalse(call(host, "close-window", extra = JSONObject().put("kitty_window_id", 2)).getBoolean("ok"))
        assertTrue(host.actions.isEmpty())
    }
    @Test fun scopeCannotCrossRuntimesAndListingOmitsOtherEnvironment() = runBlocking {
        val host = Host().apply { enabled = true }
        assertFalse(call(host, "send-text", JSONObject().put("match", "id:3").put("data", "text:bad")).getBoolean("ok"))
        val listed = call(host, "ls").getString("data")
        assertFalse(listed.contains("rootless"))
        assertEquals(2, JSONArray(listed).getJSONObject(0).getJSONArray("tabs").length())
        assertTrue(host.actions.isEmpty())
    }
    @Test fun windowCommandsAndInputAreTypedAndTargeted() = runBlocking {
        val host = Host().apply { enabled = true }
        assertTrue(call(host, "focus-window", JSONObject().put("match", "id:2")).getBoolean("ok"))
        assertTrue(call(host, "set-tab-title", JSONObject().put("match", "id:2").put("title", "작업")).getBoolean("ok"))
        assertTrue(call(host, "send-text", JSONObject().put("match", "id:2").put("data", "base64:aGkK")).getBoolean("ok"))
        assertTrue(call(host, "send-key", JSONObject().put("match", "id:2").put("keys", JSONArray(listOf("ctrl+c")))).getBoolean("ok"))
        assertEquals(listOf("focus:2", "title:2:작업", "write:2:hi\n", "write:2:\u001b[99;5u\u001b[99;5:3u"), host.actions)
        assertFalse(call(host, "send-key", JSONObject().put("keys", JSONArray(listOf("a", "unknown_key")))).getBoolean("ok"))
        assertEquals(4, host.actions.size)
    }
    @Test fun closeAllTerminatesSourceLastSoItsGrantRemainsValidForSiblingClosures() = runBlocking {
        val host = Host().apply { enabled = true }
        assertTrue(call(host, "close-window", JSONObject().put("all", true)).getBoolean("ok"))
        assertEquals(listOf("close:2", "close:1"), host.actions)
    }
    @Test fun launchPreservesArgvAndCannotDelegateOrInjectInternalEnvironment() = runBlocking {
        val host = Host().apply { enabled = true }
        val p = JSONObject().put("stdin_source", "none").put("location", "default").put("copy_env", JSONArray()).put("args", JSONArray(listOf("/bin/echo", "a b", "$(touch nope)", "'quoted'")))
        assertTrue(call(host, "launch", p).getBoolean("ok"))
        assertEquals(listOf("/bin/echo", "a b", "$(touch nope)", "'quoted'"), host.argv)
        val size = host.actions.size
        assertFalse(call(host, "launch", p.put("allow_remote_control", true)).getBoolean("ok"))
        p.remove("allow_remote_control")
        assertFalse(call(host, "launch", p.put("env", JSONArray(listOf("DROID_SESSION_ARGV=[]")))).getBoolean("ok"))
        assertEquals(size, host.actions.size)
        val child = KittyRemoteControl(host).execute("native-child", JSONObject().put("cmd", "ls").put("version", JSONArray(listOf(0, 46, 0))).toString())!!.decodeToString()
        assertTrue(child.contains("disabled"))
    }
    @Test fun malformedAndUnsupportedRequestsFailAndNoResponseIsHonored() = runBlocking {
        val host = Host().apply { enabled = true }
        val nested = "[".repeat(10000) + "]".repeat(10000)
        assertTrue(KittyRemoteControl(host).execute("native-a", nested)!!.decodeToString().contains("nesting"))
        assertTrue(KittyRemoteControl(host).execute("native-a", "{}{}")!!.decodeToString().contains("Invalid request"))
        assertFalse(call(host, "set-font-size").getBoolean("ok"))
        assertFalse(call(host, "send-text", JSONObject().put("data", "text:" + "x".repeat(32769))).getBoolean("ok"))
        assertFalse(call(host, "close-window", JSONObject().put("all", "true")).getBoolean("ok"))
        assertNull(KittyRemoteControl(host).execute("native-a", "{\"cmd\":\"ls\",\"version\":[0,46,0],\"no_response\":true}"))
    }
    @Test fun internalArgvValidationRejectsMalformedPayloads() {
        for (value in listOf("0:", "1:9:a", "1:0:", "1:6:--help", "1:3:A=B", "1:1:aextra")) {
            assertTrue(runCatching { SessionCommand.decode(mapOf(SessionCommand.KEY to value)) }.isFailure)
        }
        assertEquals(listOf("echo", "a b"), SessionCommand.decode(mapOf(SessionCommand.KEY to SessionCommand.encode(listOf("echo", "a b")))))
    }
    companion object {
        private fun window(id: Int, session: String, runtime: SessionRuntime) = KittyRemoteControl.Window(id, runtime,
            SessionDescriptor(session, session, "/bin/sh", "/", 1L, 24u, 80u, SessionStatus.RUNNING))
    }
}
