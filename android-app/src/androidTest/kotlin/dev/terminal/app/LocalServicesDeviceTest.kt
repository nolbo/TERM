package dev.terminal.app

import android.content.*
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.ipc.ILocalServicesListener
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.ipc.ITerminalStateListener
import dev.terminal.runtime.lifecycle.VmRuntimeService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LocalServicesDeviceTest {
    @Test fun nativeShellStateAndInputSurviveDashboardRecreationAndReturn() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connected = CountDownLatch(1)
        lateinit var runtime: IRuntimeService
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { runtime = IRuntimeService.Stub.asInterface(binder); connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        val output = StringBuffer()
        val listener = object : ITerminalStateListener.Stub() {
            override fun onCheckpoint(state: ParcelFileDescriptor) {
                ParcelFileDescriptor.AutoCloseInputStream(state).use { it.readBytes() }
            }
            override fun onOutput(bytes: ByteArray) { output.append(bytes.toString(Charsets.UTF_8)) }
            override fun onResize(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {}
            override fun onExited(code: Int) {}
            override fun onError(message: String) { output.append("ERROR:$message") }
        }
        fun await(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertTrue("$message; output=${output.toString().takeLast(1000)}", condition())
        }
        var owned: String? = null
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
            try {
                assertTrue(connected.await(10, TimeUnit.SECONDS))
                fun running(): Set<String> = JSONArray(runtime.sessionsJson).let { rows ->
                    (0 until rows.length()).map { rows.getJSONObject(it) }
                        .filter { it.getString("state") == "RUNNING" }.map { it.getString("id") }.toSet()
                }
                val before = running()
                runtime.createSession("NATIVE", "/system/bin/sh", "", 24, 80)
                await("Native test session created") { (running() - before).size == 1 }
                val id = (running() - before).single().also { owned = it }
                runtime.attachTerminal(id, listener, 24, 80, 10, 20, "{}")
                val nonce = UUID.randomUUID().toString()
                runtime.writeSessionInput(id, "TERM_LOCAL_SERVICE_TEST='$nonce'\nprintf 'before:%s\\n' \"\$TERM_LOCAL_SERVICE_TEST\"\n".toByteArray())
                await("Native shell accepted input") { output.contains("before:$nonce") }
                ActivityScenario.launch<LocalServicesActivity>(LocalServicesActivity.intent(context)).use { dashboard ->
                    dashboard.recreate()
                    assertTrue(running().containsAll(before + id))
                }
                runtime.writeSessionInput(id, "printf 'after:%s\\n' \"\$TERM_LOCAL_SERVICE_TEST\"\n".toByteArray())
                await("The same shell retained its variable and accepts input after return") { output.contains("after:$nonce") }
            } finally {
                owned?.let { id -> runCatching { runtime.detachTerminal(id, listener) }; runCatching { runtime.terminateSession(id) } }
                context.unbindService(connection)
            }
        }
    }

    @Test fun dashboardRecreationAndCrudPreserveSessionsAndNeverStartRuntime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connected = CountDownLatch(1)
        lateinit var runtime: IRuntimeService
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { runtime = IRuntimeService.Stub.asInterface(binder); connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        val id = "manual:${UUID.randomUUID()}"
        try {
            assertTrue(connected.await(10, TimeUnit.SECONDS))
            val originalSessions = runtime.sessionsJson
            val originalState = runtime.state
            ActivityScenario.launch<LocalServicesActivity>(LocalServicesActivity.intent(context, "AVF")).use { activity ->
                val changed = CountDownLatch(1)
                val listener = object : ILocalServicesListener.Stub() { override fun onServicesChanged() { changed.countDown() } }
                runtime.registerLocalServicesListener(listener)
                try {
                    val input = JSONObject().put("id", id).put("name", "Dashboard device test").put("port", 18099)
                        .put("environmentId", JSONObject.NULL).put("applicationProtocol", "HTTPS")
                    assertEquals("", runtime.saveLocalService(input.toString()))
                    assertTrue(changed.await(5, TimeUnit.SECONDS))
                    activity.recreate()
                    fun row(): JSONObject = JSONArray(JSONObject(runtime.localServicesJson).getJSONArray("services").toString())
                        .let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("id") == id } }
                    assertEquals("REGISTERED_UNVERIFIED", row().getString("observation"))
                    assertEquals("NONE", row().getString("forwarding"))
                    assertEquals("", runtime.saveLocalService(input.put("port", 18100).toString()))
                    assertEquals(18100, row().getInt("port"))
                    assertEquals("INVALID_PORT", runtime.saveLocalService(input.put("port", 0).toString()))
                    assertEquals(18100, row().getInt("port"))
                } finally { runtime.unregisterLocalServicesListener(listener) }
            }
            assertEquals(originalSessions, runtime.sessionsJson)
            assertEquals(originalState, runtime.state)
            assertEquals("", runtime.deleteLocalService(id))
            assertFalse(JSONObject(runtime.localServicesJson).getJSONArray("services").toString().contains(id))
        } finally {
            if (connected.count == 0L) runCatching { runtime.deleteLocalService(id) }
            context.unbindService(connection)
        }
    }
}
