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
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.session.SessionStorage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Installs two fresh catalog-pinned Debian filesystems; never modifies existing distro storage. */
@RunWith(AndroidJUnit4::class)
class RootlessProfileStorageTest {
    @Test fun independentPackageDatabasesAndExplicitSharingSurviveProfileDeletion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SessionProfiles(context)
        val template = store.builtIns().first { it.runtime == SessionRuntime.ROOTLESS }
        val first = store.duplicate(template).copy(name = "Rootless isolation A")
        val second = store.duplicate(template).copy(name = "Rootless isolation B")
        val shared = store.duplicate(first).copy(name = "Rootless shared", storageId = first.storageId)
        val queue = LinkedBlockingQueue<IRuntimeService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { queue.offer(IRuntimeService.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        val service = requireNotNull(queue.poll(15, TimeUnit.SECONDS))
        val sessions = mutableListOf<String>()
        fun list(): List<JSONObject> {
            val array = JSONArray(service.sessionsJson)
            return List(array.length()) { array.getJSONObject(it) }
        }
        fun await(timeout: Long = 20_000, check: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeout
            while (!check()) {
                check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for isolated Debian storage" }
                SystemClock.sleep(200)
            }
        }
        try {
            service.startRuntime()
            await { service.state == "READY" }
            for (profile in listOf(first, second, shared)) {
                store.save(profile)
                service.createSessionFromSpecWithInstallationConsent(profile.resolve().toJson().toString(), java.util.UUID.randomUUID().toString(), 24, 80)
                await(600_000) { list().any { it.optString("profileId") == profile.id } }
                sessions += list().first { it.optString("profileId") == profile.id }.getString("id")
            }
            val a = SessionStorage.root(context.filesDir, first.runtime, first.storageId)
            val b = SessionStorage.root(context.filesDir, second.runtime, second.storageId)
            assertNotEquals(a, b)
            assertTrue(File(a, "rootfs/var/lib/dpkg/status").isFile)
            assertTrue(File(b, "rootfs/var/lib/dpkg/status").isFile)
            service.writeSessionInput(sessions[0],
                "printf a > /var/lib/dpkg/profile-storage-test; printf home > /root/profile-storage-test; printf work > /workspace/profile-storage-test\n".toByteArray())
            await { File(a, "rootfs/var/lib/dpkg/profile-storage-test").exists() && File(a, "workspace/profile-storage-test").exists() }
            assertFalse(File(b, "rootfs/var/lib/dpkg/profile-storage-test").exists())
            assertFalse(File(b, "home/profile-storage-test").exists())
            assertFalse(File(b, "workspace/profile-storage-test").exists())
            store.delete(first.id)
            service.writeSessionInput(sessions[2],
                "cat /var/lib/dpkg/profile-storage-test /root/profile-storage-test /workspace/profile-storage-test > /root/shared-result\n".toByteArray())
            await { File(a, "home/shared-result").let { it.isFile && it.readText() == "ahomework" } }
            service.terminateSession(sessions[0])
            await { list().none { it.getString("id") == sessions[0] } }
            assertTrue(list().any { it.getString("id") == sessions[2] })
        } finally {
            sessions.forEach(service::terminateSession)
            listOf(first, second, shared).forEach { store.delete(it.id) }
            context.unbindService(connection)
        }
    }
}
