package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Rect
import android.os.IBinder
import android.os.SystemClock
import android.view.KeyEvent
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.MutableState
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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

    @Test fun defaultSheetExpansionAndBothBackPathsShowIntermediateMotion() = withSessions { service, first, _ ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            select(scenario, first)
            scenario.onActivity { activity ->
                activity.getSystemService(InputMethodManager::class.java)
                    .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            }
            SystemClock.sleep(600)
            val root = composeRoot(scenario)
            val initial = pickerMotionFrame(scenario, root)
            val before = sessions(service).map { it.getString("id") }.toSet()
            val opening = capturePickerMotion(scenario, root) { click(R.string.new_session) }
            await("expanded picker") { findLabel(R.string.back) != null &&
                state<ToolbarSheetMode>(scenario, "toolbarSheetMode") == ToolbarSheetMode.Expanded }
            val expanded = pickerMotionFrame(scenario, root)
            val initialTop = requireNotNull(initial.handleTop)
            val expandedTop = requireNotNull(expanded.handleTop)
            val backLeft = requireNotNull(expanded.backLeft)
            assertTrue("Opening the default toolbar must animate its height", opening.any {
                it.handleTop != null && it.handleTop < initialTop - 2 && it.handleTop > expandedTop + 2
            })
            assertTrue("The profile page must slide into the sheet", opening.any {
                it.backLeft != null && it.backLeft > backLeft + 2
            })
            val profileHeight = requireNotNull(expanded.profileViewportHeight)
            val resizing = opening.filter { it.profileViewportHeight != null && it.handleTop != null &&
                it.handleTop < initialTop - 2 && it.handleTop > expandedTop + 2 }
            assertTrue("The profile list must be revealed during expansion", resizing.isNotEmpty())
            assertTrue("Height frames must keep the profile list at its final measured size",
                resizing.all { it.profileViewportHeight == profileHeight })

            val returning = capturePickerMotion(scenario, root) { click(R.string.back) }
            await("sessions after animated header back") { findLabel(R.string.select_profile) == null && findLabel(R.string.new_session) != null }
            assertTrue("Header back must slide the profile page out", returning.any {
                it.backLeft != null && it.backLeft > backLeft + 2
            })
            assertEquals(expandedTop, pickerMotionFrame(scenario, root).handleTop)

            capturePickerMotion(scenario, root) { click(R.string.new_session) }
            await("picker reopened") { findLabel(R.string.back) != null }
            val androidBack = capturePickerMotion(scenario, root) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK) }
            await("sessions after animated Android back") { findLabel(R.string.select_profile) == null && findLabel(R.string.new_session) != null }
            assertTrue("Android back must use the same page motion", androidBack.any {
                it.backLeft != null && it.backLeft > backLeft + 2
            })
            capturePickerMotion(scenario, root) { click(R.string.new_session) }
            capturePickerMotion(scenario, root) {
                val actionLabel = context.getString(R.string.toolbar_default)
                val handle = requireNotNull(find { node -> node.actionList.any { it.label?.toString() == actionLabel } })
                assertTrue(handle.performAction(handle.actionList.first { it.label?.toString() == actionLabel }.id))
            }
            await("picker at default height") { state<ToolbarSheetMode>(scenario, "toolbarSheetMode") == ToolbarSheetMode.Default }
            assertTrue("A settled default sheet must give the list its actual smaller viewport",
                requireNotNull(pickerMotionFrame(scenario, root).profileViewportHeight) < profileHeight)
            assertNotNull(findLabel(R.string.back))
            assertEquals(first, state<String?>(scenario, "activeSessionId"))
            assertEquals(before, sessions(service).map { it.getString("id") }.toSet())
        }
    }

    private data class PickerMotionFrame(val handleTop: Float?, val backLeft: Float?, val profileViewportHeight: Int?)

    private fun composeRoot(scenario: ActivityScenario<MainActivity>): ViewRootForTest {
        fun find(view: View): ViewRootForTest? {
            if (view is ViewRootForTest) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        var root: ViewRootForTest? = null
        scenario.onActivity { root = find(it.window.decorView) }
        return requireNotNull(root)
    }

    private fun pickerMotionFrame(root: ViewRootForTest): PickerMotionFrame {
        fun find(node: SemanticsNode, label: String): SemanticsNode? {
            if (node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true) return node
            return node.children.firstNotNullOfOrNull { find(it, label) }
        }
        val semantics = root.semanticsOwner.rootSemanticsNode
        var profileList = find(semantics, context.getString(R.string.select_profile))
        while (profileList != null && profileList.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) == null) {
            profileList = profileList.parent
        }
        return PickerMotionFrame(find(semantics, context.getString(R.string.toolbar_handle))?.boundsInWindow?.top,
            find(semantics, context.getString(R.string.back))?.boundsInWindow?.left, profileList?.size?.height)
    }

    private fun pickerMotionFrame(scenario: ActivityScenario<MainActivity>, root: ViewRootForTest): PickerMotionFrame {
        var frame: PickerMotionFrame? = null
        scenario.onActivity { frame = pickerMotionFrame(root) }
        return requireNotNull(frame)
    }

    private fun capturePickerMotion(scenario: ActivityScenario<MainActivity>, root: ViewRootForTest,
        action: () -> Unit): List<PickerMotionFrame> {
        val frames = mutableListOf<PickerMotionFrame>()
        // Collect before the click; accessibility calls may return after the animation has finished.
        val listener = ViewTreeObserver.OnPreDrawListener { frames += pickerMotionFrame(root); true }
        scenario.onActivity { root.view.viewTreeObserver.addOnPreDrawListener(listener) }
        try {
            action()
            SystemClock.sleep(1000)
            var result = emptyList<PickerMotionFrame>()
            scenario.onActivity { result = frames.toList() }
            return result
        } finally {
            scenario.onActivity { root.view.viewTreeObserver.removeOnPreDrawListener(listener) }
        }
    }

    @Test fun touchScrollingAndDiagonalSwipesKeepTheProfilePickerOpen() = withSessions { service, first, _ ->
        val store = SessionProfiles(context)
        val native = store.all().first { it.runtime == SessionRuntime.NATIVE }
        val token = UUID.randomUUID().toString()
        val profiles = (0..15).map { index ->
            native.copy(id = UUID.randomUUID().toString(), name = "Picker gesture $token $index")
                .also { store.save(it) }
        }
        try {
            val profileNames = store.all().mapTo(mutableSetOf()) { it.name }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                select(scenario, first)
                val before = sessions(service).map { it.getString("id") }.toSet()
                graphicsInfo("reset")
                click(R.string.new_session)
                await("profile picker header") { findLabel(R.string.back) != null }
                // Include the page fade in the same observation window on both implementations.
                SystemClock.sleep(600)
                android.util.Log.i("SessionProfilePickerTest", "pickerOpen " + graphicsInfo().lineSequence().map(String::trim).filter {
                    it.startsWith("Total frames rendered:") || it.startsWith("Janky frames:") ||
                        it.startsWith("50th percentile:") || it.startsWith("95th percentile:")
                }.joinToString("; "))
                assertEquals(ToolbarSheetMode.Expanded, state<ToolbarSheetMode>(scenario, "toolbarSheetMode"))
                val list = Rect().also { requireNotNull(verticalList()).getBoundsInScreen(it) }
                fun firstVisibleProfile() = find { node ->
                    node.text?.toString()?.lineSequence()?.any { it in profileNames } == true
                }?.text?.toString()
                val firstProfile = firstVisibleProfile()
                swipe(list, 0.5f, 0.8f, 0.5f, 0.2f)
                assertTrue("Vertical touch scrolling must keep the picker open", state<Boolean>(scenario, "sessionProfilePickerVisible"))
                assertNotNull("Picker header remains visible after scrolling", findLabel(R.string.back))
                assertNotEquals("The profile list must actually scroll", firstProfile,
                    firstVisibleProfile())
                swipe(list, 0.85f, 0.65f, 0.15f, 0.4f)
                assertTrue("A diagonal swipe must not navigate to another tool", state<Boolean>(scenario, "sessionProfilePickerVisible"))
                assertNotNull(findLabel(R.string.back))
                swipe(list, 0.15f, 0.4f, 0.85f, 0.65f)
                assertTrue(state<Boolean>(scenario, "sessionProfilePickerVisible"))
                assertEquals(first, state<String?>(scenario, "activeSessionId"))
                assertEquals(before, sessions(service).map { it.getString("id") }.toSet())
                click(R.string.back)
                await("Sessions after picker back") { findLabel(R.string.new_session) != null }
                swipe(list, 0.85f, 0.5f, 0.15f, 0.5f)
                await("normal tool swiping is restored") { findLabel(R.string.new_session) == null }
                assertEquals(ToolbarSheetMode.Expanded, state<ToolbarSheetMode>(scenario, "toolbarSheetMode"))
            }
        } finally { profiles.forEach { store.delete(it.id) } }
    }

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
                SystemClock.sleep(600)
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
        preferences.edit().putString(KEY_TOOLBAR, serializeToolbarConfig(ToolbarConfig(tools = listOf(SESSIONS_TOOL, HOTKEY_TOOL))))
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
            verticalList()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            false
        }
    }
    private fun verticalList() = find { node -> node.isScrollable && node.actionList.any {
        it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id ||
            it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
    } }
    private fun graphicsInfo(argument: String = "") =
        instrumentation.uiAutomation.executeShellCommand("dumpsys gfxinfo ${context.packageName} $argument").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
    private fun swipe(bounds: Rect, startX: Float, startY: Float, endX: Float, endY: Float) {
        val start = SystemClock.uptimeMillis()
        for (step in 0..20) {
            val fraction = step / 20f
            val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 20 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
                bounds.left + bounds.width() * (startX + (endX - startX) * fraction),
                bounds.top + bounds.height() * (startY + (endY - startY) * fraction), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            SystemClock.sleep(16)
        }
        instrumentation.waitForIdleSync()
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
