package dev.terminal.app

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.MutableState
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.RuntimeChoiceUi
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.session.SessionLaunchSpec
import dev.terminal.runtime.session.SessionProfile
import dev.terminal.runtime.session.SessionRuntime
import java.lang.reflect.Proxy
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real Activity selection and IPC without installing or booting a VM. */
@RunWith(AndroidJUnit4::class)
class AvfProfileLaunchTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val runtimeField = MainActivity::class.java.getDeclaredField("runtime").apply { isAccessible = true }
    private val selectProfile = MainActivity::class.java.getDeclaredMethod("selectProfile", SessionProfile::class.java)
        .apply { isAccessible = true }

    private fun awaitService(scenario: ActivityScenario<MainActivity>) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            scenario.onActivity { ready = runtimeField.get(it) != null }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail("Runtime service did not connect")
    }

    @Test fun legacyAndIndependentAvfProfilesSendStartupEvenWithNativeAsDefault() {
        val requested = mutableListOf<SessionLaunchSpec>()
        val proxy = Proxy.newProxyInstance(IRuntimeService::class.java.classLoader,
            arrayOf(IRuntimeService::class.java)) { _, method, args ->
            if (method.name == "createSessionFromSpec") requested += SessionLaunchSpec.fromJson(args!![0] as String)
            null
        } as IRuntimeService
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitService(scenario)
            scenario.onActivity { activity ->
                val original = runtimeField.get(activity)
                try {
                    runtimeField.set(activity, proxy)
                    @Suppress("UNCHECKED_CAST")
                    val choices = MainActivity::class.java.getDeclaredField("runtimeChoices\$delegate")
                        .apply { isAccessible = true }.get(activity) as MutableState<List<RuntimeChoiceUi>>
                    choices.value = listOf(RuntimeChoiceUi("NATIVE", true, "ready"), RuntimeChoiceUi("AVF", false, "not booted"))
                    val legacy = SessionProfiles(activity).builtIns().first { it.runtime == SessionRuntime.AVF }
                    val independent = legacy.copy(id = "avf-independent", storageId = UUID.randomUUID().toString())
                    selectProfile.invoke(activity, legacy)
                    selectProfile.invoke(activity, independent)
                    assertEquals(listOf(legacy.resolve(), independent.resolve()), requested)
                    @Suppress("UNCHECKED_CAST")
                    val unavailable = MainActivity::class.java.getDeclaredField("unavailableChoice\$delegate")
                        .apply { isAccessible = true }.get(activity) as MutableState<RuntimeChoiceUi?>
                    assertNull("The default AVF profile must not redirect to settings", unavailable.value)
                } finally {
                    runtimeField.set(activity, original)
                }
            }
        }
    }

    @Test fun unsupportedAvfLaunchShowsTheActualServiceErrorInADialog() {
        val context = instrumentation.targetContext
        assumeFalse(context.packageManager.hasSystemFeature("android.software.virtualization_framework"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitService(scenario)
            scenario.onActivity { activity ->
                selectProfile.invoke(activity, SessionProfile("avf-error", "AVF", SessionRuntime.AVF,
                    "/bin/bash", emptyList(), "/home/user"))
            }
            val title = context.getString(R.string.avf_session_start_failed)
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < deadline) {
                instrumentation.waitForIdleSync()
                instrumentation.uiAutomation.clearCache()
                if (find(instrumentation.uiAutomation.rootInActiveWindow, title) != null) break
                SystemClock.sleep(50)
            }
            assertNotNull("AVF failure must remain visible", find(instrumentation.uiAutomation.rootInActiveWindow, title))
            assertNotNull("The service's AVF permission failure must be shown",
                find(instrumentation.uiAutomation.rootInActiveWindow, "AVF requires privileged custom VM access"))
            if (InstrumentationRegistry.getArguments().getString("preview") == "true") SystemClock.sleep(20_000)
        }
    }

    private fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && node.text?.toString() == text) return node
        for (index in 0 until node.childCount) find(node.getChild(index), text)?.let { return it }
        return null
    }
}
