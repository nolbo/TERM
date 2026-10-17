package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.KEY_TERMINAL_LAYOUTS
import dev.terminal.app.ui.SplitDirection
import dev.terminal.app.ui.TerminalPaneState
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.workspace.Workspace
import dev.terminal.runtime.workspace.WorkspaceLaunch
import dev.terminal.runtime.workspace.WorkspaceRegistry
import dev.terminal.view.TerminalView
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SplitTerminalDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        var satisfied = condition()
        while (!satisfied && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(50)
            satisfied = condition()
        }
        assertTrue(message, satisfied)
    }
    private fun paneState(activity: MainActivity) = MainActivity::class.java.getDeclaredMethod("getPaneState")
        .apply { isAccessible = true }.invoke(activity) as TerminalPaneState
    private fun host(activity: MainActivity) = MainActivity::class.java.getDeclaredField("terminalPaneLayout")
        .apply { isAccessible = true }.get(activity) as? TerminalPaneLayout
    private fun terminalViews(activity: MainActivity) = (0..1).map { requireNotNull(host(activity)?.terminal(it)) }
    private fun assertSameTerminalViews(activity: MainActivity, expected: List<TerminalView>) {
        expected.forEachIndexed { index, view -> assertSame("Pane $index keeps its TerminalView", view, host(activity)?.terminal(index)) }
    }
    private fun call(activity: MainActivity, method: String, id: String) = MainActivity::class.java
        .getDeclaredMethod(method, String::class.java).apply { isAccessible = true }.invoke(activity, id)
    private fun check(scenario: ActivityScenario<MainActivity>, message: String, condition: (MainActivity) -> Boolean) {
        try { await(message) { var result = false; scenario.onActivity { result = condition(it) }; result } }
        catch (failure: AssertionError) {
            var details = ""
            scenario.onActivity { activity ->
                details = "focus=${paneState(activity).focus}, panes=" + (0..1).map { index ->
                    host(activity)?.terminal(index)?.let { view ->
                        val rect = android.graphics.Rect(); view.getGlobalVisibleRect(rect)
                        "${view.width}x${view.height}:$rect"
                    }
                }
            }
            throw AssertionError("$message ($details)", failure)
        }
    }
    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.clearCache()
        fun visit(value: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (value == null) return null
            if (value.isVisibleToUser && predicate(value)) return value
            for (index in 0 until value.childCount) visit(value.getChild(index))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun tabNode(title: String) = node { it.text?.toString() == title }
        ?: node { it.contentDescription?.toString() == title }
    private data class TabMotionBounds(val id: Int, val bounds: androidx.compose.ui.geometry.Rect)
    private fun composeRoot(scenario: ActivityScenario<MainActivity>): ViewRootForTest {
        fun find(view: View): ViewRootForTest? {
            if (view is ViewRootForTest) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        var root: ViewRootForTest? = null
        scenario.onActivity { root = find(it.window.decorView) }
        return requireNotNull(root)
    }
    private fun tabMotionFrame(root: ViewRootForTest, titles: Set<String>): Map<String, TabMotionBounds> {
        val result = mutableMapOf<String, TabMotionBounds>()
        fun visit(value: SemanticsNode) {
            val title = value.config.getOrNull(SemanticsProperties.Text)?.firstOrNull { it.text in titles }?.text
                ?: value.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull { it in titles }
            if (title != null) {
                var tab: SemanticsNode? = value
                while (tab != null && tab.config.getOrNull(SemanticsProperties.StateDescription) == null) tab = tab.parent
                tab?.let { result[title] = TabMotionBounds(it.id, it.boundsInRoot) }
            }
            value.children.forEach(::visit)
        }
        visit(root.semanticsOwner.rootSemanticsNode)
        return result
    }
    private fun actionNode(text: String, action: Int): AccessibilityNodeInfo {
        var target: AccessibilityNodeInfo? = null
        await("Action $action for $text") {
            target = tabNode(text)
            while (target != null && target!!.actionList.none { it.id == action }) target = target!!.parent
            target != null
        }
        return requireNotNull(target)
    }
    private fun textAction(text: String, action: Int) {
        assertTrue(actionNode(text, action).performAction(action))
    }
    private fun expandToolbar(scenario: ActivityScenario<MainActivity>) {
        var handle: AccessibilityNodeInfo? = null
        var label = ""
        scenario.onActivity { label = it.getString(R.string.toolbar_expand) }
        await("Toolbar expand action") { handle = node { it.actionList.any { a -> a.label?.toString() == label } }; handle != null }
        assertTrue(handle!!.performAction(handle!!.actionList.first { it.label?.toString() == label }.id))
    }
    private fun menu(scenario: ActivityScenario<MainActivity>, title: String, action: Int) {
        var label = ""
        scenario.onActivity { label = it.getString(action) }
        textAction(title, AccessibilityNodeInfo.ACTION_LONG_CLICK)
        if (action in listOf(R.string.terminal_split_side_by_side, R.string.terminal_split_stacked,
                R.string.terminal_unsplit)) assertSplitControlRow(scenario)
        textAction(label, AccessibilityNodeInfo.ACTION_CLICK)
    }
    private fun assertSplitControlRow(scenario: ActivityScenario<MainActivity>) {
        var openLabel = ""
        var labels = emptyList<String>()
        var direction = SplitDirection.Stacked
        scenario.onActivity {
            openLabel = it.getString(R.string.terminal_open_split)
            labels = listOf(R.string.terminal_split_side_by_side, R.string.terminal_split_stacked,
                R.string.terminal_unsplit).map(it::getString)
            direction = paneState(it).direction
        }
        assertNull("Split menus omit the open-split command", node {
            it.text?.toString() == openLabel || it.contentDescription?.toString() == openLabel
        })
        var buttons = emptyList<AccessibilityNodeInfo>()
        await("Split controls settle to 48dp buttons in one icon row") {
            buttons = labels.map { actionNode(it, AccessibilityNodeInfo.ACTION_CLICK) }
            val bounds = buttons.map { button -> android.graphics.Rect().also(button::getBoundsInScreen) }
            bounds.all { !it.isEmpty && kotlin.math.abs(it.width() - 48 * context.resources.displayMetrics.density) <= 2 } &&
                bounds.maxOf { it.centerY() } - bounds.minOf { it.centerY() } <= 2
        }
        listOf(SplitDirection.SideBySide, SplitDirection.Stacked).forEachIndexed { index, value ->
            assertEquals("The current split direction is selected", direction == value,
                buttons[index].isChecked || buttons[index].isSelected)
        }
    }

    private fun tabBounds(title: String, requireEnabled: Boolean = true): android.graphics.Rect {
        var target: AccessibilityNodeInfo? = null
        var bounds = android.graphics.Rect()
        var stable = 0
        await("Tab body for $title") {
            target = tabNode(title)
            // Disabled drag targets expose the tab's state, but no click action.
            while (target != null && if (requireEnabled)
                target!!.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK }
                else target!!.stateDescription == null) target = target!!.parent
            val next = android.graphics.Rect().also { target?.getBoundsInScreen(it) }
            stable = if (next == bounds && !next.isEmpty) stable + 1 else 0
            bounds = next
            target != null && (!requireEnabled || target!!.isEnabled) && stable >= 2
        }
        return bounds
    }

    private fun horizontalTabScroller(title: String): AccessibilityNodeInfo {
        var target: AccessibilityNodeInfo? = null
        await("Horizontal tab scroller for $title") {
            target = tabNode(title)
            while (target != null && !target!!.isScrollable) target = target!!.parent
            target != null && target!!.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }
        }
        return requireNotNull(target)
    }

    private inner class TabDrag(source: String, target: String, private val leftBody: Boolean = false) : AutoCloseable {
        val from = tabBounds(source)
        val to = tabBounds(target)
        private val down = SystemClock.uptimeMillis()
        private val startX = if (leftBody) from.left + from.width() * 0.15f else from.centerX().toFloat()
        private var x = startX
        private var y = from.centerY().toFloat()
        private var pressed = true
        private fun event(action: Int, x: Float, y: Float) {
            val value = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            value.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(value, true)) } finally { value.recycle() }
        }
        init {
            android.util.Log.i("SessionTabDragTest", "start $source $from -> $target $to")
            event(android.view.MotionEvent.ACTION_DOWN, x, y)
            SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout() + 250L)
        }
        fun moveTo(endX: Float, endY: Float, steps: Int = 12, stepDelayMs: Long = 16) {
            val startX = x; val startY = y
            for (step in 1..steps) {
                val fraction = step / steps.toFloat()
                x = startX + (endX - startX) * fraction; y = startY + (endY - startY) * fraction
                event(android.view.MotionEvent.ACTION_MOVE, x, y)
                SystemClock.sleep(stepDelayMs)
            }
        }
        fun toTarget() = moveTo(if (leftBody) startX else to.centerX().toFloat(), to.centerY().toFloat())
        fun targetHeight(fraction: Float) = moveTo(x, to.top + to.height() * fraction, steps = 2, stepDelayMs = 8)
        fun side(first: Boolean) = moveTo(context.resources.displayMetrics.widthPixels * if (first) 0.25f else 0.75f, to.centerY().toFloat())
        fun release() { event(android.view.MotionEvent.ACTION_UP, x, y); pressed = false }
        override fun close() { if (pressed) event(android.view.MotionEvent.ACTION_CANCEL, x, y) }
    }

    private inner class Fixture(initialTitles: List<String> = listOf("A", "B", "C", "D")) : AutoCloseable {
        lateinit var runtime: IRuntimeService
        val firstWorkspace = Workspace(UUID.randomUUID().toString(), "Split test ${UUID.randomUUID().toString().take(8)}", false)
        val otherWorkspace = Workspace(UUID.randomUUID().toString(), "Other test ${UUID.randomUUID().toString().take(8)}", false)
        val ids = linkedMapOf<String, String>()
        private val originalUi = context.getSharedPreferences("terminal-ui-state", Context.MODE_PRIVATE).all
        private var previousWorkspace = WorkspaceRegistry.UNASSIGNED
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                runtime = IRuntimeService.Stub.asInterface(binder); ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        private val ready = CountDownLatch(1)
        fun rows() = JSONArray(runtime.sessionsJson).let { array -> List(array.length()) { array.getJSONObject(it) } }
        init {
            assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            val cleanup = InstrumentationRegistry.getArguments().getString("split.cleanupWorkspaces")
                ?.split(',').orEmpty().map { UUID.fromString(it).toString() }.toSet()
            if (cleanup.isNotEmpty()) {
                val old = WorkspaceRegistry.State.parse(runtime.workspacesJson)
                val owned = old.memberships.filterValues { it in cleanup }.keys
                owned.forEach { runtime.terminateSession(it) }
                await("Previous fixture PTYs terminate") { rows().none { it.getString("id") in owned } }
                old.workspaces.filter { it.id in cleanup }.forEach { assertEquals("", runtime.deleteWorkspace(it.id)) }
            }
            previousWorkspace = WorkspaceRegistry.State.parse(runtime.workspacesJson).selected
            try {
            val profiles = SessionProfiles(context)
            val profile = profiles.all().first { it.runtime == SessionRuntime.NATIVE }
            assertEquals("", runtime.saveWorkspace(firstWorkspace.json().toString()))
            assertEquals("", runtime.saveWorkspace(otherWorkspace.json().toString()))
            val requests = initialTitles.associateWith { UUID.randomUUID().toString() }
            requests.forEach { (title, request) ->
                val workspace = if (title == "D") otherWorkspace.id else firstWorkspace.id
                val spec = WorkspaceLaunch(UUID.randomUUID().toString(), title, profile.id).resolve(profile, profiles::resolve)
                runtime.createSessionInWorkspace(spec.toJson().toString(), request, workspace, false, 24, 80)
            }
            await("Fixture PTYs are running") {
                val memberships = WorkspaceRegistry.State.parse(runtime.workspacesJson).memberships
                rows().filter { memberships[it.getString("id")] in setOf(firstWorkspace.id, otherWorkspace.id) && it.optString("state") == "RUNNING" }
                    .forEach { row -> ids[row.getString("title")] = row.getString("id") }
                ids.size == initialTitles.size
            }
            assertEquals("", runtime.selectWorkspace(firstWorkspace.id))
            } catch (failure: Throwable) { close(); throw failure }
        }
        fun id(title: String) = ids.getValue(title)
        fun assertLive() = assertTrue(rows().map { it.getString("id") }.containsAll(ids.values))
        override fun close() {
            val state = WorkspaceRegistry.State.parse(runtime.workspacesJson)
            val owned = state.memberships.filterValues { it in setOf(firstWorkspace.id, otherWorkspace.id) }.keys + ids.values
            rows().filter { it.getString("id") in owned }.forEach { runtime.terminateSession(it.getString("id")) }
            await("Fixture PTYs terminate") { rows().none { it.getString("id") in owned } }
            state.workspaces.filter { it.id in setOf(firstWorkspace.id, otherWorkspace.id) }.forEach { assertEquals("", runtime.deleteWorkspace(it.id)) }
            runtime.selectWorkspace(previousWorkspace)
            context.unbindService(connection)
            context.getSharedPreferences("terminal-ui-state", Context.MODE_PRIVATE).edit().apply {
                listOf(KEY_TERMINAL_LAYOUTS, "active-session", "session-tab-order").forEach { key ->
                    if (originalUi[key] is String) putString(key, originalUi[key] as String) else remove(key)
                }
            }.commit()
        }
    }

    @Test fun creatingSecondSessionInBothToolbarSizesKeepsTabsConsistent() = Fixture(listOf("A")).use { fixture ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            check(scenario, "First session visible") { it.focusedTerminalView() != null }
            scenario.onActivity { call(it, "attach", fixture.id("A")) }
            val profile = SessionProfiles(context).all().first { it.runtime == SessionRuntime.NATIVE }
            fun createFromPicker(): String {
                val before = fixture.rows().map { it.getString("id") }.toSet()
                var label = ""
                scenario.onActivity { label = it.getString(R.string.new_session) }
                var add: AccessibilityNodeInfo? = null
                await("New session button") {
                    add = node { it.contentDescription?.toString() == label }
                    while (add != null && add!!.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK }) add = add!!.parent
                    add != null
                }
                assertTrue(add!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                textAction(profile.name, AccessibilityNodeInfo.ACTION_CLICK)
                var created: String? = null
                await("New Native session running") {
                    created = fixture.rows().firstOrNull { it.getString("id") !in before && it.optString("state") == "RUNNING" }
                        ?.getString("id")
                    created != null
                }
                check(scenario, "New session focused") { paneState(it).focusedSessionId == created }
                instrumentation.waitForIdleSync()
                return requireNotNull(created)
            }
            val second = createFromPicker()
            fixture.runtime.terminateSession(second)
            await("Removed session disappears") { fixture.rows().none { it.getString("id") == second } }
            check(scenario, "First session restored after closing second") { paneState(it).focusedSessionId == fixture.id("A") }
            expandToolbar(scenario)
            val expandedSecond = createFromPicker()
            scenario.recreate()
            check(scenario, "Newest session survives recreation") { paneState(it).focusedSessionId == expandedSecond }
            assertTrue(fixture.rows().map { it.getString("id") }.containsAll(listOf(fixture.id("A"), expandedSecond)))
        }
    }

    @Test fun expandedTabReorderPersistsAfterReleaseOutputAndRecreation() = Fixture(listOf("A", "B", "C")).use { fixture ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            check(scenario, "Initial session visible") { it.focusedTerminalView() != null }
            scenario.onActivity {
                call(it, "attach", fixture.id("A"))
                MainActivity::class.java.getDeclaredMethod("reorderSessionTabs", List::class.java)
                    .apply { isAccessible = true }.invoke(it, listOf("A", "B", "C").map(fixture::id))
            }
            expandToolbar(scenario)
            fun savedOrder(): List<String> {
                val value = context.getSharedPreferences("terminal-ui-state", Context.MODE_PRIVATE)
                    .getString("session-tab-order", "[]")
                val order = JSONArray(value)
                return List(order.length()) { order.getString(it) }.filter { it in fixture.ids.values }
            }
            fun assertOrder(vararg titles: String) {
                val expected = titles.map(fixture::id)
                await("Released drag saves ${titles.toList()} instead of restoring the original order") { savedOrder() == expected }
                val positions = titles.map { tabBounds(it).centerY() }
                assertTrue("Tabs follow the saved order: $positions", positions.zipWithNext().all { (a, b) -> a < b })
            }
            assertOrder("A", "B", "C")
            TabDrag("A", "B").use { drag ->
                val originalB = drag.to.centerY()
                drag.moveTo(drag.from.centerX().toFloat(), drag.to.bottom - 1f)
                await("Neighbor moves before release") { tabBounds("B", false).centerY() < originalB }
                // Release in the gap after a small reversal. The live preview still puts A below B.
                drag.moveTo(drag.from.centerX().toFloat(), drag.to.top - 3 * context.resources.displayMetrics.density)
                drag.release()
            }
            assertOrder("B", "A", "C")
            fixture.runtime.writeSessionInput(fixture.id("A"), "printf 'TAB_ORDER_STAYS\\n'\n".toByteArray())
            check(scenario, "Output arrives without changing focus") {
                paneState(it).focusedSessionId == fixture.id("A") &&
                    "TAB_ORDER_STAYS" in it.focusedTerminalView()!!.mEmulator.screen.transcriptText
            }
            assertOrder("B", "A", "C")
            scenario.recreate()
            check(scenario, "Focused session restored") { paneState(it).focusedSessionId == fixture.id("A") }
            assertOrder("B", "A", "C")
            TabDrag("A", "B").use { drag ->
                drag.moveTo(drag.from.centerX().toFloat(), drag.to.top + 1f)
                drag.moveTo(drag.from.centerX().toFloat(), drag.to.bottom + 3 * context.resources.displayMetrics.density)
                drag.release()
            }
            assertOrder("A", "B", "C")
            TabDrag("A", "B").use { drag ->
                drag.moveTo(drag.from.centerX().toFloat(), drag.to.bottom - 1f)
            }
            assertOrder("A", "B", "C")
            check(scenario, "Reordering never creates a split") { !paneState(it).split }
            fixture.assertLive()
        }
    }

    @Test fun expandedDragReordersThenDwellsForAnimatedSplitAndChoosesBothPaneOrders() = Fixture().use { fixture ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            check(scenario, "Initial session visible") { it.focusedTerminalView() != null }
            scenario.onActivity { call(it, "attach", fixture.id("A")) }
            check(scenario, "A is focused") { paneState(it).focusedSessionId == fixture.id("A") }
            expandToolbar(scenario)
            instrumentation.uiAutomation.waitForIdle(300, 5_000)
            val originalB = tabBounds("B").centerY()
            TabDrag("A", "B").use { drag ->
                val endY = if (drag.from.centerY() < drag.to.centerY()) drag.to.bottom + 2 else drag.to.top - 2
                drag.moveTo(drag.to.centerX().toFloat(), endY.toFloat())
                await("Neighbor moves before the finger is released") {
                    kotlin.math.abs(tabBounds("B", false).centerY() - originalB) > 10
                }
                check(scenario, "Reordering never starts a split") { !paneState(it).split }
                drag.release()
            }
            val wasBelow = tabBounds("A").centerY() > tabBounds("B").centerY()
            TabDrag("A", "B").use { it.toTarget(); it.release() }
            check(scenario, "A quick center drop reorders instead of splitting") { !paneState(it).split }
            await("Quick center drop persists the new order") {
                (tabBounds("A").centerY() > tabBounds("B").centerY()) != wasBelow
            }
            fun preview(first: String, second: String) {
                await("Both tabs morph into one split row") {
                    val one = tabBounds(first, false); val two = tabBounds(second, false)
                    kotlin.math.abs(one.centerY() - two.centerY()) <= 2 &&
                        one.width() < context.resources.displayMetrics.widthPixels * 0.65f &&
                        two.width() < context.resources.displayMetrics.widthPixels * 0.65f
                }
            }
            TabDrag("A", "B", leftBody = true).use { drag ->
                drag.toTarget()
                // A body drag can remain left of the title while the finger wobbles vertically.
                val jitter = 6 * context.resources.displayMetrics.density / drag.to.height()
                drag.targetHeight(0.5f - jitter)
                drag.targetHeight(0.5f + jitter)
                drag.targetHeight(0.5f)
                // Briefly cross the strict 22% center boundary, then resume the same dwell.
                drag.targetHeight(0.18f)
                SystemClock.sleep(32)
                drag.targetHeight(0.5f)
                preview("A", "B")
                check(scenario, "Hover is presentation only until drop") { !paneState(it).split }
                drag.side(true)
                await("Preview puts the dragged tab on the left") { tabBounds("A", false).left < tabBounds("B", false).left }
                SystemClock.sleep(InstrumentationRegistry.getArguments().getString("split.inspectDelayMs")
                    ?.toLongOrNull()?.coerceIn(0, 30_000) ?: 0)
                drag.side(false)
                await("Preview swaps the dragged tab to the right") { tabBounds("A", false).left > tabBounds("B", false).left }
                drag.release()
            }
            check(scenario, "Right drop commits the preview order and source focus") {
                paneState(it).sessionIds == listOf(fixture.id("B"), fixture.id("A")) && paneState(it).focus == 1
            }
            for ((title, other) in listOf("A" to "B", "B" to "A")) {
                val width = tabBounds(title, false).width()
                val otherWidth = tabBounds(other, false).width()
                TabDrag(title, other).use {
                    assertEquals("Long-pressed split tab keeps its width", width, tabBounds(title, false).width())
                    assertEquals("Partner keeps its width", otherWidth, tabBounds(other, false).width())
                    check(scenario, "Holding a split tab leaves the pair and focus unchanged") {
                        paneState(it).sessionIds == listOf(fixture.id("B"), fixture.id("A")) && paneState(it).focus == 1
                    }
                }
            }
            TabDrag("A", "B").use { drag ->
                drag.toTarget(); drag.side(true)
                await("Existing pair previews the opposite order") { tabBounds("A", false).left < tabBounds("B", false).left }
                drag.release()
            }
            check(scenario, "Left drop can swap the existing pair without closing PTYs") {
                paneState(it).sessionIds == listOf(fixture.id("A"), fixture.id("B")) && paneState(it).focus == 0
            }
            TabDrag("C", "B").use { drag ->
                drag.toTarget(); preview("C", "B"); drag.side(false); drag.release()
            }
            check(scenario, "Drop replaces the pair independently of the focused session") {
                paneState(it).sessionIds == listOf(fixture.id("B"), fixture.id("C")) && paneState(it).focus == 1
            }
            val tap = tabBounds("B")
            val downTime = SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                    tap.centerX().toFloat(), tap.centerY().toFloat(), 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
            check(scenario, "Grouped tab still focuses its pane") { paneState(it).focus == 0 }
            scenario.recreate()
            check(scenario, "Recreation preserves the ordered pair and focus") {
                paneState(it).sessionIds == listOf(fixture.id("B"), fixture.id("C")) && paneState(it).focus == 0
            }
            fixture.assertLive()
        }
    }

    @Test fun menusTabsDividerWorkspaceAndRecreationPreserveTwoLiveSessions() = Fixture().use { fixture ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            check(scenario, "Initial session visible") { it.focusedTerminalView() != null }
            scenario.onActivity { call(it, "attach", fixture.id("A")) }
            expandToolbar(scenario)
            menu(scenario, "B", R.string.terminal_open_split)
            check(scenario, "Independent terminal panes") {
                paneState(it).sessionIds == listOf(fixture.id("A"), fixture.id("B")) &&
                    host(it)?.terminal(0)?.currentSession != null && host(it)?.terminal(1)?.currentSession != null &&
                    host(it)?.terminal(0) !== host(it)?.terminal(1)
            }
            var stableViews = emptyList<TerminalView>()
            scenario.onActivity { stableViews = terminalViews(it) }
            SystemClock.sleep(InstrumentationRegistry.getArguments().getString("split.inspectDelayMs")
                ?.toLongOrNull()?.coerceIn(0, 30_000) ?: 0)
            textAction("A", AccessibilityNodeInfo.ACTION_CLICK)
            check(scenario, "Visible tab focuses its pane") { paneState(it).focus == 0 }
            scenario.onActivity { assertSameTerminalViews(it, stableViews) }
            textAction("C", AccessibilityNodeInfo.ACTION_CLICK)
            check(scenario, "An ordinary tab opens alone and retains the original pair") {
                !paneState(it).split && paneState(it).sessionIds == listOf(fixture.id("C")) &&
                    paneState(it).splitSessionIds == listOf(fixture.id("A"), fixture.id("B")) &&
                    it.focusedTerminalView()?.currentSession != null
            }
            fixture.assertLive()
            scenario.recreate()
            check(scenario, "Recreation retains the ordinary tab and saved pair") {
                !paneState(it).split && paneState(it).focusedSessionId == fixture.id("C") &&
                    paneState(it).splitSessionIds == listOf(fixture.id("A"), fixture.id("B"))
            }
            textAction("A", AccessibilityNodeInfo.ACTION_CLICK)
            check(scenario, "Selecting a grouped tab restores its original partner") {
                paneState(it).sessionIds == listOf(fixture.id("A"), fixture.id("B")) && paneState(it).focus == 0 &&
                    host(it)?.terminal(0)?.currentSession != null && host(it)?.terminal(1)?.currentSession != null
            }
            scenario.onActivity { stableViews = terminalViews(it) }
            check(scenario, "Stacked panes retain two visible terminal slots") {
                val first = android.graphics.Rect(); val second = android.graphics.Rect()
                paneState(it).direction == SplitDirection.Stacked &&
                    host(it)!!.terminal(0)!!.getGlobalVisibleRect(first) && host(it)!!.terminal(1)!!.getGlobalVisibleRect(second) &&
                    first.height() > 0 && second.height() > 0 && second.top >= first.bottom
            }
            scenario.onActivity { assertSameTerminalViews(it, stableViews) }
            var groupMenuLabel = ""
            var sideBySideLabel = ""
            scenario.onActivity {
                groupMenuLabel = it.getString(R.string.workspace_session_menu, "A")
                sideBySideLabel = it.getString(R.string.terminal_split_side_by_side)
            }
            var groupMenu: AccessibilityNodeInfo? = null
            await("Shared split-tab menu button") {
                groupMenu = node { it.contentDescription?.toString() == groupMenuLabel }
                while (groupMenu != null && groupMenu!!.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK }) groupMenu = groupMenu!!.parent
                groupMenu != null
            }
            assertTrue(groupMenu!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertSplitControlRow(scenario)
            val inspectMenu = InstrumentationRegistry.getArguments().getString("split.inspectMenuMs")
                ?.toLongOrNull()?.coerceIn(0, 30_000) ?: 0
            if (inspectMenu > 0) {
                android.util.Log.i("SplitMenuInspection", "Shared split menu is open")
                SystemClock.sleep(inspectMenu)
            }
            textAction(sideBySideLabel, AccessibilityNodeInfo.ACTION_CLICK)
            val ratio = 0.35f
            var dividerLabel = ""
            scenario.onActivity { dividerLabel = host(it)!!.context.getString(R.string.terminal_split_resize) }
            val inspectHandleMenu = InstrumentationRegistry.getArguments().getString("split.inspectHandleMenuMs")
                ?.toLongOrNull()?.coerceIn(0, 8_000) ?: 0
            var inspectedHandleMenu = false
            fun handleMenu(action: Int) {
                val anchor = android.graphics.Rect().also {
                    actionNode(dividerLabel, AccessibilityNodeInfo.ACTION_CLICK).getBoundsInScreen(it)
                }
                val window = android.graphics.Rect()
                scenario.onActivity { it.window.decorView.getWindowVisibleDisplayFrame(window) }
                textAction(dividerLabel, AccessibilityNodeInfo.ACTION_CLICK)
                assertSplitControlRow(scenario)
                val density = context.resources.displayMetrics.density
                // Leave room for the 168dp surface and the platform's screen-edge margin.
                if (anchor.centerX() - 92 * density >= window.left && anchor.centerX() + 92 * density <= window.right) {
                    await("The handle menu settles centered on its anchor x=${anchor.centerX()}") {
                        val bounds = listOf(R.string.terminal_split_side_by_side, R.string.terminal_unsplit).map { id ->
                            android.graphics.Rect().also {
                                actionNode(context.getString(id), AccessibilityNodeInfo.ACTION_CLICK).getBoundsInScreen(it)
                            }
                        }
                        bounds.all { kotlin.math.abs(it.width() - 48 * density) <= 2 } &&
                            kotlin.math.abs((bounds.first().left + bounds.last().right) / 2f - anchor.centerX()) <= 2
                    }
                }
                if (inspectHandleMenu > 0 && !inspectedHandleMenu) {
                    android.util.Log.i("SplitHandleMenuInspection", "Pane handle menu is open")
                    SystemClock.sleep(inspectHandleMenu)
                    inspectedHandleMenu = true
                }
                var label = ""
                scenario.onActivity { label = it.getString(action) }
                textAction(label, AccessibilityNodeInfo.ACTION_CLICK)
            }
            handleMenu(R.string.terminal_split_stacked)
            check(scenario, "The handle menu switches to stacked panes") {
                paneState(it).direction == SplitDirection.Stacked &&
                    host(it)!!.terminal(0)!!.width == host(it)!!.terminal(1)!!.width &&
                    host(it)!!.terminal(0)!!.height < host(it)!!.height
            }
            handleMenu(R.string.terminal_split_side_by_side)
            val divider = actionNode(dividerLabel, AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id)
            assertTrue(divider.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, ratio) }))
            check(scenario, "Divider changes pane geometry") {
                paneState(it).fraction == ratio && host(it)!!.terminal(0)!!.width < host(it)!!.terminal(1)!!.width &&
                    host(it)!!.terminal(0)!!.mEmulator.mColumns < host(it)!!.terminal(1)!!.mEmulator.mColumns
            }
            scenario.onActivity { assertSameTerminalViews(it, stableViews) }
            val inspectPane = InstrumentationRegistry.getArguments().getString("split.inspectPaneMs")
                ?.toLongOrNull()?.coerceIn(0, 15_000) ?: 0
            if (inspectPane > 0) {
                android.util.Log.i("SplitPaneInspection", "Material split panes ready: SideBySide fraction=$ratio")
                SystemClock.sleep(inspectPane)
            }
            var firstSize = ""; var secondSize = ""
            scenario.onActivity {
                firstSize = host(it)!!.terminal(0)!!.mEmulator.let { e -> "${e.mRows} ${e.mColumns}" }
                secondSize = host(it)!!.terminal(1)!!.mEmulator.let { e -> "${e.mRows} ${e.mColumns}" }
            }
            fixture.runtime.writeSessionInput(fixture.id("A"), "printf 'PTY_A='; stty size\n".toByteArray())
            fixture.runtime.writeSessionInput(fixture.id("B"), "printf 'PTY_B='; stty size\n".toByteArray())
            check(scenario, "Each real PTY has the corresponding pane geometry") {
                "PTY_A=$firstSize" in host(it)!!.terminal(0)!!.mEmulator.screen.transcriptText &&
                    "PTY_B=$secondSize" in host(it)!!.terminal(1)!!.mEmulator.screen.transcriptText
            }
            scenario.recreate()
            check(scenario, "Rotation/recreation restores layout and focus") {
                paneState(it) == TerminalPaneState(fixture.id("A"), fixture.id("B"), 0, SplitDirection.SideBySide, ratio) &&
                    it.focusedTerminalView()?.currentSession != null
            }
            scenario.onActivity { call(it, "selectWorkspaceFilter", fixture.otherWorkspace.id) }
            check(scenario, "Other Workspace has its own single layout") { paneState(it).first == fixture.id("D") && !paneState(it).split }
            scenario.onActivity { call(it, "selectWorkspaceFilter", fixture.firstWorkspace.id) }
            check(scenario, "Workspace restores split") { paneState(it).split && paneState(it).fraction == ratio && paneState(it).focus == 0 }
            handleMenu(R.string.terminal_unsplit)
            check(scenario, "Unsplit retains focused session") { paneState(it).first == fixture.id("A") && !paneState(it).split }
            fixture.assertLive()
            val inspectCollapse = InstrumentationRegistry.getArguments().getString("split.inspectCollapseMs")
                ?.toLongOrNull()?.coerceIn(0, 8_000) ?: 0
            for (direction in SplitDirection.entries) for (edge in 0..1) {
                scenario.onActivity { call(it, "attach", fixture.id("A")) }
                menu(scenario, "B", R.string.terminal_open_split)
                handleMenu(if (direction == SplitDirection.SideBySide) R.string.terminal_split_side_by_side
                    else R.string.terminal_split_stacked)
                check(scenario, "$direction panes settle before edge=$edge drag") {
                    val pane = host(it)!!
                    paneState(it).split && paneState(it).direction == direction &&
                        pane.terminal(0)?.let { first -> if (direction == SplitDirection.SideBySide)
                            first.height == pane.terminal(1)?.height && first.width < pane.width
                        else first.width == pane.terminal(1)?.width && first.height < pane.height } == true
                }
                val closing = if (edge == 0) "A" else "B"
                val survivor = if (edge == 0) "B" else "A"
                textAction(closing, AccessibilityNodeInfo.ACTION_CLICK)
                var survivingSession: dev.terminal.emulator.TerminalSession? = null
                var gestureViews = emptyList<TerminalView>()
                var endCoordinate = 0f
                var cross = 0f
                val horizontal = direction == SplitDirection.SideBySide
                val downTime = SystemClock.uptimeMillis()
                scenario.onActivity {
                    val pane = host(it)!!
                    gestureViews = terminalViews(it)
                    survivingSession = pane.terminal(1 - edge)!!.currentSession
                    val density = context.resources.displayMetrics.density
                    val length = if (horizontal) pane.width else pane.height
                    cross = (if (horizontal) pane.height else pane.width) / 2f
                    val firstContainer = pane.terminal(0)!!.parent as android.view.View
                    val start = (if (horizontal) firstContainer.width else firstContainer.height) + 6 * density
                    // Keep the full 48dp accessibility target visible while entering the 48dp collapse zone.
                    endCoordinate = if (edge == 0) 24 * density else length - 24 * density
                    for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE)) {
                        val coordinate = if (action == android.view.MotionEvent.ACTION_DOWN) start else endCoordinate
                        val event = android.view.MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                            if (horizontal) coordinate else cross, if (horizontal) cross else coordinate, 0)
                        try { assertTrue(if (action == android.view.MotionEvent.ACTION_DOWN)
                            pane.onInterceptTouchEvent(event) else pane.onTouchEvent(event)) } finally { event.recycle() }
                    }
                }
                check(scenario, "$direction edge=$edge previews only the pane being removed") {
                    val alphas = (0..1).map { index ->
                        (host(it)!!.terminal(index)!!.parent as android.widget.FrameLayout).foreground?.alpha ?: 0
                    }
                    paneState(it).split && paneState(it).focusedSessionId == fixture.id(closing) &&
                        alphas[edge] > 0 && alphas[1 - edge] == 0
                }
                await("$direction edge=$edge visual boundary follows the finger beyond safe PTY bounds") {
                    val bounds = android.graphics.Rect().also {
                        actionNode(dividerLabel, AccessibilityNodeInfo.ACTION_CLICK).getBoundsInScreen(it)
                    }
                    var origin = 0
                    scenario.onActivity {
                        val location = IntArray(2)
                        host(it)!!.getLocationOnScreen(location)
                        origin = location[if (horizontal) 0 else 1]
                        assertSameTerminalViews(it, gestureViews)
                    }
                    kotlin.math.abs((if (horizontal) bounds.exactCenterX() else bounds.exactCenterY()) - origin - endCoordinate) <= 2f
                }
                if (inspectCollapse > 0 && direction == SplitDirection.SideBySide) {
                    android.util.Log.i("SplitCollapseInspection", if (edge == 0) "Leading" else "Trailing")
                    SystemClock.sleep(inspectCollapse)
                }
                scenario.onActivity {
                    val event = android.view.MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_UP,
                        if (horizontal) endCoordinate else cross, if (horizontal) cross else endCoordinate, 0)
                    try { assertTrue(host(it)!!.onTouchEvent(event)) } finally { event.recycle() }
                }
                check(scenario, "$direction edge=$edge retains opposite session $survivor regardless of focus") {
                    !paneState(it).split && paneState(it).sessionIds == listOf(fixture.id(survivor)) &&
                        it.focusedTerminalView()?.currentSession === survivingSession &&
                        (it.focusedTerminalView()!!.parent as android.widget.FrameLayout).foreground?.alpha.let { alpha -> alpha == null || alpha == 0 }
                }
                fixture.assertLive()
            }
            menu(scenario, "B", R.string.terminal_open_split)
            check(scenario, "Split reopened") { paneState(it).split }
            assertEquals("", fixture.runtime.moveSessionToWorkspace(fixture.id("B"), fixture.otherWorkspace.id))
            check(scenario, "Moving a pane to another Workspace safely collapses split") {
                !paneState(it).split && paneState(it).first == fixture.id("A")
            }
            fixture.assertLive()
            menu(scenario, "C", R.string.terminal_open_split)
            check(scenario, "New pair is visible") { paneState(it).split }
            fixture.runtime.terminateSession(fixture.id("C"))
            check(scenario, "Explicit PTY termination retains surviving pane") { !paneState(it).split && paneState(it).first == fixture.id("A") }
            assertTrue(fixture.rows().any { it.getString("id") == fixture.id("A") && it.getString("state") == "RUNNING" })
        }
    }

    @Test fun compactSplitTabsKeepTheirGroupingAndManualScrollPosition() =
        Fixture(listOf("A", "B", "C") + (1..6).map { "Scroll extra $it" }).use { fixture ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                check(scenario, "Initial session visible") { it.focusedTerminalView() != null }
                scenario.onActivity {
                    // Runtime completion order must not move the A/B group behind the scroll fixtures.
                    MainActivity::class.java.getDeclaredMethod("reorderSessionTabs", List::class.java)
                        .apply { isAccessible = true }.invoke(it, fixture.ids.toSortedMap().values.toList())
                    call(it, "attach", fixture.id("A"))
                    call(it, "openSplit", fixture.id("B"))
                    call(it, "attach", fixture.id("A"))
                }
                check(scenario, "Compact tool shows the split pair") {
                    paneState(it).sessionIds == listOf(fixture.id("A"), fixture.id("B")) && paneState(it).focus == 0
                }
                val tabRowHeight = 48 * context.resources.displayMetrics.density
                fun compactTabScroller(): AccessibilityNodeInfo? {
                    var target = fixture.ids.keys.asSequence().mapNotNull { tabNode(it) }.firstOrNull()
                    while (target != null) {
                        val bounds = android.graphics.Rect().also(target::getBoundsInScreen)
                        if (target.isScrollable && kotlin.math.abs(bounds.height() - tabRowHeight) <= 2) return target
                        target = target.parent
                    }
                    return null
                }
                // Geometry assertions start at the first group, regardless of a retained viewport.
                for (attempt in 0 until 10) {
                    val scroller = compactTabScroller()
                    if (scroller != null) {
                        if (scroller.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD }) break
                        assertTrue(scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
                    }
                    SystemClock.sleep(400)
                }
                val initialScroller = compactTabScroller()
                assertNotNull("The 48dp compact tab row is available", initialScroller)
                assertFalse("The compact tab row reaches its start within ten backward scrolls",
                    initialScroller!!.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD })
                val expectedGap = (2 * context.resources.displayMetrics.density).toInt()
                await("Grouped tabs settle into one row with the configured gap") {
                    val first = tabBounds("A", false)
                    val second = tabBounds("B", false)
                    kotlin.math.abs(first.centerY() - second.centerY()) <= 2 &&
                        kotlin.math.abs(second.left - first.right - expectedGap) <= 1
                }
                val inspectCompact = InstrumentationRegistry.getArguments().getString("split.inspectCompactMs")
                    ?.toLongOrNull()?.coerceIn(0, 30_000) ?: 0
                if (inspectCompact > 0) {
                    android.util.Log.i("SplitCompactInspection", "Compact split tabs are ready")
                    SystemClock.sleep(inspectCompact)
                }
                var closeA = ""; var closeB = ""; var menuA = ""; var menuB = ""
                scenario.onActivity {
                    closeA = it.getString(R.string.terminate_session_description, "A")
                    closeB = it.getString(R.string.terminate_session_description, "B")
                    menuA = it.getString(R.string.workspace_session_menu, "A")
                    menuB = it.getString(R.string.workspace_session_menu, "B")
                }
                fun description(label: String) = node { it.contentDescription?.toString() == label }
                val compose = composeRoot(scenario)
                fun assertIconTab(title: String, menuLabel: String) {
                    val expectedWidth = 48 * context.resources.displayMetrics.density
                    await("The inactive $title tab settles to a named 48dp icon without title text") {
                        var frame = emptyMap<String, TabMotionBounds>()
                        scenario.onActivity { frame = tabMotionFrame(compose, setOf(title)) }
                        val width = frame[title]?.bounds?.width
                        width != null && kotlin.math.abs(width - expectedWidth) <= 1 &&
                            node { it.text?.toString() == title } == null && description(title) != null
                    }
                    var target = tabNode(title)
                    while (target != null && target!!.actionList.none { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK }) {
                        target = target!!.parent
                    }
                    val action = target?.actionList?.firstOrNull { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK }
                    assertNotNull("The icon tab retains its long-press menu", action)
                    assertEquals("The icon menu is named for its session", menuLabel, action!!.label?.toString())
                }
                assertNotNull("Selected grouped tab retains its title", node { it.text?.toString() == "A" })
                assertNotNull("Selected grouped tab keeps its close button", description(closeA))
                assertNull("Unselected grouped tab has no close button", description(closeB))
                assertIconTab("B", menuB)
                assertTrue("The selected tab keeps room for its title and close button",
                    tabBounds("A", false).width() > tabBounds("B", false).width())
                assertNull("Compact group has no kebab button for A", description(menuA))
                assertNull("Compact group has no kebab button for B", description(menuB))
                textAction("B", AccessibilityNodeInfo.ACTION_LONG_CLICK)
                assertSplitControlRow(scenario)
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                val swapTitles = setOf("A", "B")
                var beforeSwap = emptyMap<String, TabMotionBounds>()
                scenario.onActivity { beforeSwap = tabMotionFrame(compose, swapTitles) }
                val beforeA = requireNotNull(beforeSwap["A"]).bounds
                val beforeB = requireNotNull(beforeSwap["B"]).bounds
                val animationScale = android.provider.Settings.Global.getFloat(context.contentResolver,
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
                val frames = mutableListOf<Map<String, TabMotionBounds>>()
                // Accessibility calls can finish after motion, so observe actual frames before clicking.
                val listener = ViewTreeObserver.OnPreDrawListener { frames += tabMotionFrame(compose, swapTitles); true }
                if (animationScale > 0f) scenario.onActivity { compose.view.viewTreeObserver.addOnPreDrawListener(listener) }
                try {
                    textAction("B", AccessibilityNodeInfo.ACTION_CLICK)
                    check(scenario, "Compact grouped tab focuses its own pane") { paneState(it).focusedSessionId == fixture.id("B") }
                    await("Close button follows the selected grouped tab") { description(closeB) != null && description(closeA) == null }
                    assertNotNull("The newly selected tab shows its title", node { it.text?.toString() == "B" })
                    assertIconTab("A", menuA)
                    assertTrue("The selected tab expands while its partner becomes an icon",
                        tabBounds("B", false).width() > tabBounds("A", false).width())
                    if (animationScale > 0f) {
                        var samples = emptyList<Map<String, TabMotionBounds>>()
                        scenario.onActivity { samples = frames.toList() }
                        val observedIntermediate = samples.any { frame ->
                            val one = frame["A"]?.bounds; val two = frame["B"]?.bounds
                            one != null && two != null &&
                                one.width > beforeB.width + 2 && one.width < beforeA.width - 2 &&
                                two.width > beforeB.width + 2 && two.width < beforeA.width - 2 &&
                                kotlin.math.abs(one.width + two.width - beforeA.width - beforeB.width) <= 2 &&
                                kotlin.math.abs(two.left - one.right - (beforeB.left - beforeA.right)) <= 1 &&
                                kotlin.math.abs(one.center.y - two.center.y) <= 2
                        }
                        assertTrue("Focus swap has intermediate widths with a fixed sum and gap: before=$beforeSwap frames=${samples.take(20)}",
                            observedIntermediate)
                    }
                } finally {
                    if (animationScale > 0f) scenario.onActivity { compose.view.viewTreeObserver.removeOnPreDrawListener(listener) }
                }

                var scrolls = 0
                repeat(2) {
                    val scroller = compactTabScroller()?.takeIf { value ->
                        value.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }
                    }
                    if (scroller == null) return@repeat
                    assertTrue(scroller!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                    scrolls++
                    var previous: List<Pair<String, android.graphics.Rect>>? = null
                    var unchangedSince = SystemClock.uptimeMillis()
                    await("Visible tab positions settle after scrolling") {
                        val positions = fixture.ids.keys.mapNotNull { title ->
                            tabNode(title)?.let { value ->
                                title to android.graphics.Rect().also(value::getBoundsInScreen)
                            }
                        }
                        if (positions != previous) {
                            previous = positions
                            unchangedSince = SystemClock.uptimeMillis()
                        }
                        positions.isNotEmpty() && SystemClock.uptimeMillis() - unchangedSince >= 200
                    }
                }
                assertTrue("Compact tab list scrolls forward", scrolls > 0)
                await("Manual scrolling moves the focused pair off screen") {
                    tabNode("A") == null && tabNode("B") == null
                }
                var visibleExtra: String? = null
                await("A later tab remains visible after manual scrolling") {
                    visibleExtra = (1..6).map { "Scroll extra $it" }.firstOrNull { title -> node { it.text?.toString() == title } != null }
                    visibleExtra != null
                }
                val anchor = requireNotNull(visibleExtra)
                val beforeOutput = tabBounds(anchor, false)
                fixture.runtime.writeSessionInput(fixture.id("B"), "printf 'COMPACT_SCROLL_STAYS\\n'\n".toByteArray())
                check(scenario, "Terminal output does not change focus") {
                    paneState(it).focusedSessionId == fixture.id("B") &&
                        "COMPACT_SCROLL_STAYS" in it.focusedTerminalView()!!.mEmulator.screen.transcriptText
                }
                SystemClock.sleep(600)
                assertEquals("Output and recomposition keep the user's scroll position", beforeOutput, tabBounds(anchor, false))
                assertNull("The list does not jump back to the focused split tabs", tabNode("B"))
                fixture.assertLive()
            }
        }

    @Test fun imeFocusTuiGraphicsAndBackgroundRestoreRemainIndependent() = Fixture().use { fixture ->
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            check(scenario, "Runtime is attached") { it.focusedTerminalView() != null }
            scenario.onActivity { call(it, "attach", fixture.id("A")); call(it, "openSplit", fixture.id("B")) }
            check(scenario, "Both emulators exist") { host(it)?.terminal(0)?.mEmulator != null && host(it)?.terminal(1)?.mEmulator != null }
            var stableViews = emptyList<TerminalView>()
            scenario.onActivity { stableViews = terminalViews(it) }
            SystemClock.sleep(InstrumentationRegistry.getArguments().getString("split.inspectDelayMs")
                ?.toLongOrNull()?.coerceIn(0, 30_000) ?: 0)
            check(scenario, "IME layout leaves the second pane visible above the keyboard") { activity ->
                val view = host(activity)!!.terminal(1)!!
                val rect = android.graphics.Rect()
                val insets = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                val keyboard = insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0
                view.getGlobalVisibleRect(rect) && rect.height() > 0 && keyboard > 0 &&
                    rect.bottom <= activity.window.decorView.height - keyboard
            }
            lateinit var oldConnection: android.view.inputmethod.InputConnection
            val tap = IntArray(2)
            scenario.onActivity { activity ->
                val first = host(activity)!!.terminal(0)!!
                val second = host(activity)!!.terminal(1)!!
                call(activity, "attach", fixture.id("A"))
                oldConnection = first.onCreateInputConnection(EditorInfo())
                assertTrue(oldConnection.setComposingText("한", 1))
                second.getLocationOnScreen(tap)
                tap[0] += second.width / 2; tap[1] += second.height / 2
            }
            val time = SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, tap[0].toFloat(), tap[1].toFloat(), 0)
                instrumentation.sendPointerSync(event); event.recycle()
            }
            check(scenario, "Panel tap switches input focus") { paneState(it).focusedSessionId == fixture.id("B") }
            scenario.onActivity { activity ->
                assertSameTerminalViews(activity, stableViews)
                val second = host(activity)!!.terminal(1)!!
                assertFalse(oldConnection.commitText("echo WRONG_SPLIT_TARGET\r", 1))
                val input = second.onCreateInputConnection(EditorInfo())
                assertTrue(input.commitText("echo SPLIT_IME_TARGET\r", 1))
                assertSame(second, activity.focusedTerminalView())
            }
            check(scenario, "IME writes only to focused session") {
                val first = host(it)!!.terminal(0)!!.mEmulator.screen.transcriptText
                val second = host(it)!!.terminal(1)!!.mEmulator.screen.transcriptText
                "SPLIT_IME_TARGET" in second && "SPLIT_IME_TARGET" !in first && "WRONG_SPLIT_TARGET" !in first
            }
            scenario.onActivity { activity ->
                "echo split_hotkey_target".forEach { character ->
                    call(activity, "sendHotkeyShortcut", if (character == ' ') "SPACE" else character.uppercaseChar().toString())
                }
                call(activity, "sendHotkeyShortcut", "ENTER")
            }
            check(scenario, "Common hotkeys target only the focused pane") {
                "split_hotkey_target" in host(it)!!.terminal(1)!!.mEmulator.screen.transcriptText &&
                    "split_hotkey_target" !in host(it)!!.terminal(0)!!.mEmulator.screen.transcriptText
            }
            val red = """printf '\033[?1049h\033[41m\033[2J\033[Hfirst\033_Ga=T,i=91,q=2,f=24,s=1,v=1,C=1;/wAA\033\\'""" + "\n"
            val blue = """printf '\033[?1049h\033[44m\033[2J\033[Hsecond\033_Ga=T,i=92,q=2,f=24,s=1,v=1,C=1;AAD/\033\\'""" + "\n"
            fixture.runtime.writeSessionInput(fixture.id("A"), red.toByteArray())
            fixture.runtime.writeSessionInput(fixture.id("B"), blue.toByteArray())
            check(scenario, "Both panels retain separate TUI and graphics state") {
                val first = host(it)!!.terminal(0)!!.mEmulator
                val second = host(it)!!.terminal(1)!!.mEmulator
                first.isAlternateBufferActive && second.isAlternateBufferActive &&
                    first.graphics.placements.singleOrNull()?.image?.pixels?.firstOrNull() == 0xffff0000.toInt() &&
                    second.graphics.placements.singleOrNull()?.image?.pixels?.firstOrNull() == 0xff0000ff.toInt()
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            SystemClock.sleep(TerminalSessionStore.BACKGROUND_GRACE_MS + 300)
            scenario.moveToState(Lifecycle.State.RESUMED)
            check(scenario, "Authoritative checkpoints restore both panes after grace period") {
                paneState(it).split && paneState(it).focusedSessionId == fixture.id("B") &&
                    host(it)?.terminal(0)?.mEmulator?.graphics?.placements?.size == 1 &&
                    host(it)?.terminal(1)?.mEmulator?.graphics?.placements?.size == 1
            }
            scenario.onActivity { assertSameTerminalViews(it, stableViews) }
            scenario.recreate()
            check(scenario, "Activity recreation keeps both live graphics sessions") {
                paneState(it).split && host(it)?.terminal(0)?.mEmulator?.isAlternateBufferActive == true &&
                    host(it)?.terminal(1)?.mEmulator?.isAlternateBufferActive == true
            }
            fixture.assertLive()
        }
    }

    @Test fun singlePaneStillConnectsImeAfterRecreationAndReopensItAfterBack() = Fixture().use {
        TerminalKeyboardTest().entryAndActivityRecreationConnectTheKeyboard()
        TerminalKeyboardTest().backHidesKeyboardAndTerminalTapReopensIt()
    }
}
