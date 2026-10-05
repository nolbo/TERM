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
                val requested = java.util.concurrent.CopyOnWriteArrayList<SessionLaunchSpec>()
                val runtimeField = MainActivity::class.java.getDeclaredField("runtime").apply { isAccessible = true }
                var originalRuntime: Any? = null
                val pending = profiles.first()
                fun field(name: String, value: Any) {
                    MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
                }
                val select = MainActivity::class.java.getDeclaredMethod("selectProfile", SessionProfile::class.java)
                    .apply { isAccessible = true }
                try {
                    val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
                    while (originalRuntime == null) {
                        scenario.onActivity { originalRuntime = runtimeField.get(it) }
                        check(android.os.SystemClock.elapsedRealtime() < deadline)
                        android.os.SystemClock.sleep(100)
                    }
                    scenario.onActivity {
                        activity = it
                        val runtime = Proxy.newProxyInstance(IRuntimeService::class.java.classLoader,
                            arrayOf(IRuntimeService::class.java)) { _, method, args ->
                            when (method.name) {
                                "getEnvironmentSoftwareJson" -> """{"installed":true,"available":true,"state":"READY"}"""
                                "createSessionFromSpec" -> { requested += SessionLaunchSpec.fromJson(args!![0] as String); null }
                                else -> method.invoke(originalRuntime, *(args ?: emptyArray()))
                            }
                        } as IRuntimeService
                        field("runtime", runtime)
                        field("rootlessLaunchSpec", store.resolve(pending))
                        field("rootlessInstallInFlight", true)
                    }
                    val others = profiles.drop(1)
                    others.forEachIndexed { index, profile ->
                        scenario.onActivity { select.invoke(it, profile) }
                        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
                        while (requested.size <= index) {
                            check(android.os.SystemClock.elapsedRealtime() < deadline)
                            android.os.SystemClock.sleep(100)
                        }
                    }
                    scenario.onActivity {
                        assertEquals(others.map { it.id }, requested.map { it.profileId })
                        assertEquals(false, state("rootlessVisible").value)
                        select.invoke(activity, pending.copy(id = UUID.randomUUID().toString()))
                        assertEquals(3, requested.size)
                        assertEquals(true, state("rootlessVisible").value)
                        val generation = MainActivity::class.java.getDeclaredField("bindingGeneration").apply { isAccessible = true }.getLong(activity)
                        val listener = MainActivity::class.java.getDeclaredMethod("createRuntimeListener", Long::class.javaPrimitiveType)
                            .apply { isAccessible = true }.invoke(activity, generation) as IRuntimeStateListener
                        listener.onRootlessProgress("ROOTLESS:${pending.storageId}", "Extracting", "pending detail")
                        listener.onRootlessProgress("ROOTLESS:${profiles[2].storageId}", "Downloading", "other detail")
                        assertEquals("Extracting", state("rootlessStatus").value)
                        assertEquals("pending detail", state("rootlessDetail").value)
                    }
                } finally {
                    scenario.onActivity {
                        runtimeField.set(it, originalRuntime)
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
