package dev.terminal.app

import androidx.compose.runtime.MutableState
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.ipc.IRuntimeStateListener
import dev.terminal.runtime.session.SessionLaunchSpec
import dev.terminal.runtime.session.SessionProfile
import dev.terminal.runtime.session.SessionRuntime
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

/** Exercises the Activity's real selection/listener handlers without downloading a distro. */
class RootlessConcurrentProfileTest {
    @Test fun installingProfileDoesNotInterceptOtherProfilesOrTheirProgress() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var activity: MainActivity
        fun state(name: String): MutableState<*> = MainActivity::class.java
            .getDeclaredField(name + "\$delegate").apply { isAccessible = true }.get(activity) as MutableState<*>
        instrumentation.runOnMainSync {
            activity = MainActivity()
            val pendingStorage = java.util.UUID.randomUUID().toString()
            val otherStorage = java.util.UUID.randomUUID().toString()
            val pending = SessionProfile("pending", "Installing", SessionRuntime.ROOTLESS,
                "/bin/bash", listOf("-l"), "/root", storageId = pendingStorage)
            val requested = mutableListOf<SessionLaunchSpec>()
            val runtime = Proxy.newProxyInstance(IRuntimeService::class.java.classLoader,
                arrayOf(IRuntimeService::class.java)) { _, method, args ->
                if (method.name == "createSessionFromSpec") requested += SessionLaunchSpec.fromJson(args!![0] as String)
                null
            } as IRuntimeService
            fun field(name: String, value: Any) {
                MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
            }
            field("runtime", runtime)
            field("rootlessLaunchSpec", pending.resolve())
            field("rootlessInstallInFlight", true)
            val select = MainActivity::class.java.getDeclaredMethod("selectProfile", SessionProfile::class.java)
                .apply { isAccessible = true }
            val others = listOf(
                pending.copy(id = "native", runtime = SessionRuntime.NATIVE, storageId = "legacy", shell = "/system/bin/sh"),
                pending.copy(id = "other-storage", storageId = otherStorage),
                pending.copy(id = "ubuntu", runtime = SessionRuntime.ROOTLESS_UBUNTU, storageId = java.util.UUID.randomUUID().toString()),
            )
            others.forEach { select.invoke(activity, it) }
            assertEquals(others.map { it.id }, requested.map { it.profileId })
            assertEquals(false, state("rootlessVisible").value)
            select.invoke(activity, pending.copy(id = "shared"))
            assertEquals(3, requested.size)
            assertEquals(true, state("rootlessVisible").value)
            val listener = MainActivity::class.java.getDeclaredMethod("createRuntimeListener", Long::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(activity, 0L) as IRuntimeStateListener
            listener.onRootlessProgress("ROOTLESS:$pendingStorage", "Extracting", "pending detail")
            listener.onRootlessProgress("ROOTLESS:$otherStorage", "Downloading", "other detail")
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals("Extracting", state("rootlessStatus").value)
            assertEquals("pending detail", state("rootlessDetail").value)
        }
    }
}
