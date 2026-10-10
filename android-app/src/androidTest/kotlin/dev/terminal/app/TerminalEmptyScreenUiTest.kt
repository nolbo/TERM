package dev.terminal.app

import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.workspace.WorkspaceRegistry
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the production screen without replacing its composition or deleting runtime data. */
@RunWith(AndroidJUnit4::class)
class TerminalEmptyScreenUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val runtimeField = MainActivity::class.java.getDeclaredField("runtime").apply { isAccessible = true }

    @Test fun initialEmptyScreenKeepsNavigationAfterRecreationAndOpensSettings() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            requireEmptyRuntime(scenario)
            assertEmptyNavigation()
            scenario.recreate()
            requireEmptyRuntime(scenario)
            assertEmptyNavigation()

            click(node { it.contentDescription?.toString()?.startsWith(label(R.string.workspace_selector, "")) == true })
            node { it.text?.toString() == label(R.string.workspace_unassigned) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            assertEmptyNavigation()

            val monitor = instrumentation.addMonitor(SettingsActivity::class.java.name, null, false)
            try {
                click(description(R.string.settings))
                val settings = monitor.waitForActivityWithTimeout(5000)
                assertNotNull("Settings opens directly from the initial empty toolbar", settings)
                instrumentation.runOnMainSync { requireNotNull(settings).finish() }
                assertEmptyNavigation()
            } finally {
                instrumentation.removeMonitor(monitor)
            }
        }
    }

    @Test fun initialEmptyScreenOpensTheEmbeddedProfilePickerAndReturnsToItsToolbar() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            requireEmptyRuntime(scenario)
            assertEmptyNavigation()
            click(description(R.string.new_session))
            node { it.text?.toString() == label(R.string.select_profile) }
            description(R.string.toolbar_handle)
            assertNull("The initial screen uses the toolbar page instead of a modal profile sheet",
                find { it.isClickable && it.actionList.any { action ->
                    action.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id
                } })
            click(description(R.string.back))
            assertEmptyNavigation()
        }
    }

    private fun requireEmptyRuntime(scenario: ActivityScenario<MainActivity>) {
        var service: IRuntimeService? = null
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (service == null && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { service = runtimeField.get(it) as IRuntimeService? }
            if (service == null) SystemClock.sleep(100)
        }
        val runtime = requireNotNull(service) { "The console did not connect to its runtime" }
        assumeTrue("The production initial-screen scenario needs no existing sessions or workspaces",
            JSONArray(runtime.sessionsJson).length() == 0 &&
                WorkspaceRegistry.State.parse(runtime.workspacesJson).workspaces.isEmpty())
    }

    private fun assertEmptyNavigation() {
        node { it.text?.toString() == label(R.string.empty_terminal_title) }
        var handle: AccessibilityNodeInfo? = description(R.string.toolbar_handle)
        var performed = false
        while (handle != null && !performed) {
            handle.actionList.firstOrNull { it.label?.toString() == label(R.string.toolbar_default) }
                ?.let { performed = handle!!.performAction(it.id) }
            handle = handle.parent
        }
        assertTrue("The empty toolbar supports its default-height accessibility action", performed)
        for (id in listOf(R.string.new_session, R.string.settings)) {
            val screen = Rect().also(instrumentation.uiAutomation.rootInActiveWindow::getBoundsInScreen)
            val button = node {
                it.contentDescription?.toString() == label(id) &&
                    Rect().also(it::getBoundsInScreen).centerY() > screen.height() * 0.6f
            }
            val bounds = Rect().also(button::getBoundsInScreen)
            assertTrue("${label(id)} stays in the bottom toolbar", bounds.centerY() > screen.height() * 0.6f)
        }
        node { it.contentDescription?.toString()?.startsWith(label(R.string.workspace_selector, "")) == true }
        assertNull("An empty console exposes session navigation only", find { it.contentDescription == "CTRL: CTRL" })
    }

    private fun label(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun description(id: Int) = node { it.contentDescription?.toString() == label(id) }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.isVisibleToUser && predicate(node)) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            find(predicate)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Required empty-screen element was not visible")
    }

    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        assertTrue(requireNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
}
