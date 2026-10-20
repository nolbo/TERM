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
import dev.terminal.app.ui.TerminalPaneState
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

    @Test fun installedLegacyAndIndependentAvfProfilesSendStartupEvenWithNativeAsDefault() {
        val requested = java.util.concurrent.CopyOnWriteArrayList<SessionLaunchSpec>()
        val requestIds = java.util.concurrent.CopyOnWriteArrayList<String>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitService(scenario)
            lateinit var original: IRuntimeService
            lateinit var legacy: SessionProfile
            lateinit var independent: SessionProfile
            scenario.onActivity { activity ->
                original = runtimeField.get(activity) as IRuntimeService
                legacy = SessionProfiles(activity).builtIns().first { it.runtime == SessionRuntime.AVF }
                independent = legacy.copy(id = "avf-independent", storageId = UUID.randomUUID().toString())
                val proxy = Proxy.newProxyInstance(IRuntimeService::class.java.classLoader,
                    arrayOf(IRuntimeService::class.java)) { _, method, args ->
                    when (method.name) {
                        "getEnvironmentSoftwareJson" -> """{"installed":true,"busy":false}"""
                        "createSessionFromSpecWithRequestId", "createSessionInWorkspace" -> {
                            requested += SessionLaunchSpec.fromJson(args!![0] as String)
                            requestIds += args[1] as String
                            assertEquals("/home/droid", requested.last().workingDirectory)
                            val tabId = "avf-launch:${requestIds.last()}"
                            assertEquals(tabId, activeSessionId(activity))
                            assertTrue(state<List<SessionUi>>(activity, "sessions").value.single { it.id == tabId }.pendingLaunch)
                            null
                        }
                        else -> method.invoke(original, *(args ?: emptyArray()))
                    }
                } as IRuntimeService
                runtimeField.set(activity, proxy)
                selectProfile.invoke(activity, legacy)
            }
            try {
                awaitRequests(requested, 1)
                scenario.onActivity { activity ->
                    val submit = MainActivity::class.java.getDeclaredMethod("submitPendingAvfLaunches").apply { isAccessible = true }
                    submit.invoke(activity)
                    submit.invoke(activity)
                    selectProfile.invoke(activity, independent)
                }
                awaitRequests(requested, 2)
                assertEquals(listOf(legacy.id, independent.id), requested.map { it.profileId })
                assertTrue(requested.all { it.executionEnvironment != null && it.environmentId != null })
                assertEquals(2, requestIds.distinct().size)
                val loadingDescription = instrumentation.targetContext.getString(R.string.avf_session_loading)
                awaitText(loadingDescription, contentDescription = true)
                assertNull("Loading must not draw a caption", find(automation.rootInActiveWindow, loadingDescription))
                scenario.onActivity { activity ->
                    assertNull(state<RuntimeChoiceUi?>(activity, "unavailableChoice").value)
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
                    assertEquals("Completion must keep the user's selected tab", first.id, activeSessionId(activity))
                }
            } finally {
                scenario.onActivity { activity -> runtimeField.set(activity, original); render(activity, original.sessionsJson) }
            }
        }
    }

    private fun awaitRequests(requests: List<SessionLaunchSpec>, size: Int) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (requests.size < size) {
            check(SystemClock.elapsedRealtime() < deadline) { "Profile launch timed out" }
            SystemClock.sleep(100)
        }
    }

    @Test fun unsupportedAvfLaunchShowsTheActualServiceErrorInADialog() {
        val context = instrumentation.targetContext
        assumeFalse(context.packageManager.hasSystemFeature("android.software.virtualization_framework"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitService(scenario)
            scenario.onActivity { activity ->
                state<String?>(activity, "avfLaunchFailure").value = null
                selectProfile.invoke(activity, SessionProfile("avf-error", "AVF", SessionRuntime.AVF,
                    "/bin/bash", emptyList(), "/home/droid"))
            }
            awaitText(context.getString(R.string.profile_installation_title))
            var installButton = find(automation.rootInActiveWindow, context.getString(R.string.install))
            while (installButton != null && !installButton.isClickable) installButton = installButton.parent
            assertTrue(installButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            val title = context.getString(R.string.avf_session_start_failed)
            awaitText(title)
            assertNotNull("The service's AVF permission failure must be shown",
                find(automation.rootInActiveWindow, "AVF requires privileged custom VM access"))
            scenario.onActivity { activity ->
                val tab = state<List<SessionUi>>(activity, "sessions").value.single { it.id == activeSessionId(activity) }
                assertTrue(tab.pendingLaunch)
                assertEquals("LAUNCH_FAILED", tab.state)
                assertEquals("AVF requires privileged custom VM access", tab.launchError)
                assertNull("AVF errors must not enqueue a snackbar", state<String?>(activity, "feedbackMessage").value)
                assertFalse(state<String>(activity, "runtimeMessage").value.contains(tab.launchError!!))
            }
            scenario.recreate()
            awaitService(scenario)
            awaitText(title)
            assertNotNull("An open error dialog must survive recreation",
                find(automation.rootInActiveWindow, "AVF requires privileged custom VM access"))
            if (InstrumentationRegistry.getArguments().getString("preview") == "true") SystemClock.sleep(20_000)
            if (InstrumentationRegistry.getArguments().getString("externalDismiss") != "true") closeDialog()
            awaitGone("AVF requires privileged custom VM access")
            awaitGone(title)
            assertNull(find(automation.rootInActiveWindow, context.getString(R.string.avf_session_loading), contentDescription = true))
            scenario.recreate()
            awaitService(scenario)
            instrumentation.waitForIdleSync()
            automation.clearCache()
            assertNull("Dismissed errors must not reopen from owner snapshot replay",
                find(automation.rootInActiveWindow, "AVF requires privileged custom VM access"))
            scenario.onActivity { activity ->
                assertNull(state<String?>(activity, "avfLaunchFailure").value)
                assertNull(state<String?>(activity, "feedbackMessage").value)
            }
        }
    }

    @Test fun ownerFailureAndRepeatedCallbacksShareOnlyOneDismissibleDialog() {
        val requestId = UUID.randomUUID().toString()
        val message = "Fixture AVF startup failure $requestId"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitService(scenario)
            lateinit var original: IRuntimeService
            lateinit var snapshot: String
            lateinit var listener: IRuntimeStateListener
            scenario.onActivity { activity ->
                original = runtimeField.get(activity) as IRuntimeService
                snapshot = JSONArray(original.sessionsJson).put(JSONObject()
                    .put("id", "avf-launch:$requestId").put("runtime", "AVF").put("title", "Fixture VM")
                    .put("state", "LAUNCH_FAILED").put("pendingLaunch", true)
                    .put("launchRequestId", requestId).put("launchError", message)).toString()
                listener = MainActivity::class.java.getDeclaredField("listener").apply { isAccessible = true }
                    .get(activity) as IRuntimeStateListener
                state<String?>(activity, "feedbackMessage").value = null
                render(activity, snapshot)
                listener.onOperationError("세션 생성:AVF:legacy:$requestId", message)
                assertNull(state<String?>(activity, "feedbackMessage").value)
                assertFalse(state<String>(activity, "runtimeMessage").value.contains(message))
            }
            try {
                awaitText(message)
                closeDialog()
                awaitGone(message)
                scenario.onActivity { activity ->
                    render(activity, snapshot)
                    listener.onOperationError("세션 생성:AVF:legacy:$requestId", message)
                    listener.onOperationError("세션 생성:AVF:legacy", message)
                    assertNull(state<String?>(activity, "avfLaunchFailure").value)
                    assertNull(state<String?>(activity, "feedbackMessage").value)
                    // Other runtimes retain their existing error feedback.
                    listener.onOperationError("세션 생성:NATIVE:legacy", "Fixture native failure")
                    assertTrue(state<String?>(activity, "feedbackMessage").value!!.contains("Fixture native failure"))
                    assertNull(state<String?>(activity, "avfLaunchFailure").value)
                }
            } finally {
                scenario.onActivity { activity -> render(activity, original.sessionsJson) }
            }
        }
    }

    private fun activeSessionId(activity: MainActivity) = state<TerminalPaneState>(activity, "paneState").value.focusedSessionId

    private fun closeDialog() {
        var close = find(automation.rootInActiveWindow, instrumentation.targetContext.getString(R.string.close))
        while (close != null && !close.isClickable) close = close.parent
        assertTrue(close?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    private fun awaitGone(text: String) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            automation.clearCache()
            if (find(automation.rootInActiveWindow, text) == null) return
            SystemClock.sleep(50)
        }
        fail("Error is still visible outside the dismissed dialog: $text")
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
