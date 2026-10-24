package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BackgroundPowerPolicyTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun defaultKeepsQuietSessionAwakeAfterUiDetachAndReleasesAtLastExit() {
        val queue = LinkedBlockingQueue<IRuntimeService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                queue.offer(IRuntimeService.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        var owned: String? = null
        val service = requireNotNull(queue.poll(15, TimeUnit.SECONDS))
        fun runningIds() = JSONArray(service.sessionsJson).let { sessions ->
            (0 until sessions.length()).map { sessions.getJSONObject(it) }
                .filter { it.getString("state") == "RUNNING" }.map { it.getString("id") }.toSet()
        }
        try {
            assertEquals("Fresh app defaults to protecting persistent sessions", "ALWAYS_AWAKE", service.wakePolicy)

            val before = runningIds()
            assertTrue("Run on an isolated test app with no existing sessions", before.isEmpty())
            service.createSession("NATIVE", "/system/bin/sh", "", 24, 80)
            await("test session") { owned = (runningIds() - before).singleOrNull(); owned != null }
            val id = checkNotNull(owned)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await("session acquires partial wake lock") { held() }
                val store = (context.applicationContext as TerminalApplication).terminalSessions
                fun hasMirror(): Boolean {
                    var attached = false
                    instrumentation.runOnMainSync { attached = store.hasMirror(id) }
                    return attached
                }
                await("UI mirror attached") { hasMirror() }
                // A known idle prompt must keep protection under the default policy.
                service.writeSessionInput(id, "printf '\\033]133;A\\007'\n".toByteArray())
                scenario.moveToState(Lifecycle.State.CREATED)
                await("UI grace expires") { !hasMirror() }
                assertTrue("Quiet background session keeps wake lock", held())
                assertTrue(runningIds().contains(id))
                service.terminateSession(id)
                await("last session releases partial wake lock") { runningIds().isEmpty() && !held() }
                owned = null
            }
        } finally {
            owned?.let(service::terminateSession)
            context.unbindService(connection)
        }
    }

    private fun held(): Boolean = instrumentation.uiAutomation.executeShellCommand("dumpsys power").use { fd ->
        ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { reader ->
            reader.lineSequence().any { it.contains("PARTIAL_WAKE_LOCK") && it.contains("DroidTerminal:guest-work") &&
                it.contains("uid=${android.os.Process.myUid()}") }
        }
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        throw AssertionError(description)
    }
}
