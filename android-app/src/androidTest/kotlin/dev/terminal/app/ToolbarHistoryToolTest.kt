package dev.terminal.app

import android.content.ClipboardManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.emulator.TerminalSession
import dev.terminal.view.TerminalView
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
            ?: instrumentation.uiAutomation.windows.firstNotNullOfOrNull { visit(it.root) }
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
    private fun text(value: String) = node { it.text?.toString()?.lineSequence()?.any { line -> line == value } == true }
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
    private fun keyboardVisible(scenario: ActivityScenario<MainActivity>): Boolean {
        var visible = false
        scenario.onActivity { activity ->
            visible = ViewCompat.getRootWindowInsets(activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return visible
    }
    private fun awaitKeyboard(scenario: ActivityScenario<MainActivity>, visible: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            if (keyboardVisible(scenario) == visible) return
            SystemClock.sleep(50)
        }
        fail("IME visibility did not become $visible")
    }
    private fun changeTool(scenario: ActivityScenario<MainActivity>, next: Boolean) {
        var width = 0f
        var y = 0f
        scenario.onActivity { activity ->
            val decor = activity.window.decorView
            val location = IntArray(2)
            decor.getLocationOnScreen(location)
            val bottom = requireNotNull(ViewCompat.getRootWindowInsets(decor))
                .getInsets(WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.navigationBars()).bottom
            width = decor.width.toFloat()
            // Accessibility bounds can lag IME animations; swipe the toolbar above the current insets.
            y = location[1] + decor.height - bottom - 14 * context.resources.displayMetrics.density
        }
        val x = width * if (next) 0.9f else 0.1f
        val dx = width * if (next) -0.8f else 0.8f
        val start = SystemClock.uptimeMillis()
        for (i in 0..10) {
            val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; 10 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x + dx * i / 10, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, false)) } finally { event.recycle() }
            SystemClock.sleep(10)
        }
        settle()
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
    private fun withTool(mode: ToolbarSheetMode, tools: List<String> = listOf(HISTORY_TOOL),
        block: (ActivityScenario<MainActivity>, TerminalView, ByteArrayOutputStream) -> Unit) {
        val writes = ByteArrayOutputStream()
        val backend = RemoteTerminalBackend(write = { writes.write(it) }, resize = { _, _ -> })
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var view: TerminalView
                scenario.onActivity { activity ->
                    val session = TerminalSession("", "/", emptyArray(), emptyArray(), 1000, TerminalCallbacks {}, backend)
                    view = TerminalView(activity, null).apply {
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setTerminalViewClient(TerminalInputCallbacks())
                        setTextSize(48)
                        layout(0, 0, 1000, 700)
                        attachSession(session)
                    }
                    activity.setContent {
                        var sheet by remember { mutableStateOf(mode) }
                        DroidTheme {
                            Column(Modifier.fillMaxSize().imePadding()) {
                                AndroidView(factory = { view }, modifier = Modifier.weight(1f).fillMaxWidth())
                                TerminalToolbar(ToolbarConfig(tools = tools), sheet, { sheet = it },
                                    400.dp, TerminalChromeColors(Color.Black), animateModeChanges = false) { tool, active ->
                                    if (tool == HISTORY_TOOL) ToolbarHistoryTool(active, onReturnToTerminal = {
                                        view.post {
                                            if (view.isAttachedToWindow && view.hasWindowFocus() && view.requestFocus()) {
                                                WindowCompat.getInsetsController(activity.window, view).show(WindowInsetsCompat.Type.ime())
                                            }
                                        }
                                    }) { view }
                                    else androidx.compose.material3.Text("Other tool", Modifier.fillMaxSize())
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

    @Test fun switchingToolsReturnsSearchFocusToTerminalAndKeepsKeyboard() =
        withTool(ToolbarSheetMode.Default, listOf(HISTORY_TOOL, HOTKEY_TOOL)) { scenario, view, writes ->
            click(description(R.string.history_search_description))
            awaitKeyboard(scenario, true)
            query("needle")
            assertTrue(node { it.isEditable }.isFocused)
            changeTool(scenario, true)
            text("Other tool")
            scenario.onActivity { activity ->
                assertTrue("Tool changes must return input focus to the terminal", view.hasFocus())
                assertTrue(activity.getSystemService(InputMethodManager::class.java).isActive(view))
            }
            awaitKeyboard(scenario, true)
            assertEquals(0, writes.size())
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_X)
            settle()
            assertEquals("x", writes.toString("UTF-8"))
        }

    @Test fun switchingToolsAfterKeyboardDismissalDoesNotReopenIt() =
        withTool(ToolbarSheetMode.Default, listOf(HISTORY_TOOL, HOTKEY_TOOL)) { scenario, _, writes ->
            click(description(R.string.history_search_description))
            awaitKeyboard(scenario, true)
            query("needle")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitKeyboard(scenario, false)
            changeTool(scenario, true)
            text("Other tool")
            repeat(5) {
                SystemClock.sleep(100)
                assertFalse("Tool changes must not reopen a dismissed keyboard", keyboardVisible(scenario))
            }
            assertEquals(0, writes.size())
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

    @Test fun compactIconScopeSplitButtonSupportsBothSegmentsAndAllRanges() = withTool(ToolbarSheetMode.Default) { scenario, view, writes ->
        fun currentScope(id: Int) = node {
            it.contentDescription?.toString() == context.getString(R.string.history_scope, label(id))
        }
        fun bounds(node: AccessibilityNodeInfo) = Rect().also { node.getBoundsInScreen(it) }
        val fieldBounds = bounds(description(R.string.history_search_description))
        val density = context.resources.displayMetrics.density
        scenario.onActivity {
            val location = IntArray(2).also(view::getLocationOnScreen)
            val contentCenter = location[1] + view.height + (24 + 92 / 2) * density
            assertEquals("Compact controls must be centered below the handle",
                contentCenter.toDouble(), fieldBounds.exactCenterY().toDouble(), 1.0)
        }
        listOf(description(R.string.history_previous), description(R.string.history_bottom),
            currentScope(R.string.history_scope_commands), description(R.string.history_scope_choose)).forEach { control ->
            assertEquals("Arrows, search and scope controls must share a vertical center",
                fieldBounds.exactCenterY().toDouble(), bounds(control).exactCenterY().toDouble(), 1.0)
        }
        assertNull(find { it.text?.toString() == context.getString(R.string.history_count, 3) })
        val minimumTouchSize = (48 * context.resources.displayMetrics.density).toInt()
        listOf(currentScope(R.string.history_scope_commands), description(R.string.history_scope_choose)).forEach { button ->
            val bounds = Rect().also { button.getBoundsInScreen(it) }
            assertTrue("Each split segment needs a 48 dp touch target",
                bounds.width() >= minimumTouchSize && bounds.height() >= minimumTouchSize)
        }
        assertNull(find { it.text?.toString() == label(R.string.history_scope_commands) })
        query("needle")
        fun filterImage(scope: Int): Bitmap {
            val deadline = SystemClock.elapsedRealtime() + 5000
            val size = (24 * density).toInt()
            var previous: Bitmap? = null
            while (SystemClock.elapsedRealtime() < deadline) {
                val area = bounds(currentScope(scope))
                val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                val icon = try {
                    Bitmap.createBitmap(screenshot, area.centerX() - size / 2, area.centerY() - size / 2, size, size)
                } finally { screenshot.recycle() }
                val pixels = IntArray(size * size)
                icon.getPixels(pixels, 0, size, 0, 0, size, size)
                // Accessibility bounds can precede rendering and popup dismissal animations.
                // Compare the icon only after its light foreground is visible and stable.
                if (pixels.any { android.graphics.Color.luminance(it) > 0.4f } && previous?.sameAs(icon) == true) {
                    previous.recycle()
                    return icon
                }
                previous?.recycle()
                previous = icon
                SystemClock.sleep(100)
            }
            previous?.recycle()
            error("The filter icon did not finish rendering")
        }
        val originalFilter = filterImage(R.string.history_scope_commands)
        fun assertSameFilter(scope: Int) {
            val currentFilter = filterImage(scope)
            try { assertTrue("The filter icon must stay fixed across scopes", originalFilter.sameAs(currentFilter)) }
            finally { currentFilter.recycle() }
        }
        click(description(R.string.history_scope_choose))
        text(label(R.string.history_scope_commands)); text(label(R.string.history_scope_output)); text(label(R.string.history_scope_all))
        click(text(label(R.string.history_scope_output)))
        currentScope(R.string.history_scope_output)
        assertSameFilter(R.string.history_scope_output)
        assertNull(find { it.text?.toString() == label(R.string.history_scope_output) })
        click(currentScope(R.string.history_scope_output))
        click(text(label(R.string.history_scope_all)))
        currentScope(R.string.history_scope_all)
        assertSameFilter(R.string.history_scope_all)
        click(description(R.string.history_scope_choose))
        click(text(label(R.string.history_scope_commands)))
        currentScope(R.string.history_scope_commands)
        assertSameFilter(R.string.history_scope_commands)
        originalFilter.recycle()
        assertEquals(0, writes.size())
    }

    @Test fun timelineShowsExitCodesOnlyWhenKnown() = withTool(ToolbarSheetMode.Expanded) { scenario, view, _ ->
        fun row(command: String): AccessibilityNodeInfo {
            var node = text(command)
            while (node.stateDescription == null && node.parent != null) node = node.parent
            return node
        }
        fun hasCode(node: AccessibilityNodeInfo): Boolean {
            val prefix = label(R.string.history_exit_code).substringBefore('%')
            if (node.text?.toString()?.lineSequence()?.any { it.startsWith(prefix) } == true) return true
            return (0 until node.childCount).any { hasCode(node.getChild(it)) }
        }
        text(context.getString(R.string.history_exit_code, 0))
        text(context.getString(R.string.history_exit_code, 7))
        assertTrue(hasCode(row("echo needle")))
        assertTrue(hasCode(row("false")))
        assertFalse(hasCode(row("sleep 9")))
        scenario.onActivity { append(view, "\u001b]133;D\u0007") }
        settle()
        assertEquals(label(R.string.history_completed), row("sleep 9").stateDescription?.toString())
        assertFalse(hasCode(row("sleep 9")))
    }

    private fun outputToggle(command: String, expanded: Boolean = false) = node {
        it.contentDescription?.toString() == context.getString(
            if (expanded) R.string.history_output_collapse else R.string.history_output_expand, command)
    }

    private fun tapOutput(value: String) {
        val bounds = Rect().also { text(value).getBoundsInScreen(it) }
        val density = context.resources.displayMetrics.density
        val start = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
                bounds.left + 16 * density, bounds.top + 8 * density, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        settle()
    }

    @Test fun accordionTogglesWithoutJumpingAndCopiesCommand() =
        withTool(ToolbarSheetMode.Expanded) { scenario, view, writes ->
            var originalRow = 0
            scenario.onActivity { originalRow = view.topRow }
            val commandCopyBounds = Rect().also { description(R.string.history_copy_command).getBoundsInScreen(it) }
            val toggleBounds = Rect().also { outputToggle("sleep 9").getBoundsInScreen(it) }
            assertTrue(commandCopyBounds.right <= toggleBounds.left)
            assertEquals(toggleBounds.centerY().toFloat(), commandCopyBounds.centerY().toFloat(), 1f)
            click(description(R.string.history_copy_command))
            scenario.onActivity {
                assertEquals("sleep 9", it.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
                assertEquals(originalRow, view.topRow)
            }
            assertEquals(label(R.string.history_output_collapsed), outputToggle("sleep 9").stateDescription?.toString())
            click(outputToggle("sleep 9"))
            assertEquals(label(R.string.history_output_expanded), outputToggle("sleep 9", true).stateDescription?.toString())
            val outputBounds = Rect().also { text("running-output-0").getBoundsInScreen(it) }
            tapOutput("running-output-0")
            assertEquals(outputBounds, Rect().also { text("running-output-0").getBoundsInScreen(it) })
            scenario.onActivity { assertEquals(originalRow, view.topRow) }
            assertFalse(keyboardVisible(scenario))
            click(outputToggle("sleep 9", true))
            assertEquals(label(R.string.history_output_collapsed), outputToggle("sleep 9").stateDescription?.toString())
            assertNull(find { it.text?.toString()?.contains("running-output-0") == true })
            description(R.string.history_copy_command)
            click(text("false"))
            scenario.onActivity {
                assertEquals(view.mEmulator.history.markers[1].anchor.row!!.coerceAtMost(0), view.topRow)
            }
            assertEquals(0, writes.size())
        }

    @Test fun outputFieldScrollsWithoutJumpingOrOpeningKeyboard() =
        withTool(ToolbarSheetMode.Expanded) { scenario, view, writes ->
            click(outputToggle("sleep 9"))
            var originalRow = 0
            scenario.onActivity { originalRow = view.topRow }
            var scrollable = text("running-output-0")
            while (!scrollable.isScrollable && scrollable.parent != null) scrollable = scrollable.parent
            assertTrue(scrollable.isScrollable)
            assertTrue(scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            settle()
            scenario.onActivity { assertEquals(originalRow, view.topRow) }
            assertFalse(keyboardVisible(scenario))
            assertEquals(0, writes.size())
        }

    @Test fun outputLongPressSelectsTextWithoutJumpingOrOpeningKeyboard() =
        withTool(ToolbarSheetMode.Expanded) { scenario, view, writes ->
            val automation = instrumentation.uiAutomation
            val serviceInfo = automation.serviceInfo
            val previousFlags = serviceInfo.flags
            serviceInfo.flags = previousFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = serviceInfo
            try {
                click(outputToggle("sleep 9"))
                val bounds = Rect().also { text("running-output-0").getBoundsInScreen(it) }
                val density = context.resources.displayMetrics.density
                val start = SystemClock.uptimeMillis()
                fun touch(action: Int) {
                    val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
                        bounds.left + 16 * density, bounds.top + 8 * density, 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, false)) } finally { event.recycle() }
                }
                var originalRow = 0
                scenario.onActivity { originalRow = view.topRow }
                touch(MotionEvent.ACTION_DOWN)
                SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150)
                touch(MotionEvent.ACTION_UP)
                settle()
                click(text(context.getString(android.R.string.copy)))
                scenario.onActivity {
                    val copied = it.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
                    assertTrue(copied.isNotEmpty())
                    assertTrue("running-output-0".contains(copied))
                    assertEquals(originalRow, view.topRow)
                }
                assertFalse(keyboardVisible(scenario))
                assertEquals(0, writes.size())
            } finally {
                serviceInfo.flags = previousFlags
                automation.serviceInfo = serviceInfo
            }
        }

    @Test fun expandedOutputRefreshesWhileRunningAndDisappearsWithTrim() =
        withTool(ToolbarSheetMode.Expanded) { scenario, view, writes ->
            click(outputToggle("sleep 9"))
            scenario.onActivity { append(view, "streamed-tail\r\n") }
            text("streamed-tail")
            scenario.onActivity { append(view, "\u001b]133;D;0\u0007\u001b]133;A\u0007$ next prompt") }
            settle()
            val output = text("streamed-tail").text.toString()
            assertTrue(output.trimEnd().endsWith("streamed-tail"))
            assertFalse(output.contains("next prompt"))
            scenario.onActivity { append(view, (0 until 1200).joinToString("") { "trim-$it\r\n" }) }
            text(label(R.string.history_empty))
            assertNull(find { it.text?.toString()?.contains("running-output-0") == true })
            scenario.onActivity { append(view, "\u001b]133;A\u0007$ \u001b]133;B\u0007true\r\n\u001b]133;C\u0007\u001b]133;D;0\u0007") }
            click(outputToggle("true"))
            text(label(R.string.history_output_empty))
            assertEquals(0, writes.size())
        }

    @Test fun expandedTimelineStaysCompleteWhileSearchMovesThroughTerminalCells() = withTool(ToolbarSheetMode.Expanded) { scenario, view, writes ->
        text("echo needle"); text("false"); text("sleep 9")
        assertNull(find { it.text?.toString() == context.getString(R.string.history_count, 3) })
        node { it.stateDescription?.toString() == label(R.string.history_running) }
        node { it.stateDescription?.toString() == context.getString(R.string.history_failed, 7) }
        node { it.stateDescription?.toString() == context.getString(R.string.history_completed_code, 0) }
        click(text("false"))
        scenario.onActivity { assertEquals(view.mEmulator.history.markers[1].anchor.row!!.coerceAtMost(0), view.topRow) }
        query("needle")
        text(context.getString(R.string.history_search_count, 1, 1))
        text("echo needle"); text("false"); text("sleep 9")
        scenario.onActivity {
            assertEquals(1, view.historySearchMatches.size)
            assertEquals(7, view.currentHistorySearchMatch!!.startColumn)
        }
        scope(R.string.history_scope_output)
        text("echo needle"); text("false"); text("sleep 9")
        scenario.onActivity {
            assertEquals(40, view.historySearchMatches.size)
            val hit = view.currentHistorySearchMatch!!
            assertTrue(hit.startRow > view.mEmulator.history.markers[1].anchor.row!!)
            assertEquals(hit.startRow.coerceAtMost(0), view.topRow)
        }
        scope(R.string.history_scope_all)
        text("false"); text("echo needle"); text("sleep 9")
        scenario.onActivity { assertEquals(41, view.historySearchMatches.size) }
        query("unmatched")
        text(label(R.string.history_no_results))
        text("false"); text("echo needle"); text("sleep 9")
        scenario.onActivity { assertTrue(view.historySearchMatches.isEmpty()) }
        click(text("false"))
        scenario.onActivity { assertEquals(view.mEmulator.history.markers[1].anchor.row!!.coerceAtMost(0), view.topRow) }
        assertEquals(0, writes.size())
    }

    @Test fun compactSearchNavigatesIndividualOutputOccurrencesAndReturnsToCommandNavigation() = withTool(ToolbarSheetMode.Default) { scenario, view, writes ->
        query("needle-output")
        text(label(R.string.history_no_results))
        scope(R.string.history_scope_output)
        scenario.onActivity { assertEquals(39, view.historySearchIndex) }
        click(description(R.string.history_search_previous))
        scenario.onActivity {
            assertEquals(38, view.historySearchIndex)
            assertEquals(view.currentHistorySearchMatch!!.startRow.coerceAtMost(0), view.topRow)
        }
        click(description(R.string.history_search_next))
        scenario.onActivity { assertEquals(39, view.historySearchIndex) }
        click(description(R.string.history_search_next))
        scenario.onActivity { assertEquals(0, view.historySearchIndex) }
        query("")
        description(R.string.history_previous)
        scenario.onActivity { assertTrue(view.historySearchMatches.isEmpty()) }
        assertEquals(0, writes.size())
    }

    @Test fun legacyBashCommandScopeFindsCommandsAfterLongOutput() = withTool(ToolbarSheetMode.Expanded) { scenario, view, writes ->
        scenario.onActivity {
            listOf("ls -al", "ls -al /usr/bin").forEach { command ->
                append(view, "\r\n\u001b]133;A\u0007root@ubuntu:~# $command\r\n\u001b]133;C\u0007")
                repeat(60) { append(view, "listed-file-$it\r\n") }
                append(view, "\u001b]133;D;0\u0007")
            }
        }
        settle()
        text("ls -al"); text("ls -al /usr/bin")
        query("ls")
        // "false" from the fixture also contains "ls".
        text(context.getString(R.string.history_search_count, 3, 3))
        scenario.onActivity {
            assertEquals(3, view.historySearchMatches.size)
            view.historySearchMatches.takeLast(2).forEach { hit ->
                assertTrue(hit.startRow < 0)
                assertEquals("ls", view.mEmulator.screen.getSelectedText(
                    hit.startColumn, hit.startRow, hit.endColumn - 1, hit.endRow))
            }
        }
        click(description(R.string.history_search_previous))
        scenario.onActivity {
            assertEquals(1, view.historySearchIndex)
            assertEquals(view.currentHistorySearchMatch!!.startRow, view.topRow)
        }
        scope(R.string.history_scope_output)
        text(label(R.string.history_no_results))
        scenario.onActivity { assertTrue(view.historySearchMatches.isEmpty()) }
        scope(R.string.history_scope_commands)
        text(context.getString(R.string.history_search_count, 3, 3))
        assertEquals(0, writes.size())
    }

    @Test fun ordinaryOutputIsSearchableWithoutAnyCommandMarkers() = withTool(ToolbarSheetMode.Default) { scenario, view, writes ->
        val other = RemoteTerminalBackend(write = { writes.write(it) }, resize = { _, _ -> })
        try {
            scenario.onActivity {
                view.attachSession(TerminalSession("", "/", emptyArray(), emptyArray(), 1000, TerminalCallbacks {}, other))
                append(view, "unmarked needle needle\r\n")
                repeat(60) { append(view, "ordinary-output-$it\r\n") }
            }
            scope(R.string.history_scope_output)
            query("needle")
            scenario.onActivity {
                assertTrue(view.mEmulator.history.markers.isEmpty())
                assertEquals(2, view.historySearchMatches.size)
                assertEquals(16, view.currentHistorySearchMatch!!.startColumn)
                assertTrue(view.topRow < 0)
            }
            click(description(R.string.history_search_previous))
            scenario.onActivity { assertEquals(9, view.currentHistorySearchMatch!!.startColumn) }
            assertEquals(0, writes.size())
        } finally { other.close() }
    }

    @Test fun keyboardResizeKeepsTheSelectedTerminalOccurrenceVisible() = withTool(ToolbarSheetMode.Default) { scenario, view, writes ->
        scope(R.string.history_scope_output)
        query("needle-output-39")
        scenario.onActivity {
            val width = view.width
            val height = view.height
            view.layout(0, 0, width, height + 2400)
            view.updateSize()
            assertTrue("The occurrence starts on the taller live screen", view.currentHistorySearchMatch!!.startRow >= 0)
            view.layout(0, 0, width, 300)
            view.updateSize()
            val hit = view.currentHistorySearchMatch!!
            assertTrue("The occurrence enters scrollback when the keyboard returns", hit.startRow < 0)
            assertEquals(hit.startRow, view.topRow)
            assertEquals("needle-output-39", view.mEmulator.screen.getSelectedText(
                hit.startColumn, hit.startRow, hit.endColumn - 1, hit.endRow))
            view.layout(0, 0, width, height)
            view.updateSize()
        }
        assertEquals(0, writes.size())
    }

    @Test fun searchPaintsTerminalCellsAndClearsWhenTheToolLeaves() = withTool(ToolbarSheetMode.Default) { scenario, view, writes ->
        query("needle")
        scenario.onActivity { activity ->
            val emulator = view.mEmulator
            val cells = emulator.screen.transcriptText
            val highlighted = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val plain = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(highlighted))
            view.clearHistorySearch()
            view.draw(Canvas(plain))
            assertFalse("Search must visibly highlight the terminal canvas", highlighted.sameAs(plain))
            assertSame(emulator, view.mEmulator)
            assertEquals(cells, emulator.screen.transcriptText)
            highlighted.recycle(); plain.recycle()
            view.updateHistorySearch("needle", dev.terminal.emulator.TerminalSearch.Scope.COMMANDS)
            activity.setContent { Box(Modifier.fillMaxSize()) }
        }
        settle()
        scenario.onActivity {
            assertTrue(view.historySearchMatches.isEmpty())
            assertNotNull(view.mEmulator)
        }
        assertEquals(0, writes.size())
    }

    @Test fun ownerResizeKeepsSearchVisibleWithoutPullingManualBrowsingBack() = withTool(ToolbarSheetMode.Default) { scenario, view, writes ->
        scope(R.string.history_scope_output)
        query("needle-output-39")
        scenario.onActivity {
            val columns = view.mEmulator.mColumns
            // Mirror the owner's resize event, which arrives after applySize has returned.
            view.mEmulator.resize(columns, 80, 1, 1)
            view.onScreenUpdated()
            assertTrue(view.currentHistorySearchMatch!!.startRow >= 0)
            view.mEmulator.resize(columns, 4, 1, 1)
            view.onScreenUpdated()
            assertEquals(view.currentHistorySearchMatch!!.startRow, view.topRow)
            assertTrue(view.topRow < 0)
            view.jumpToHistoryRow(-view.mEmulator.screen.activeTranscriptRows)
            view.mEmulator.resize(columns, 8, 1, 1)
            view.onScreenUpdated()
            assertEquals("A manually browsed viewport must stay at the oldest retained line",
                -view.mEmulator.screen.activeTranscriptRows, view.topRow)
        }
        assertEquals(0, writes.size())
    }

    @Test fun applicationViewportBlocksJumpsAndMainHistoryReturnsIntact() = withTool(ToolbarSheetMode.Expanded) { scenario, view, writes ->
        query("needle")
        scenario.onActivity {
            append(view, "\u001b[?1049h\u001b]133;C\u0007alternate\u001b]133;D;9\u0007")
            writes.reset()
            assertFalse(view.jumpToCommand(view.mEmulator.history.markers.first()))
            assertFalse(view.jumpToHistoryRow(-50))
            assertFalse(view.moveHistorySearchMatch(true))
            assertEquals(0, view.topRow)
            assertFalse(view.isVerticalScrollBarEnabled)
        }
        settle()
        assertHistoryActionsAvailable(scenario, view, writes)
        var button: AccessibilityNodeInfo? = description(R.string.history_search_previous)
        var disabled = false
        while (button != null) {
            disabled = disabled || !button.isEnabled
            button = button.parent
        }
        assertTrue("The navigation button must expose disabled semantics", disabled)
        scenario.onActivity { assertTrue(view.historySearchMatches.isEmpty()) }
        assertEquals(0, writes.size())
        scenario.onActivity { append(view, "\u001b[?1049l") }
        settle()
        query("")
        click(text("echo needle"))
        scenario.onActivity {
            assertTrue(view.topRow < 0)
            assertEquals(3, view.mEmulator.history.markers.size)
        }
    }

    @Test fun mainScreenApplicationModesAllowCopyAndOutputWithoutNavigation() =
        withTool(ToolbarSheetMode.Expanded) { scenario, view, writes ->
            for ((enter, leave) in listOf(
                "\u001b[?1000h" to "\u001b[?1000l",
                "\u001b[?1h" to "\u001b[?1l",
                "\u001b[2;4r" to "\u001b[r",
            )) {
                scenario.onActivity {
                    append(view, enter)
                    writes.reset()
                    assertTrue(view.mEmulator.isHistoryViewportOwnedByApplication)
                    assertFalse(view.jumpToCommand(view.mEmulator.history.markers.first()))
                }
                settle()
                assertHistoryActionsAvailable(scenario, view, writes)
                query("needle")
                assertHistoryActionsAvailable(scenario, view, writes)
                scenario.onActivity { append(view, leave) }
                settle()
                query("")
                click(text("false"))
                scenario.onActivity {
                    assertFalse(view.mEmulator.isHistoryViewportOwnedByApplication)
                    assertEquals(view.mEmulator.history.markers[1].anchor.row!!.coerceAtMost(0), view.topRow)
                }
            }
        }

    private fun assertHistoryActionsAvailable(scenario: ActivityScenario<MainActivity>, view: TerminalView,
        writes: ByteArrayOutputStream) {
        assertNull(find { it.text?.toString()?.let { value ->
            value.contains("제어하는 동안 이동") || value.contains("controls the viewport")
        } == true })
        assertNull(find { it.text?.toString() == label(R.string.history_no_results) })
        var commandRow = text("sleep 9")
        while (commandRow.stateDescription == null && commandRow.parent != null) commandRow = commandRow.parent
        assertFalse("Command navigation must stay disabled", commandRow.isEnabled)
        var originalRow = 0
        var originalScreen = ""
        scenario.onActivity {
            originalRow = view.topRow
            originalScreen = view.mEmulator.screen.transcriptText
        }
        fun clickEnabled(node: AccessibilityNodeInfo) {
            var button = node
            while (!button.isClickable && button.parent != null) button = button.parent
            assertTrue("History action must expose enabled semantics", button.isEnabled)
            click(button)
        }
        clickEnabled(description(R.string.history_copy_command))
        scenario.onActivity {
            assertEquals("sleep 9", it.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        }
        clickEnabled(outputToggle("sleep 9"))
        text("running-output-0")
        assertEquals(label(R.string.history_output_expanded), outputToggle("sleep 9", true).stateDescription?.toString())
        clickEnabled(outputToggle("sleep 9", true))
        assertNull(find { it.text?.toString()?.contains("running-output-0") == true })
        scenario.onActivity {
            assertEquals(originalRow, view.topRow)
            assertEquals(originalScreen, view.mEmulator.screen.transcriptText)
        }
        assertFalse(keyboardVisible(scenario))
        assertEquals(0, writes.size())
    }

    @Test fun reattachingAnotherSessionReplacesTheHistoryWithoutStartingOrWritingIt() = withTool(ToolbarSheetMode.Expanded) { scenario, view, writes ->
        query("needle")
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
            scenario.onActivity { assertTrue(view.historySearchMatches.isEmpty()) }
            assertNull(find { it.text?.toString() == "echo needle" })
            scenario.onActivity { view.attachSession(original) }
            settle()
            text("echo needle")
            scenario.onActivity { assertEquals(1, view.historySearchMatches.size) }
            assertNull(find { it.text?.toString() == "another-session" })
            assertEquals(0, writes.size())
        } finally { other.close() }
    }
}
