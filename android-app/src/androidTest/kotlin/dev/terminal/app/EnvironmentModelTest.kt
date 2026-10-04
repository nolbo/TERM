package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import dev.terminal.runtime.session.Environment
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.session.SessionStorage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Real :vmhost ownership, shared persistent state, profile edits and UI detach/recreation. */
@RunWith(AndroidJUnit4::class)
class EnvironmentModelTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun rows(encoded: String) = JSONArray(encoded).let { array -> List(array.length()) { array.getJSONObject(it) } }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (!condition()) { check(SystemClock.elapsedRealtime() < deadline) { message }; SystemClock.sleep(100) }
    }

    @Test fun explicitlyStartedEnvironmentSurvivesUiDetachWithoutSessions() {
        val store = SessionProfiles(context)
        val environment = Environment.create(SessionRuntime.NATIVE, "Idle environment ${UUID.randomUUID()}")
        val profile = store.save(store.duplicate(store.builtIns().first { it.runtime == SessionRuntime.NATIVE }), environment)
        fun bind(): Pair<ServiceConnection, IRuntimeService> {
            val queue = LinkedBlockingQueue<IRuntimeService>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) { queue.offer(IRuntimeService.Stub.asInterface(binder)) }
                override fun onServiceDisconnected(name: ComponentName) = Unit
            }
            assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
            return connection to requireNotNull(queue.poll(15, TimeUnit.SECONDS))
        }
        var binding: Pair<ServiceConnection, IRuntimeService>? = bind()
        try {
            val original = binding!!.second
            assertTrue("This lifecycle fixture requires no active session", rows(original.sessionsJson).all { it.getString("state") == "EXITED" })
            assertEquals("", original.operateEnvironment(environment.id.value, "START"))
            val marker = File(SessionStorage.root(context.filesDir, SessionRuntime.NATIVE, environment.storage.id), "home/keep").apply { writeText("preserved") }
            ActivityScenario.launch<EnvironmentDetailActivity>(EnvironmentDetailActivity.intent(context, environment.id.value)).use { it.recreate() }
            context.unbindService(binding!!.first)
            binding = null
            // Give Android the opportunity to destroy a service owned only by its UI bindings.
            SystemClock.sleep(1000)
            binding = bind()
            val service = binding!!.second
            assertEquals("UI detach must retain the runtime owner", original.asBinder(), service.asBinder())
            assertEquals("READY", rows(service.environmentsJson).single { it.getString("id") == environment.id.value }.getString("runtimeState"))
            assertTrue(rows(service.sessionsJson).none { it.optString("environmentId") == environment.id.value })
            assertEquals("preserved", marker.readText())
            assertEquals("", service.operateEnvironment(environment.id.value, "STOP"))
            assertEquals("STOPPED", rows(service.environmentsJson).single { it.getString("id") == environment.id.value }.getString("runtimeState"))
            assertTrue(marker.exists())
        } finally {
            if (binding == null) binding = bind()
            store.delete(profile.id)
            binding!!.second.deleteEnvironment(environment.id.value)
            context.unbindService(binding!!.first)
        }
    }

    @Test fun profilesShareEnvironmentAndEditingPreservesOldSessionsAndDeletionIsolation() {
        val store = SessionProfiles(context)
        val label = "Environment fixture ${UUID.randomUUID()}"
        val firstEnvironment = Environment.create(SessionRuntime.NATIVE, "$label first")
        val secondEnvironment = Environment.create(SessionRuntime.NATIVE, "$label second")
        val template = store.builtIns().first { it.runtime == SessionRuntime.NATIVE }
        val first = store.save(store.duplicate(template).copy(name = "$label profile"), firstEnvironment)
        val sibling = store.save(store.duplicate(template).copy(name = "$label sibling"), secondEnvironment)
        val shared = store.duplicate(first).copy(name = "$label shared", environmentId = first.environmentId)
        store.save(shared)
        val queue = LinkedBlockingQueue<IRuntimeService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { queue.offer(IRuntimeService.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        val service = requireNotNull(queue.poll(15, TimeUnit.SECONDS))
        val owned = mutableSetOf<String>()
        fun sessions() = rows(service.sessionsJson)
        fun launch(profileId: String): JSONObject {
            service.createSessionFromSpec(store.resolve(requireNotNull(store.find(profileId))).toJson().toString(), 24, 80)
            await("Session was not created for $profileId") { sessions().any { it.optString("profileId") == profileId && it.getString("id") !in owned } }
            return sessions().first { it.optString("profileId") == profileId && it.getString("id") !in owned }.also { owned += it.getString("id") }
        }
        try {
            val old = launch(first.id)
            val sharedSession = launch(shared.id)
            assertEquals(firstEnvironment.id.value, sharedSession.getString("environmentId"))
            val root = SessionStorage.root(context.filesDir, SessionRuntime.NATIVE, firstEnvironment.storage.id)
            val marker = File(root, "home/environment-marker")
            service.writeSessionInput(old.getString("id"), "printf shared > \"\$HOME/environment-marker\"\n".toByteArray())
            await("Shared home marker missing") { marker.exists() && marker.readText() == "shared" }
            val reply = File(context.cacheDir, "environment-${UUID.randomUUID()}")
            service.writeSessionInput(sharedSession.getString("id"), "cat \"\$HOME/environment-marker\" > '${reply.absolutePath}'\n".toByteArray())
            await("Shared session used a different home") { reply.exists() && reply.readText() == "shared" }
            reply.delete()
            val originalSnapshot = old.getJSONObject("launchSpec").toString()
            store.save(first.copy(environmentId = secondEnvironment.id))
            val next = launch(first.id)
            assertEquals(secondEnvironment.id.value, next.getString("environmentId"))
            assertEquals(firstEnvironment.id.value, sessions().single { it.getString("id") == old.getString("id") }.getString("environmentId"))
            assertEquals("", service.renameEnvironment(firstEnvironment.id.value, "$label renamed"))
            assertEquals(originalSnapshot, sessions().single { it.getString("id") == old.getString("id") }.getJSONObject("launchSpec").toString())
            val reopened = SessionProfiles(context)
            assertEquals(secondEnvironment.id, reopened.find(first.id)?.environmentId)
            assertEquals(firstEnvironment.id, reopened.find(shared.id)?.environmentId)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                service.attachSession(old.getString("id")); service.detachSession(old.getString("id"))
                scenario.recreate()
                await("Activity recreation lost sessions") { owned.all { id -> sessions().any { it.getString("id") == id } } }
            }
            ActivityScenario.launch<EnvironmentDetailActivity>(EnvironmentDetailActivity.intent(context, firstEnvironment.id.value)).use { it.recreate() }
            assertTrue(owned.all { id -> sessions().any { it.getString("id") == id } })
            store.delete(shared.id)
            assertTrue("Active sessions must prevent deletion", service.deleteEnvironment(firstEnvironment.id.value).isNotEmpty())
            assertTrue("Active sessions must prevent stopping", service.operateEnvironment(firstEnvironment.id.value, "STOP").isNotEmpty())
            assertTrue(marker.exists())
            for (id in listOf(old.getString("id"), sharedSession.getString("id"))) {
                service.terminateSession(id)
                await("Termination did not complete") { sessions().none { it.getString("id") == id } }
                owned.remove(id)
            }
            assertEquals("", service.operateEnvironment(firstEnvironment.id.value, "STOP"))
            assertEquals("STOPPED", rows(service.environmentsJson).single { it.getString("id") == firstEnvironment.id.value }.getString("runtimeState"))
            assertTrue("Stopping must preserve persistent data", marker.exists())
            assertEquals("", service.operateEnvironment(firstEnvironment.id.value, "START"))
            assertTrue("Starting an environment must not create a session", sessions().none { it.optString("environmentId") == firstEnvironment.id.value })
            assertTrue(marker.exists())
            val siblingRoot = SessionStorage.root(context.filesDir, SessionRuntime.NATIVE, secondEnvironment.storage.id)
            val keep = File(siblingRoot, "home/keep").apply { parentFile!!.mkdirs(); writeText("preserved") }
            assertEquals("", service.deleteEnvironment(firstEnvironment.id.value))
            assertFalse(root.exists())
            assertEquals("preserved", keep.readText())
            assertTrue(sessions().any { it.getString("id") == next.getString("id") })
            assertFalse(rows(service.environmentsJson).any { it.getString("id") == firstEnvironment.id.value })
            assertEquals(secondEnvironment.id, SessionProfiles(context).find(sibling.id)?.environmentId)
        } finally {
            owned.forEach { service.terminateSession(it) }
            await("Fixture sessions did not terminate") { sessions().none { it.getString("id") in owned } }
            listOf(first.id, sibling.id, shared.id).forEach(store::delete)
            service.deleteEnvironment(firstEnvironment.id.value)
            service.deleteEnvironment(secondEnvironment.id.value)
            context.unbindService(connection)
        }
    }
}
