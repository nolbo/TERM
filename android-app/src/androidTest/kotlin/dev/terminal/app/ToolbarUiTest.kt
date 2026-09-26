package dev.terminal.app

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolbarUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun label(id: Int) = context.getString(id)
    private fun settle() { SystemClock.sleep(1500); instrumentation.waitForIdleSync() }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && predicate(node)) return node
            for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun waitForNode(message: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            find(predicate)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing $message; " + dump())
    }
    private fun text(value: String) = waitForNode("text: $value") { it.text?.toString() == value }
    private fun description(value: String) = waitForNode("description: $value") { it.contentDescription?.toString() == value }
    private fun dump(): String {
        val values = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.text != null || node.contentDescription != null) values += "[${node.text}|${node.contentDescription}|${node.isVisibleToUser}]"
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(instrumentation.uiAutomation.rootInActiveWindow)
        return values.joinToString().take(4000)
    }
    private fun click(node: AccessibilityNodeInfo) {
        val rect = bounds(node)
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, rect.exactCenterX(), rect.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        settle()
    }
    private fun drag(x: Float, y: Float, dx: Float, dy: Float) {
        val start = SystemClock.uptimeMillis()
        for (i in 0..20) {
            val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; 20 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x + dx * i / 20, y + dy * i / 20, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            SystemClock.sleep(20)
        }
        settle()
    }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also { node.getBoundsInScreen(it) }
    private fun handle() = description(label(R.string.toolbar_handle))
    private fun action(label: String) {
        val node = handle()
        var current: AccessibilityNodeInfo? = node
        var performed = false
        while (current != null && !performed) {
            val action = current.actionList.firstOrNull { it.label?.toString() == label }
            if (action != null) performed = current.performAction(action.id)
            current = current.parent
        }
        assertTrue("Missing action $label: " + dump(), performed)
        settle()
    }

    @Test fun sheetDragPagingHiddenHandleAndDisabledState() {
        val mode = mutableStateOf(ToolbarSheetMode.Default)
        val config = mutableStateOf(ToolbarConfig(tools = listOf("first", "second")))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) { Text("Terminal canvas") }
                        TerminalToolbar(config.value, mode.value, { mode.value = it }, 400.dp, TerminalChromeColors(Color.Black)) { id, _ ->
                            Text("$id page", Modifier.fillMaxSize())
                        }
                    }
                } }
            } }
            settle()
            assertEquals(ToolbarSheetMode.Default, mode.value)
            handle()
            val page = bounds(text("first page"))
            drag(page.exactCenterX() + page.width() * 0.3f, page.exactCenterY(), -page.width() * 0.65f, 0f)
            text("second page")
            val initial = bounds(handle())
            drag(initial.exactCenterX(), initial.exactCenterY(), 0f, -800f)
            assertEquals(ToolbarSheetMode.Expanded, mode.value)
            val expandedHandle = bounds(handle())
            drag(expandedHandle.exactCenterX(), expandedHandle.exactCenterY(), 0f, 950f)
            assertEquals(ToolbarSheetMode.Hidden, mode.value)
            assertNull(find { it.text?.toString() == "second page" })
            click(handle())
            assertEquals(ToolbarSheetMode.Default, mode.value)
            text("second page")
            action(label(R.string.toolbar_expand))
            assertEquals(ToolbarSheetMode.Expanded, mode.value)
            action(label(R.string.toolbar_default))
            assertEquals(ToolbarSheetMode.Default, mode.value)
            // Stable tool IDs preserve selection across reordering.
            scenario.onActivity { config.value = config.value.copy(tools = listOf("second", "first")) }
            settle()
            text("second page")
            scenario.onActivity { config.value = config.value.copy(tools = emptyList()) }
            settle()
            text(label(R.string.toolbar_empty))
            scenario.onActivity { config.value = config.value.copy(enabled = false) }
            settle()
            assertNull(find { it.contentDescription?.toString() == label(R.string.toolbar_handle) })
        }
    }

    @Test fun commandSendRequiresConfirmationAndCancelsWhenInactive() {
        var sent: Pair<String, Boolean>? = null
        val active = mutableStateOf(true)
        val commands = listOf(ToolbarCommand("sample", "Sample", "echo first\necho second"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { DroidTheme { TerminalHapticFeedbackProvider {
                    ToolbarCommandsTool(commands, active.value, "Test session", {}, { text, execute -> sent = text to execute })
                } } }
            }
            settle()
            click(text(label(R.string.toolbar_send)))
            text("echo first\necho second")
            text(context.getString(R.string.toolbar_send_target, "Test session"))
            assertNull(sent)
            click(text(label(R.string.toolbar_run)))
            assertEquals(commands.single().text to true, sent)
            scenario.onActivity { sent = null }
            click(text(label(R.string.toolbar_send)))
            scenario.onActivity { active.value = false }
            settle()
            assertNull(find { it.text?.toString() == label(R.string.toolbar_run) })
            assertNull(sent)
        }
    }

    @Test fun settingsEditAddRemoveReorderAndNavigate() {
        val prefs = context.getSharedPreferences("toolbar-ui-test", 0)
        prefs.edit().clear().putString(KEY_TOOLBAR, serializeToolbarConfig(ToolbarConfig(tools = listOf(HOTKEY_TOOL, "clipboard")))).commit()
        var opened: String? = null
        val registry = toolbarTools + ToolbarTool("second", R.string.diagnostics_title, "fixture")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    DroidTheme { TerminalHapticFeedbackProvider {
                        ToolbarSettings(prefs, {}, registry) { opened = it }
                    } }
                } }
                settle()
                assertNull(find { it.text?.toString() == label(R.string.diagnostics_title) })
                click(description(label(R.string.toolbar_edit)))
                text(label(R.string.diagnostics_title))
                assertNull(find { it.contentDescription?.toString() == context.getString(R.string.toolbar_move, label(R.string.diagnostics_title)) })
                click(description(context.getString(R.string.toolbar_add, label(R.string.diagnostics_title))))
                val first = bounds(description(context.getString(R.string.toolbar_move, label(R.string.settings_hotkeys_title))))
                val second = bounds(description(context.getString(R.string.toolbar_move, label(R.string.diagnostics_title))))
                drag(first.exactCenterX(), first.exactCenterY(), 0f, second.exactCenterY() - first.exactCenterY())
                assertEquals(listOf("second", HOTKEY_TOOL), parseToolbarConfig(prefs.getString(KEY_TOOLBAR, null), available = registry.map { it.id }.toSet()).tools)
                click(description(context.getString(R.string.toolbar_remove, label(R.string.settings_hotkeys_title))))
                assertNull(find { it.contentDescription?.toString() == context.getString(R.string.toolbar_move, label(R.string.settings_hotkeys_title)) })
                click(description(label(R.string.toolbar_done)))
                assertNull(find { it.text?.toString() == label(R.string.settings_hotkeys_title) })
                click(text(label(R.string.diagnostics_title)))
                assertEquals("fixture", opened)
            }
        } finally { prefs.edit().clear().commit() }
    }
}
