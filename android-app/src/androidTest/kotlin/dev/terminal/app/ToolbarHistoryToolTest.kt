package dev.terminal.app

import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import dev.terminal.app.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class ToolbarHistoryToolTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun label(id: Int) = context.getString(id)
    private fun settle() { SystemClock.sleep(450); instrumentation.waitForIdleSync() }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && predicate(node)) return node
            repeat(node.childCount) { visit(node.getChild(it))?.let { result -> return result } }
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
        error("Missing command history UI element")
    }
    private fun description(id: Int) = node { it.contentDescription?.toString() == label(id) }
    private fun text(value: String) = node { it.text?.toString() == value }
    private fun click(original: AccessibilityNodeInfo) {
        var target = original
        while (!target.isClickable && target.parent != null) target = target.parent
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        settle()
    }
    private fun query(value: String) {
        val field = node { it.isEditable }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        settle()
    }
    private fun scope(id: Int) {
        click(node { it.contentDescription?.toString()?.startsWith(label(R.string.history_scope).substringBefore('%')) == true })
        click(text(label(id)))
    }
    private fun append(view: TerminalView, value: String) {
        value.toByteArray().let { view.mEmulator.append(it, it.size) }
        view.onScreenUpdated()
    }
    private fun fixture(view: TerminalView) {
        repeat(5) { append(view, "startup-$it\r\n") }
        fun command(name: String, output: String, code: Int?) {
            append(view, "\u001b]133;A\u0007$ \u001b]133;B\u0007$name\r\n\u001b]133;C\u0007")
            repeat(40) { append(view, "$output-$it\r\n") }
            if (code != null) append(view, "\u001b]133;D;$code\u0007")
        }
        command("echo needle", "alpha-output", 0)
        command("false", "needle-output", 7)
        command("sleep 9", "running-output", null)
    }
    private fun withTool(mode: ToolbarSheetMode, block: (ActivityScenario<MainActivity>, TerminalView, ByteArrayOutputStream) -> Unit) {
        val writes = ByteArrayOutputStream()
        val backend = RemoteTerminalBackend(write = { writes.write(it) }, resize = { _, _ -> })
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var view: TerminalView
                scenario.onActivity { activity ->
                    val session = TerminalSession("", "/", emptyArray(), emptyArray(), 1000, TerminalCallbacks {}, backend)
                    view = TerminalView(activity, null).apply {
                        setTerminalViewClient(TerminalInputCallbacks())
                        setTextSize(48)
                        layout(0, 0, 1000, 700)
                        attachSession(session)
                    }
                    activity.setContent {
                        var sheet by remember { mutableStateOf(mode) }
                        DroidTheme {
                            Column(Modifier.fillMaxSize()) {
                                AndroidView(factory = { view }, modifier = Modifier.weight(1f).fillMaxWidth())
                                TerminalToolbar(ToolbarConfig(tools = listOf(HISTORY_TOOL)), sheet, { sheet = it },
                                    400.dp, TerminalChromeColors(Color.Black), animateModeChanges = false) { _, active ->
                                    ToolbarHistoryTool(active) { view }
                                }
                            }
                        }
                    }
                }
                settle()
                scenario.onActivity { fixture(view) }
                settle()
                block(scenario, view, writes)
            }
        } finally { backend.close() }
    }

    @Test fun compactArrowsWalkCommandsThenBecomeTopAndBottomActions() = withTool(ToolbarSheetMode.Default) { scenario, view, _ ->
        description(R.string.history_search_description)
        assertNull(find { it.text?.toString() == "echo needle" })
        click(description(R.string.history_previous))
        scenario.onActivity { assertEquals(view.mEmulator.history.markers.last().anchor.row!!.coerceAtMost(0), view.topRow) }
        description(R.string.history_bottom)
        click(description(R.string.history_previous))
        click(description(R.string.history_previous))
        scenario.onActivity { assertEquals(view.mEmulator.history.markers.first().anchor.row!!.coerceAtMost(0), view.topRow) }
        click(description(R.string.history_top))
        scenario.onActivity { assertEquals(-view.mEmulator.screen.activeTranscriptRows, view.topRow) }
        click(description(R.string.history_next))
        click(description(R.string.history_next))
        click(description(R.string.history_next))
        click(description(R.string.history_bottom))
        scenario.onActivity { assertEquals(0, view.topRow) }
    }

    @Test fun compactIconScopeSplitButtonSupportsBothSegmentsAndAllRanges() = withTool(ToolbarSheetMode.Default) { _, _, writes ->
        fun currentScope(id: Int) = node {
            it.contentDescription?.toString() == context.getString(R.string.history_scope, label(id))
        }
        val minimumTouchSize = (48 * context.resources.displayMetrics.density).toInt()
        listOf(currentScope(R.string.history_scope_commands), description(R.string.history_scope_choose)).forEach { button ->
            val bounds = Rect().also { button.getBoundsInScreen(it) }
            assertTrue("Each split segment needs a 48 dp touch target",
                bounds.width() >= minimumTouchSize && bounds.height() >= minimumTouchSize)
        }
        assertNull(find { it.text?.toString() == label(R.string.history_scope_commands) })
        query("needle")
        click(description(R.string.history_scope_choose))
        text(label(R.string.history_scope_commands)); text(label(R.string.history_scope_output)); text(label(R.string.history_scope_all))
        click(text(label(R.string.history_scope_output)))
        currentScope(R.string.history_scope_output)
        assertNull(find { it.text?.toString() == label(R.string.history_scope_output) })
        click(currentScope(R.string.history_scope_output))
        click(text(label(R.string.history_scope_all)))
        currentScope(R.string.history_scope_all)
        click(description(R.string.history_scope_choose))
        click(text(label(R.string.history_scope_commands)))
        currentScope(R.string.history_scope_commands)
        assertEquals(0, writes.size())
    }

    @Test fun expandedTimelineExposesStatusAndFiltersEachSearchScope() = withTool(ToolbarSheetMode.Expanded) { scenario, view, _ ->
        text("echo needle"); text("false"); text("sleep 9")
        node { it.stateDescription?.toString() == label(R.string.history_running) }
        node { it.stateDescription?.toString() == context.getString(R.string.history_failed, 7) }
        node { it.stateDescription?.toString() == context.getString(R.string.history_completed_code, 0) }
        click(text("false"))
        scenario.onActivity { assertEquals(view.mEmulator.history.markers[1].anchor.row!!.coerceAtMost(0), view.topRow) }
        query("needle")
        text("echo needle")
        assertNull(find { it.text?.toString() == "false" })
        scope(R.string.history_scope_output)
        text("false")
        assertNull(find { it.text?.toString() == "echo needle" })
        scope(R.string.history_scope_all)
        text("false"); text("echo needle")
        query("unmatched")
        text(label(R.string.history_no_results))
        assertNull(find { it.text?.toString() == "false" })
    }

    @Test fun applicationViewportBlocksJumpsAndMainHistoryReturnsIntact() = withTool(ToolbarSheetMode.Expanded) { scenario, view, writes ->
        scenario.onActivity {
            append(view, "\u001b[?1049h\u001b]133;C\u0007alternate\u001b]133;D;9\u0007")
            writes.reset()
            assertFalse(view.jumpToCommand(view.mEmulator.history.markers.first()))
            assertFalse(view.jumpToHistoryRow(-50))
            assertEquals(0, view.topRow)
            assertFalse(view.isVerticalScrollBarEnabled)
        }
        settle()
        text(label(R.string.history_application_viewport))
        var button: AccessibilityNodeInfo? = description(R.string.history_previous)
        var disabled = false
        while (button != null) {
            disabled = disabled || !button.isEnabled
            button = button.parent
        }
        assertTrue("The navigation button must expose disabled semantics", disabled)
        assertEquals(0, writes.size())
        scenario.onActivity { append(view, "\u001b[?1049l") }
        settle()
        click(text("echo needle"))
        scenario.onActivity {
            assertTrue(view.topRow < 0)
            assertEquals(3, view.mEmulator.history.markers.size)
        }
    }

    @Test fun reattachingAnotherSessionReplacesTheHistoryWithoutStartingOrWritingIt() = withTool(ToolbarSheetMode.Expanded) { scenario, view, writes ->
        val other = RemoteTerminalBackend(write = { writes.write(it) }, resize = { _, _ -> })
        try {
            lateinit var original: TerminalSession
            scenario.onActivity {
                original = view.currentSession
                view.attachSession(TerminalSession("", "/", emptyArray(), emptyArray(), 1000, TerminalCallbacks {}, other))
                append(view, "\u001b]133;A\u0007$ \u001b]133;B\u0007another-session\r\n\u001b]133;C\u0007\u001b]133;D;0\u0007")
                writes.reset()
            }
            settle()
            text("another-session")
            assertNull(find { it.text?.toString() == "echo needle" })
            scenario.onActivity { view.attachSession(original) }
            settle()
            text("echo needle")
            assertNull(find { it.text?.toString() == "another-session" })
            assertEquals(0, writes.size())
        } finally { other.close() }
    }
}
