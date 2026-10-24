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
import dev.terminal.runtime.session.Environment
import dev.terminal.runtime.session.SessionProfile
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.session.SessionStorage
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnvironmentInputIsolationDeviceTest {
    @Test fun unreadRawPtyInOneEnvironmentDoesNotBlockInputInAnother() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val queue = LinkedBlockingQueue<IRuntimeService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { queue.offer(IRuntimeService.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        val service = requireNotNull(queue.poll(15, TimeUnit.SECONDS))
        val store = SessionProfiles(context)
        val environments = listOf(Environment.create(SessionRuntime.NATIVE, "Blocked input"),
            Environment.create(SessionRuntime.NATIVE, "Healthy input"))
        val profiles = environments.map { environment ->
            SessionProfile(UUID.randomUUID().toString(), environment.name, SessionRuntime.NATIVE,
                "/system/bin/sh", environmentId = environment.id, storageId = environment.storage.id)
        }
        val sessions = mutableListOf<String>()
        try {
            for ((environment, profile) in environments.zip(profiles)) {
                store.save(profile, environment)
                service.createSessionFromSpec(store.resolve(profile).toJson().toString(), 24, 80)
                await("Native session") { rows(service.sessionsJson).any { it.optString("profileId") == profile.id } }
                sessions += rows(service.sessionsJson).single { it.optString("profileId") == profile.id }.getString("id")
            }
            val blockedHome = File(SessionStorage.root(context.filesDir, SessionRuntime.NATIVE, environments[0].storage.id), "home")
            val healthyHome = File(SessionStorage.root(context.filesDir, SessionRuntime.NATIVE, environments[1].storage.id), "home")
            val armed = File(blockedHome, "input-armed")
            val finished = File(blockedHome, "input-sleep-finished")
            service.writeSessionInput(sessions[0],
                "stty raw -echo; printf ready > \"\$HOME/input-armed\"; sleep 20; printf done > \"\$HOME/input-sleep-finished\"\n".toByteArray())
            await("Unread raw PTY is armed") { armed.readTextOrNull() == "ready" }
            // Raw input without a reader backpressures the PTY. A following input
            // packet also stays behind this write in the same Environment lane.
            service.writeSessionInput(sessions[0], ByteArray(64 * 1024) { 'x'.code.toByte() })
            service.writeSessionInput(sessions[0], "still queued\n".toByteArray())
            service.writeSessionInput(sessions[1], "printf '".toByteArray())
            service.writeSessionInput(sessions[1], "ORDER_OK".toByteArray())
            service.writeSessionInput(sessions[1], "' > \"\$HOME/input-result\"\n".toByteArray())
            await("Sibling input remains ordered and makes progress", 5_000) {
                File(healthyHome, "input-result").readTextOrNull() == "ORDER_OK"
            }
            assertFalse("The stalled Environment must still be sleeping", finished.exists())
            assertTrue(rows(service.sessionsJson).any { it.getString("id") == sessions[0] && it.getString("state") == "RUNNING" })
        } finally {
            sessions.forEach { runCatching { service.terminateSession(it) } }
            environments.forEach { runCatching { service.operateEnvironment(it.id.value, "STOP") } }
            profiles.forEach { store.delete(it.id) }
            context.unbindService(connection)
        }
    }

    private fun File.readTextOrNull(): String? = if (isFile) readText() else null
    private fun rows(json: String): List<JSONObject> = JSONArray(json).let { array -> List(array.length()) { array.getJSONObject(it) } }
    private fun await(description: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        throw AssertionError(description)
    }
}
