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
import dev.terminal.runtime.lifecycle.VmRuntimeService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionMemoryDeviceTest {
    @Test fun nativeSessionHasRealResidentMemoryAndDisappearsAfterTermination() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connected = CountDownLatch(1)
        lateinit var service: IRuntimeService
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = IRuntimeService.Stub.asInterface(binder); connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        var owned: String? = null
        try {
            assertTrue(connected.await(10, TimeUnit.SECONDS))

            val previous = ids(service.sessionsJson)
            service.createSession("NATIVE", "/system/bin/sh", "/", 24, 80)
            await { owned = (ids(service.sessionsJson) - previous).singleOrNull(); owned != null }
            val runtimeMemory = JSONObject(service.runtimeAppMemoryJson)
            val uiMemory = requireNotNull(dev.terminal.runtime.session.currentAppProcessMemory())
            assertTrue(runtimeMemory.getLong("total") > 0)
            val appMemory = requireNotNull(combineAppMemory(uiMemory, runtimeMemory))
            assertEquals(uiMemory.getLong("total") + runtimeMemory.getLong("total"), appMemory.getLong("total"))
            assertTrue(JSONObject(service.getSessionMemoryJson(requireNotNull(owned))).getLong("rssBytes") > 0)
            val before = ids(service.sessionsJson)
            androidx.test.core.app.ActivityScenario.launch<SessionInfoActivity>(
                SessionInfoActivity.intent(context, requireNotNull(owned), "RSS fixture")).use { scenario ->
                var measured: Long? = null
                await { scenario.onActivity { measured = it.rss }; measured != null }
                assertTrue(requireNotNull(measured) > 0)
                scenario.recreate()
                await { scenario.onActivity { measured = it.rss }; measured != null }
            }
            assertEquals(before, ids(service.sessionsJson))
            service.terminateSession(owned)
            await { owned !in ids(service.sessionsJson) }
            assertTrue(JSONObject(service.getSessionMemoryJson(requireNotNull(owned))).isNull("rssBytes"))
            owned = null
            // Each new fork must capture its identity, including non-debuggable builds.
            repeat(20) {
                service.createSession("NATIVE", "/system/bin/sh", "/", 24, 80)
                await { owned = (ids(service.sessionsJson) - previous).singleOrNull(); owned != null }
                assertTrue("New session RSS must be available",
                    JSONObject(service.getSessionMemoryJson(requireNotNull(owned))).getLong("rssBytes") > 0)
                service.terminateSession(owned)
                await { owned !in ids(service.sessionsJson) }
                owned = null
            }
        } finally {
            owned?.let { service.terminateSession(it) }
            context.unbindService(connection)
        }
    }
    private fun ids(json: String) = JSONArray(json).let { list -> (0 until list.length()).map { list.getJSONObject(it).getString("id") }.toSet() }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (!condition()) { check(SystemClock.elapsedRealtime() < deadline) { "Runtime condition timed out" }; SystemClock.sleep(100) }
    }
}
