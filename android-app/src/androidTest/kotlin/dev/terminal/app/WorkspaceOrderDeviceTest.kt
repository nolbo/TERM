package dev.terminal.app

import android.content.*
import android.graphics.Rect
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import dev.terminal.runtime.workspace.Workspace
import dev.terminal.runtime.workspace.WorkspaceRegistry
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WorkspaceOrderDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun label(id: Int) = context.getString(id)
    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && predicate(node)) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(message, condition())
    }
    private fun text(value: String) = node { it.text?.toString() == value }
    private fun description(value: String) = node { it.contentDescription?.toString() == value }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun click(node: AccessibilityNodeInfo) {
        var target = node
        while (!target.isClickable && target.parent != null) target = target.parent
        assertTrue(target.isEnabled)
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun button(resource: Int) {
        var target: AccessibilityNodeInfo? = null
        await("Button: ${label(resource)}") { target = description(label(resource)) ?: text(label(resource)); target != null }
        click(target!!)
    }
    private fun handle(workspace: Workspace) = description(context.getString(R.string.toolbar_move, workspace.name))
    private fun drag(from: Rect, to: Rect, cancel: Boolean = false) {
        val start = SystemClock.uptimeMillis()
        for (index in 0..20) {
            val action = when (index) {
                0 -> MotionEvent.ACTION_DOWN
                20 -> if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val fraction = index / 20f
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, from.exactCenterX(),
                from.exactCenterY() + (to.exactCenterY() - from.exactCenterY()) * fraction, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            SystemClock.sleep(20)
        }
        instrumentation.waitForIdleSync()
    }
    private fun ordered(vararg workspaces: Workspace): Boolean {
        val positions = workspaces.map { workspace -> text(workspace.name)?.let { bounds(it).top } }
        return positions.none { it == null } && positions.filterNotNull().zipWithNext().all { (one, two) -> one < two }
    }

    @Test fun editModeSupportsDragCancelAccessibilityPersistenceAndSelectorOrder() {
        val ready = CountDownLatch(1)
        lateinit var runtime: IRuntimeService
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                runtime = IRuntimeService.Stub.asInterface(binder); ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            fun state() = WorkspaceRegistry.State.parse(runtime.workspacesJson)
            val before = state()
            val suffix = UUID.randomUUID().toString().take(8)
            val first = Workspace(UUID.randomUUID().toString(), "First $suffix", false)
            val second = Workspace(UUID.randomUUID().toString(), "Second workspace with a longer name $suffix", false)
            val third = Workspace(UUID.randomUUID().toString(), "Third $suffix", false)
            val fixtures = listOf(first, second, third)
            val fixtureIds = fixtures.map { it.id }.toSet()
            fun savedOrder() = state().workspaces.filter { it.id in fixtureIds }.map { it.id }
            try {
                fixtures.forEach { assertEquals("", runtime.saveWorkspace(it.json().toString())) }
                assertEquals("", runtime.reorderWorkspaces(JSONArray(fixtures.map { it.id } + before.workspaces.map { it.id }).toString()))
                assertEquals("", runtime.selectWorkspace(first.id))
                ActivityScenario.launch(WorkspacesActivity::class.java).use { scenario ->
                    await("Workspace list loads the saved order") { text(first.name) != null && savedOrder() == fixtures.map { it.id } }
                    assertNull(handle(first))
                    button(R.string.toolbar_edit)
                    await("Editing shows usable drag handles") {
                        handle(first)?.let { bounds(it).width() >= (40 * context.resources.displayMetrics.density).toInt() } == true && ordered(first, second, third)
                    }
                    click(requireNotNull(text(first.name)))
                    assertNotNull("Editing does not open the detail screen", text(label(R.string.workspaces_edit_summary)))
                    drag(bounds(requireNotNull(handle(first))), bounds(requireNotNull(handle(second))))
                    await("Drop persists a new order") { savedOrder() == listOf(second.id, first.id, third.id) }
                    await("Rows follow the persisted order") { ordered(second, first, third) }
                    drag(bounds(requireNotNull(handle(first))), bounds(requireNotNull(handle(second))), cancel = true)
                    await("Cancelled drag restores the saved order") { ordered(second, first, third) }
                    assertEquals(listOf(second.id, first.id, third.id), savedOrder())
                    val moving = requireNotNull(handle(first))
                    val down = moving.actionList.single { it.label?.toString() == label(R.string.toolbar_move_down) }
                    assertTrue(moving.performAction(down.id))
                    await("Accessibility reorders and saves") { savedOrder() == listOf(second.id, third.id, first.id) }
                    scenario.recreate()
                    await("Edit mode and order survive recreation") {
                        description(label(R.string.toolbar_done)) != null && handle(second) != null && ordered(second, third, first)
                    }
                    button(R.string.toolbar_done)
                    await("Done hides the handles") { handle(second) == null }
                }
                ActivityScenario.launch(WorkspacesActivity::class.java).use {
                    await("Order survives reopening settings") { text(second.name) != null && savedOrder() == listOf(second.id, third.id, first.id) }
                    assertNull(handle(second))
                }
                ActivityScenario.launch(MainActivity::class.java).use {
                    var selector: AccessibilityNodeInfo? = null
                    await("Workspace selector") {
                        selector = description(context.getString(R.string.workspace_selector, first.name)); selector != null
                    }
                    click(selector!!)
                    await("The selector shares the saved order") { ordered(second, third, first) }
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                }
                assertEquals(first.id, state().selected)
                assertEquals(before.memberships, state().memberships)
            } finally {
                fixtures.forEach { assertEquals("", runtime.deleteWorkspace(it.id)) }
                assertEquals("", runtime.reorderWorkspaces(JSONArray(before.workspaces.map { it.id }).toString()))
                val restoredSelection = before.selected.takeIf { it == WorkspaceRegistry.UNASSIGNED || state().workspaces.any { row -> row.id == it } }
                    ?: WorkspaceRegistry.UNASSIGNED
                assertEquals("", runtime.selectWorkspace(restoredSelection))
            }
        } finally { context.unbindService(connection) }
    }
}
