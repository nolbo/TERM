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
import dev.terminal.runtime.session.Environment
import dev.terminal.runtime.session.SessionProfile
import dev.terminal.runtime.session.SessionRuntime
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
class ProfileEnvironmentOwnershipTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun bindingStartsNothingAndProfilesOwnIndependentEnvironmentLifetimes() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val queue = LinkedBlockingQueue<IRuntimeService>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    queue.offer(IRuntimeService.Stub.asInterface(binder))
                }
                override fun onServiceDisconnected(name: ComponentName) = Unit
            }
            assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
            val service = requireNotNull(queue.poll(15, TimeUnit.SECONDS))
            val store = SessionProfiles(context)
            val first = Environment.create(SessionRuntime.NATIVE, "Owner A")
            val second = Environment.create(SessionRuntime.NATIVE, "Owner B")
            val profile = SessionProfile(UUID.randomUUID().toString(), "Owner A profile", SessionRuntime.NATIVE,
                "/system/bin/sh", environmentId = first.id, storageId = first.storage.id)
            val sibling = profile.copy(id = UUID.randomUUID().toString(), name = "Owner B profile",
                environmentId = second.id, storageId = second.storage.id)
            var session: String? = null
            try {
                assertTrue("Use an isolated test installation", rows(service.sessionsJson).isEmpty())
                store.save(profile, first); store.save(sibling, second)
                fun state(id: String) = rows(service.environmentsJson).single { it.getString("environmentId") == id }.getString("runtimeState")
                assertEquals("STOPPED", state(first.id.value)); assertEquals("STOPPED", state(second.id.value))
                assertFalse("Binding must not create a readiness backend", File(context.filesDir, "native-runtime").exists())
                assertFalse(held())

                service.createSessionFromSpec(store.resolve(profile).toJson().toString(), 24, 80)
                await("Profile A session") {
                    session = rows(service.sessionsJson).singleOrNull { it.optString("profileId") == profile.id }?.getString("id")
                    session != null
                }
                assertEquals("ACTIVE", state(first.id.value)); assertEquals("STOPPED", state(second.id.value))
                assertEquals("", service.operateEnvironment(second.id.value, "START"))
                assertEquals("READY", state(second.id.value))
                service.terminateSession(requireNotNull(session))
                await("Profile A exits") { rows(service.sessionsJson).none { it.getString("state") == "RUNNING" } }
                session = null
                scenario.moveToState(Lifecycle.State.CREATED)
                await("Explicit Environment B retains wake protection without sessions or UI") { held() }
                assertEquals("READY", state(second.id.value))
                assertEquals("", service.operateEnvironment(second.id.value, "STOP"))
                await("Last explicit Environment releases protection") { !held() }
            } finally {
                session?.let(service::terminateSession)
                runCatching { service.operateEnvironment(second.id.value, "STOP") }
                store.delete(profile.id); store.delete(sibling.id)
                context.unbindService(connection)
            }
        }
    }

    private fun rows(json: String): List<JSONObject> = JSONArray(json).let { array ->
        List(array.length()) { array.getJSONObject(it) }
    }

    private fun held(): Boolean = instrumentation.uiAutomation.executeShellCommand("dumpsys power").use { fd ->
        ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().useLines { lines ->
            lines.any { it.contains("PARTIAL_WAKE_LOCK") && it.contains("DroidTerminal:guest-work") &&
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
