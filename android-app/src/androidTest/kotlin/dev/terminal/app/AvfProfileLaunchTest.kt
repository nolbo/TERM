package dev.terminal.app

import android.os.SystemClock
import android.app.UiAutomation
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.MutableState
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.RuntimeChoiceUi
import dev.terminal.app.ui.SessionUi
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.ipc.IRuntimeStateListener
import dev.terminal.runtime.session.SessionLaunchSpec
import dev.terminal.runtime.session.SessionProfile
import dev.terminal.runtime.session.SessionRuntime
import java.lang.reflect.Proxy
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject

/** Exercises real Activity selection and IPC without installing or booting a VM. */
@RunWith(AndroidJUnit4::class)
class AvfProfileLaunchTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
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
        val requestIds = mutableListOf<String>()
        var creatingActivity: MainActivity? = null
        val proxy = Proxy.newProxyInstance(IRuntimeService::class.java.classLoader,
            arrayOf(IRuntimeService::class.java)) { _, method, args ->
            if (method.name == "createSessionFromSpecWithRequestId") {
                requested += SessionLaunchSpec.fromJson(args!![0] as String)
                assertEquals("AVF profiles must start in droid's home", "/home/droid", requested.last().workingDirectory)
                requestIds += args[1] as String
                val activity = checkNotNull(creatingActivity)
                val tabId = "avf-launch:${requestIds.last()}"
                assertEquals("The loading tab must be selected before dispatch", tabId, state<String?>(activity, "activeSessionId").value)
                assertTrue(state<List<SessionUi>>(activity, "sessions").value.single { it.id == tabId }.pendingLaunch)
                val renderers = MainActivity::class.java.getDeclaredMethod("getTerminalSessions")
                    .apply { isAccessible = true }.invoke(activity) as Map<*, *>
                assertFalse("A loading request must not create a terminal renderer", renderers.containsKey(tabId))
            }
            null
        } as IRuntimeService
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitService(scenario)
            scenario.onActivity { activity ->
                val original = runtimeField.get(activity)
                try {
                    creatingActivity = activity
                    runtimeField.set(activity, proxy)
                    @Suppress("UNCHECKED_CAST")
                    val choices = MainActivity::class.java.getDeclaredField("runtimeChoices\$delegate")
                        .apply { isAccessible = true }.get(activity) as MutableState<List<RuntimeChoiceUi>>
                    choices.value = listOf(RuntimeChoiceUi("NATIVE", true, "ready"), RuntimeChoiceUi("AVF", false, "not booted"))
                    val legacy = SessionProfiles(activity).builtIns().first { it.runtime == SessionRuntime.AVF }
                    val independent = legacy.copy(id = "avf-independent", storageId = UUID.randomUUID().toString())
                    runtimeField.set(activity, null)
                    selectProfile.invoke(activity, legacy)
                    assertTrue("A loading tab must open while the service connects", state<List<SessionUi>>(activity, "sessions").value.last().pendingLaunch)
                    assertTrue(requested.isEmpty())
                    runtimeField.set(activity, proxy)
                    val submit = MainActivity::class.java.getDeclaredMethod("submitPendingAvfLaunches").apply { isAccessible = true }
                    submit.invoke(activity)
                    submit.invoke(activity)
                    selectProfile.invoke(activity, independent)
                    assertEquals(listOf(legacy.resolve(), independent.resolve()), requested)
                    @Suppress("UNCHECKED_CAST")
                    val unavailable = MainActivity::class.java.getDeclaredField("unavailableChoice\$delegate")
                        .apply { isAccessible = true }.get(activity) as MutableState<RuntimeChoiceUi?>
                    assertNull("The default AVF profile must not redirect to settings", unavailable.value)
                    assertEquals(2, requestIds.distinct().size)
                } finally {
                    runtimeField.set(activity, original)
                }
            }
            val loadingDescription = instrumentation.targetContext.getString(R.string.avf_session_loading)
            awaitText(loadingDescription, contentDescription = true)
            assertNull("Loading must not draw a caption", find(automation.rootInActiveWindow, loadingDescription))
            if (InstrumentationRegistry.getArguments().getString("preview") == "true") SystemClock.sleep(30_000)
            scenario.onActivity { activity ->
                val original = runtimeField.get(activity) as IRuntimeService
                try {
                    runtimeField.set(activity, proxy)
                    val first = state<List<SessionUi>>(activity, "sessions").value.single { it.launchRequestId == requestIds.first() }
                    MainActivity::class.java.getDeclaredMethod("attach", String::class.java)
                        .apply { isAccessible = true }.invoke(activity, first.id)
                    val snapshot = JSONArray(original.sessionsJson)
                        .put(JSONObject().put("id", first.id).put("runtime", "AVF").put("title", first.title)
                            .put("state", "PREPARING").put("pendingLaunch", true).put("launchRequestId", requestIds.first()))
                        .put(JSONObject().put("id", "avf-ui-ready").put("runtime", "AVF").put("title", "Ready VM")
                            .put("state", "RUNNING").put("launchRequestId", requestIds.last()))
                    render(activity, snapshot.toString())
                    val tabs = state<List<SessionUi>>(activity, "sessions").value
                    assertFalse(tabs.any { it.id == "avf-launch:${requestIds.last()}" })
                    assertTrue(tabs.any { it.id == "avf-ui-ready" && !it.pendingLaunch })
                    val listener = MainActivity::class.java.getDeclaredField("listener").apply { isAccessible = true }
                        .get(activity) as IRuntimeStateListener
                    listener.onSessionFocusRequested("avf-ui-ready")
                    assertEquals("Completion must keep the user's selected tab", first.id, state<String?>(activity, "activeSessionId").value)
                } finally {
                    runtimeField.set(activity, original)
                    render(activity, original.sessionsJson)
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
                    "/bin/bash", emptyList(), "/home/droid"))
            }
            val title = context.getString(R.string.avf_session_start_failed)
            awaitText(title)
            assertNotNull("The service's AVF permission failure must be shown",
                find(automation.rootInActiveWindow, "AVF requires privileged custom VM access"))
            scenario.onActivity { activity ->
                val tab = state<List<SessionUi>>(activity, "sessions").value.single { it.id == state<String?>(activity, "activeSessionId").value }
                assertTrue(tab.pendingLaunch)
                assertEquals("LAUNCH_FAILED", tab.state)
                assertEquals("AVF requires privileged custom VM access", tab.launchError)
                state<String?>(activity, "avfLaunchFailure").value = null
            }
            awaitText(title)
            assertNotNull("The failed tab must retain the error after dismissing the dialog",
                find(automation.rootInActiveWindow, "AVF requires privileged custom VM access"))
            if (InstrumentationRegistry.getArguments().getString("preview") == "true") SystemClock.sleep(20_000)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> state(activity: MainActivity, name: String): MutableState<T> =
        MainActivity::class.java.getDeclaredField("$name\$delegate").apply { isAccessible = true }.get(activity) as MutableState<T>

    private fun render(activity: MainActivity, json: String) {
        val revision = MainActivity::class.java.getDeclaredField("lastSessionRevision").apply { isAccessible = true }.getLong(activity)
        MainActivity::class.java.getDeclaredMethod("renderSessions", Long::class.javaPrimitiveType, String::class.java)
            .apply { isAccessible = true }.invoke(activity, revision + 1, json)
    }

    private fun awaitText(text: String, contentDescription: Boolean = false) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            automation.clearCache()
            if (find(automation.rootInActiveWindow, text, contentDescription) != null) return
            SystemClock.sleep(50)
        }
        fail("Missing visible text: $text")
    }

    private fun find(node: AccessibilityNodeInfo?, text: String, contentDescription: Boolean = false): AccessibilityNodeInfo? {
        if (node == null) return null
        val label = if (contentDescription) node.contentDescription else node.text
        if (node.isVisibleToUser && label?.toString() == text) return node
        for (index in 0 until node.childCount) find(node.getChild(index), text, contentDescription)?.let { return it }
        return null
    }
}
