package dev.terminal.app

import androidx.compose.runtime.MutableState
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.ipc.IRuntimeStateListener
import dev.terminal.runtime.session.SessionLaunchSpec
import dev.terminal.runtime.session.SessionProfile
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.session.Environment
import dev.terminal.runtime.session.EnvironmentRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

/** Exercises the Activity's real selection/listener handlers without downloading a distro. */
class RootlessConcurrentProfileTest {
    @Test fun installingProfileDoesNotInterceptOtherProfilesOrTheirProgress() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = SessionProfiles(context)
        val environments = listOf(SessionRuntime.ROOTLESS, SessionRuntime.NATIVE, SessionRuntime.ROOTLESS, SessionRuntime.ROOTLESS_UBUNTU)
            .map { Environment.create(it, "Concurrent selection ${UUID.randomUUID()}") }
        val profiles = environments.map { environment ->
            store.save(SessionProfile(UUID.randomUUID().toString(), environment.name, environment.sessionRuntime,
                if (environment.sessionRuntime == SessionRuntime.NATIVE) "/system/bin/sh" else "/bin/bash"), environment)
        }
        lateinit var activity: MainActivity
        fun state(name: String): MutableState<*> = MainActivity::class.java
            .getDeclaredField(name + "\$delegate").apply { isAccessible = true }.get(activity) as MutableState<*>
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    activity = it
                    val pending = profiles.first()
                    val pendingStorage = pending.storageId
                    val otherStorage = profiles[2].storageId
                    val requested = mutableListOf<SessionLaunchSpec>()
                    val runtime = Proxy.newProxyInstance(IRuntimeService::class.java.classLoader,
                        arrayOf(IRuntimeService::class.java)) { _, method, args ->
                        if (method.name == "createSessionFromSpec") requested += SessionLaunchSpec.fromJson(args!![0] as String)
                        null
                    } as IRuntimeService
                    fun field(name: String, value: Any) {
                        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
                    }
                    val originalRuntime = MainActivity::class.java.getDeclaredField("runtime").apply { isAccessible = true }.get(activity)
                    try {
                        field("runtime", runtime)
                        field("rootlessLaunchSpec", store.resolve(pending))
                        field("rootlessInstallInFlight", true)
                        val select = MainActivity::class.java.getDeclaredMethod("selectProfile", SessionProfile::class.java)
                            .apply { isAccessible = true }
                        val others = profiles.drop(1)
                        others.forEach { select.invoke(activity, it) }
                        assertEquals(others.map { it.id }, requested.map { it.profileId })
                        assertEquals(false, state("rootlessVisible").value)
                        select.invoke(activity, pending.copy(id = UUID.randomUUID().toString()))
                        assertEquals(3, requested.size)
                        assertEquals(true, state("rootlessVisible").value)
                        val generation = MainActivity::class.java.getDeclaredField("bindingGeneration").apply { isAccessible = true }.getLong(activity)
                        val listener = MainActivity::class.java.getDeclaredMethod("createRuntimeListener", Long::class.javaPrimitiveType)
                            .apply { isAccessible = true }.invoke(activity, generation) as IRuntimeStateListener
                        listener.onRootlessProgress("ROOTLESS:$pendingStorage", "Extracting", "pending detail")
                        listener.onRootlessProgress("ROOTLESS:$otherStorage", "Downloading", "other detail")
                        assertEquals("Extracting", state("rootlessStatus").value)
                        assertEquals("pending detail", state("rootlessDetail").value)
                    } finally {
                        MainActivity::class.java.getDeclaredField("runtime").apply { isAccessible = true }.set(activity, originalRuntime)
                        field("rootlessInstallInFlight", false)
                        @Suppress("UNCHECKED_CAST")
                        (state("rootlessVisible") as MutableState<Boolean>).value = false
                    }
                }
            }
        } finally {
            profiles.forEach { store.delete(it.id) }
            EnvironmentRegistry(context.filesDir).transaction { environments.forEach { delete(it.id) } }
        }
    }
}
