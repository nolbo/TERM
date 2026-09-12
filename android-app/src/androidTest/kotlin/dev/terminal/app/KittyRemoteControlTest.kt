package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.ipc.IRuntimeStateListener
import dev.terminal.runtime.lifecycle.VmRuntimeService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Requires provisioned Rootless Debian. Cleans up only the sessions this test creates. */
@RunWith(AndroidJUnit4::class)
class KittyRemoteControlTest {
    @Test fun nativeAndRootlessPtyRemoteCommandsSurviveDetachAndNeverReplay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connected = CountDownLatch(1)
        lateinit var service: IRuntimeService
        val outputs = ConcurrentHashMap<String, StringBuilder>()
        val focused = java.util.concurrent.atomic.AtomicReference<String>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { service = IRuntimeService.Stub.asInterface(binder); connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val listener = object : IRuntimeStateListener.Stub() {
            override fun onTerminalOutput(id: String, bytes: ByteArray, replay: Boolean) {
                if (!replay) outputs.getOrPut(id) { StringBuilder() }.let { synchronized(it) { it.append(bytes.decodeToString()) } }
            }
            override fun onSessionFocusRequested(id: String) { focused.set(id) }
            override fun onRuntimeStateChanged(state: String, detail: String) = Unit
            override fun onSessionsChanged(revision: Long, json: String) = Unit
            override fun onSessionExited(id: String, code: Int) = Unit
            override fun onSessionTerminationFailed(id: String, message: String) = Unit
            override fun onRootlessProgress(runtime: String, phase: String, detail: String) = Unit
            override fun onOperationError(operation: String, message: String) = Unit
        }
        assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        val owned = mutableSetOf<String>()
        var oldRemote = false
        var oldGraphics = false
        try {
            assertTrue(connected.await(10, TimeUnit.SECONDS))
            val preferences = context.getSharedPreferences("runtime-settings", Context.MODE_PRIVATE)
            oldRemote = preferences.getBoolean("kitty-remote-control", false)
            oldGraphics = preferences.getBoolean("kitty-graphics-compatibility", false)
            service.startRuntime()
            await { service.state == "READY" }
            service.registerListener(listener)
            fun sessions(): List<JSONObject> { val a = JSONArray(service.sessionsJson); return List(a.length()) { a.getJSONObject(it) } }
            fun create(runtime: String): String {
                val previous = sessions().map { it.getString("id") }.toSet()
                service.createSession(runtime, if (runtime == "NATIVE") "/system/bin/sh" else "/bin/bash", "/", 24, 80)
                var id: String? = null
                await { id = (sessions().map { it.getString("id") }.toSet() - previous).singleOrNull(); id != null }
                owned += id!!
                SystemClock.sleep(200)
                return id!!
            }
            var serial = 0
            fun request(id: String, cmd: String, p: JSONObject = JSONObject()): JSONObject {
                val token = "KITTY_RESULT_${++serial}_"
                val json = JSONObject().put("cmd", cmd).put("version", JSONArray(listOf(0, 46, 0))).put("payload", p).toString()
                fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
                // Read the raw reply inside the PTY and print only base64 so the response cannot be interpreted as a request.
                val command = "set +m; saved=\$(stty -g); stty raw -echo; printf '\\033P@kitty-cmd%s\\033\\\\' ${quote(json)}; reply=\$(timeout --foreground 1 cat); stty \"\$saved\"; printf '$token'; printf '%s' \"\$reply\" | base64 | tr -d '\\n'; printf ':END\\n'\n"
                service.writeSessionInput(id, command.toByteArray())
                var encoded: String? = null
                await {
                    val output = outputs[id]?.let { synchronized(it) { it.toString() } }.orEmpty()
                    encoded = Regex("${token}([A-Za-z0-9+/=]+):END").find(output)?.groupValues?.get(1)
                    encoded != null
                }
                val response = String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))
                return JSONObject(response.removePrefix("\u001bP@kitty-cmd").removeSuffix("\u001b\\"))
            }
            service.setKittyRemoteControl(false)
            val denied = create("NATIVE")
            assertFalse(request(denied, "ls").getBoolean("ok"))
            service.setKittyRemoteControl(true)
            assertFalse(request(denied, "ls").getBoolean("ok"))
            for (runtime in listOf("NATIVE", "ROOTLESS")) {
                val source = create(runtime)
                service.detachSession(source)
                val listed = request(source, "ls")
                assertTrue(listed.toString(), listed.getBoolean("ok"))
                val before = sessions().map { it.getString("id") }.toSet()
                val launch = request(source, "launch", JSONObject().put("args", JSONArray()).put("window_title", "Kitty test child"))
                assertTrue(launch.toString(), launch.getBoolean("ok"))
                val childId = (sessions().map { it.getString("id") }.toSet() - before).single()
                owned += childId
                val numericId = launch.getString("data")
                await { focused.get() == childId }
                assertEquals(runtime, sessions().single { it.getString("id") == childId }.getString("runtime"))
                service.attachSession(source)
                SystemClock.sleep(300)
                assertEquals(before.size + 1, sessions().size) // Replaying the launch must never execute it again.
                assertFalse(request(childId, "ls").getBoolean("ok"))
                val target = JSONObject().put("match", "id:$numericId")
                assertTrue(request(source, "set-window-title", JSONObject(target.toString()).put("title", "Kitty renamed")).getBoolean("ok"))
                assertTrue(request(source, "send-text", JSONObject(target.toString()).put("data", "text:printf 'KITTY_INPUT_OK\\n'\n")).getBoolean("ok"))
                await { outputs[childId]?.let { synchronized(it) { it.contains("KITTY_INPUT_OK") } } == true }
                assertTrue(request(source, "close-window", target).getBoolean("ok"))
                await { sessions().none { it.getString("id") == childId } }
                service.setKittyRemoteControl(false)
                assertFalse(request(source, "ls").getBoolean("ok"))
                service.setKittyRemoteControl(true)
            }
        } catch (error: Throwable) {
            outputs.forEach { (id, value) -> println("KITTY_DIAGNOSTIC ${id.substringBefore('-')}: ${synchronized(value) { value.takeLast(3000) }}") }
            throw error
        } finally {
            if (connected.count == 0L) {
                owned.forEach { service.terminateSession(it) }
                service.setKittyRemoteControl(oldRemote)
                service.setKittyGraphicsCompatibility(oldGraphics)
                service.unregisterListener(listener)
            }
            context.unbindService(connection)
        }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (!condition()) { assertTrue("Timed out waiting for kitty runtime result", SystemClock.uptimeMillis() < deadline); SystemClock.sleep(50) }
    }
}
