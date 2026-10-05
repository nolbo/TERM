package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.MutableState
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import dev.terminal.runtime.session.SessionRuntime
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionProfilePickerTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun backNavigationStaysInTheSheetAndPreservesSessionActions() = withSessions { service, first, second ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            select(scenario, first)
            val window = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow).windowId
            val before = sessions(service).map { it.getString("id") }.toSet()
            click(R.string.new_session)
            await("profile picker") { findLabel(R.string.select_profile) != null }
            assertEquals("Picker must use the existing window", window,
                requireNotNull(instrumentation.uiAutomation.rootInActiveWindow).windowId)
            click(R.string.back)
            await("Sessions after header back") { findLabel(R.string.select_profile) == null && findLabel(R.string.new_session) != null }
            click(R.string.new_session)
            await("profile picker reopened") { findLabel(R.string.select_profile) != null }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("Sessions after Android back") { findLabel(R.string.select_profile) == null && findLabel(R.string.new_session) != null }
            assertEquals(before, sessions(service).map { it.getString("id") }.toSet())
            assertEquals(first, state<String?>(scenario, "activeSessionId"))
            click("Picker sibling")
            await("existing session selection") { state<String?>(scenario, "activeSessionId") == second }
            click(context.getString(R.string.terminate_session_description, "Picker sibling"))
            await("termination confirmation") { findLabel(R.string.terminate) != null }
            click(R.string.terminate)
            await("explicit session termination") { sessions(service).none { it.getString("id") == second } }
            assertTrue("Back and sibling termination preserve the original session", sessions(service).any { it.getString("id") == first })
        }
    }

    @Test fun longProfileListCreatesAndSelectsOneSessionThenClosesTheSheet() = withSessions { service, first, _ ->
        val store = SessionProfiles(context)
        val native = store.all().first { it.runtime == SessionRuntime.NATIVE }
        val token = UUID.randomUUID().toString()
        val profiles = (0..15).map { index ->
            native.copy(id = UUID.randomUUID().toString(), name = "Picker $token ${index.toString().padStart(2, '0')}", icon = "keyboard")
                .also { store.save(it) }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                select(scenario, first)
                click(R.string.new_session)
                await("profile picker") { findLabel(R.string.select_profile) != null }
                assertNull("The end of a long list starts outside the viewport", find { it.hasLabel(profiles.last().name) })
                scrollTo(profiles.last().name)
                val environmentName = store.environments().single { it.id == native.environmentId }.name
                val runtime = context.getString(R.string.runtime_native_title)
                assertNotNull(find { it.hasLabel(if (environmentName == runtime) runtime else "$environmentName · $runtime") })
                val before = sessions(service).map { it.getString("id") }.toSet()
                click(profiles.last().name)
                var created: JSONObject? = null
                await("exactly one new session") {
                    created = sessions(service).filter { it.getString("id") !in before }.singleOrNull()
                    created != null
                }
                val row = requireNotNull(created)
                assertEquals(profiles.last().id, row.getJSONObject("launchSpec").getString("profileId"))
                assertEquals("keyboard", row.getJSONObject("launchSpec").getString("icon"))
                await("new session selected and sheet hidden") {
                    state<String?>(scenario, "activeSessionId") == row.getString("id") &&
                        state<ToolbarSheetMode>(scenario, "toolbarSheetMode") == ToolbarSheetMode.Hidden &&
                        findLabel(R.string.new_session) == null && findLabel(R.string.select_profile) == null
                }
                assertTrue(sessions(service).any { it.getString("id") == first })
            }
        } finally { profiles.forEach { store.delete(it.id) } }
    }

    @Test fun secondaryActionOpensProfileEditorWithoutCreatingASession() = withSessions { service, first, _ ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            select(scenario, first)
            val before = sessions(service).map { it.getString("id") }.toSet()
            click(R.string.new_session)
            await("profile picker") { findLabel(R.string.select_profile) != null }
            scrollTo(context.getString(R.string.create_profile))
            click(R.string.create_profile)
            await("existing profile editor") { findLabel(R.string.profile_name) != null }
            assertEquals(before, sessions(service).map { it.getString("id") }.toSet())
            assertEquals(ToolbarSheetMode.Hidden, state<ToolbarSheetMode>(scenario, "toolbarSheetMode"))
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("terminal after editor back") { findLabel(R.string.toolbar_handle) != null }
            assertEquals(first, state<String?>(scenario, "activeSessionId"))
        }
    }

    private fun withSessions(test: (IRuntimeService, String, String) -> Unit) {
        val preferences = context.getSharedPreferences(SettingsDetailActivity.TERMINAL_PREFERENCES, Context.MODE_PRIVATE)
        val oldToolbar = preferences.getString(KEY_TOOLBAR, null)
        val oldSkipConfirmation = preferences.getBoolean(KEY_SKIP_SESSION_CLOSE_CONFIRMATION, false)
        preferences.edit().putString(KEY_TOOLBAR, serializeToolbarConfig(ToolbarConfig(tools = listOf(SESSIONS_TOOL))))
            .putBoolean(KEY_SKIP_SESSION_CLOSE_CONFIRMATION, false).commit()
        val queue = LinkedBlockingQueue<IRuntimeService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { queue.offer(IRuntimeService.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        val service = requireNotNull(queue.poll(15, TimeUnit.SECONDS))
        val previous = sessions(service).map { it.getString("id") }.toSet()
        try {
            service.startRuntime()
            await("Native runtime ready") { service.state == "READY" }
            fun create(title: String): String {
                val before = sessions(service).map { it.getString("id") }.toSet()
                service.createSession("NATIVE", "/system/bin/sh", "", 24, 80)
                var id: String? = null
                await("test session") { id = (sessions(service).map { it.getString("id") }.toSet() - before).singleOrNull(); id != null }
                return requireNotNull(id).also { service.renameSession(it, title) }
            }
            val first = create("Picker original")
            val second = create("Picker sibling")
            test(service, first, second)
        } finally {
            sessions(service).filter { it.getString("id") !in previous }.forEach { service.terminateSession(it.getString("id")) }
            context.unbindService(connection)
            preferences.edit().apply {
                if (oldToolbar == null) remove(KEY_TOOLBAR) else putString(KEY_TOOLBAR, oldToolbar)
                putBoolean(KEY_SKIP_SESSION_CLOSE_CONFIRMATION, oldSkipConfirmation)
            }.commit()
        }
    }

    private fun select(scenario: ActivityScenario<MainActivity>, id: String) {
        await("select test session") {
            scenario.onActivity {
                MainActivity::class.java.getDeclaredMethod("attach", String::class.java).apply { isAccessible = true }.invoke(it, id)
            }
            state<String?>(scenario, "activeSessionId") == id && findLabel(R.string.new_session) != null
        }
    }

    private fun <T> state(scenario: ActivityScenario<MainActivity>, name: String): T {
        var state: MutableState<T>? = null
        scenario.onActivity {
            @Suppress("UNCHECKED_CAST")
            state = MainActivity::class.java.getDeclaredField("${name}\$delegate").apply { isAccessible = true }.get(it) as MutableState<T>
        }
        return requireNotNull(state).value
    }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || !node.isVisibleToUser) return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        instrumentation.uiAutomation.clearCache()
        return walk(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun AccessibilityNodeInfo.hasLabel(label: String) =
        text?.toString()?.lineSequence()?.any { it == label } == true || contentDescription?.toString() == label
    private fun findLabel(label: Int) = find { it.hasLabel(context.getString(label)) }
    private fun click(label: Int) = click(context.getString(label))
    private fun click(label: String) = await("click $label") {
        var node = find { it.hasLabel(label) }
        while (node != null && !node.isClickable) node = node.parent
        node?.takeIf { it.isEnabled }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }
    private fun scrollTo(label: String) = await("scroll to $label") {
        if (find { it.hasLabel(label) } != null) true else {
            find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            false
        }
    }
    private fun sessions(service: IRuntimeService): List<JSONObject> = JSONArray(service.sessionsJson).let { array ->
        List(array.length()) { array.getJSONObject(it) }
    }
    private fun await(description: String, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (!check()) {
            assertTrue(description, SystemClock.uptimeMillis() < deadline)
            SystemClock.sleep(100)
        }
    }
}
