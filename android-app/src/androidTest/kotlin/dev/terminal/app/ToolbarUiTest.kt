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
import androidx.compose.ui.graphics.toArgb
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
    private fun drag(x: Float, y: Float, dx: Float, dy: Float, cancel: Boolean = false, duringDrag: (() -> Unit)? = null) {
        val start = SystemClock.uptimeMillis()
        for (i in 0..20) {
            val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; 20 -> if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x + dx * i / 20, y + dy * i / 20, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            if (i == 10 && duringDrag != null) {
                SystemClock.sleep(250)
                duringDrag()
            }
            SystemClock.sleep(20)
        }
        settle()
    }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also { node.getBoundsInScreen(it) }
    private fun handle() = description(label(R.string.toolbar_handle))

    private fun assertNoHandleHover(node: AccessibilityNodeInfo) {
        val rect = bounds(node)
        val density = context.resources.displayMetrics.density
        // The mouse cursor stays to the right of the sampled region.
        val mouseX = rect.exactCenterX() + 14 * density
        val sample = Rect(
            maxOf(rect.left, (rect.exactCenterX() - 16 * density).toInt()), rect.top,
            (mouseX - 8 * density).toInt(), rect.bottom,
        )
        fun pixels(): IntArray {
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            return IntArray(sample.width() * sample.height()).also {
                bitmap.getPixels(it, 0, sample.width(), sample.left, sample.top, sample.width(), sample.height())
                bitmap.recycle()
            }
        }
        fun hover(action: Int, y: Float) {
            val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
            val coordinates = MotionEvent.PointerCoords().apply { x = mouseX; this.y = y }
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, action, 1, arrayOf(properties), arrayOf(coordinates),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        for (y in listOf(rect.exactCenterY(), rect.top + 2 * density)) {
            val before = pixels()
            hover(MotionEvent.ACTION_HOVER_ENTER, y)
            try {
                hover(MotionEvent.ACTION_HOVER_MOVE, y)
                SystemClock.sleep(250)
                instrumentation.waitForIdleSync()
                assertArrayEquals("Hover must not change the handle or its hit area", before, pixels())
            } finally { hover(MotionEvent.ACTION_HOVER_EXIT, y) }
            SystemClock.sleep(400)
        }
    }
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
            assertNoHandleHover(handle())
            val page = bounds(text("first page"))
            val indicatorLabels = (1..2).map { context.getString(R.string.toolbar_page_position, it, 2) }
            assertNull(find { it.contentDescription?.toString() in indicatorLabels })
            drag(page.exactCenterX() + page.width() * 0.3f, page.exactCenterY(), -page.width() * 0.65f, 0f) {
                waitForNode("page dots during swipe") { it.contentDescription?.toString() in indicatorLabels }
            }
            assertEquals(page.height(), bounds(text("second page")).height())
            assertNull(find { it.contentDescription?.toString() in indicatorLabels })
            val initial = bounds(handle())
            drag(initial.exactCenterX(), initial.exactCenterY(), 0f, -800f)
            assertEquals(ToolbarSheetMode.Expanded, mode.value)
            val expandedHandle = bounds(handle())
            assertNoHandleHover(handle())
            drag(expandedHandle.exactCenterX(), expandedHandle.exactCenterY(), 0f, 950f)
            assertEquals(ToolbarSheetMode.Hidden, mode.value)
            assertNull(find { it.text?.toString() == "second page" })
            assertNoHandleHover(handle())
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

    @Test fun newSessionHandleHasNoHoverIndicationAndStillDismissesByDragging() {
        val visible = mutableStateOf(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    Text("Session sheet fixture")
                    if (visible.value) NewSessionSheet(SessionProfiles(context).builtIns(), {}, {}, { visible.value = false })
                } }
            } }
            settle()
            val handle = waitForNode("modal sheet handle") { node ->
                node.isClickable && node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id }
            }
            assertNoHandleHover(handle)
            val rect = bounds(handle)
            val screen = Rect().also { instrumentation.uiAutomation.rootInActiveWindow.getBoundsInScreen(it) }
            val endY = screen.bottom - 32 * context.resources.displayMetrics.density
            drag(rect.exactCenterX(), rect.exactCenterY(), 0f, endY - rect.exactCenterY())
            assertFalse("Hover must preserve drag dismissal: handle=$rect screen=$screen", visible.value)
            assertNull(find { node -> node.isClickable && node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id } })
        }
    }

    @Test fun hotkeysUseToolbarHeightAndScrollOverflow() {
        val layout = HotkeyLayout(columns = 2, keys = (1..12).map { Hotkey("Key $it", "F$it") })
        val mode = mutableStateOf(ToolbarSheetMode.Default)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.weight(1f))
                        TerminalToolbar(ToolbarConfig(), mode.value, { mode.value = it },
                            240.dp, TerminalChromeColors(Color.Black)) { _, active ->
                            ToolbarHotkeys(layout, false, false, TerminalChromeColors(Color.Black), active, {})
                        }
                    }
                } }
            } }
            settle()
            val first = bounds(description("Key 1: F1"))
            val density = context.resources.displayMetrics.density
            assertEquals(40f * density, first.height().toFloat(), 1f)
            assertEquals(92f * density, bounds(waitForNode("default hotkey viewport") { it.isScrollable }).height().toFloat(), 1f)
            description("Key 4: F4")
            assertNull(find { it.contentDescription?.toString() == "Key 5: F5" && !bounds(it).isEmpty })
            val lastVisible = bounds(description("Key 4: F4"))
            drag(first.exactCenterX(), lastVisible.exactCenterY(), 0f, -64f * density)
            waitForNode("third row after scrolling") { it.contentDescription?.toString() == "Key 5: F5" && !bounds(it).isEmpty }
            assertEquals(40f * density, bounds(description("Key 6: F6")).height().toFloat(), 1f)

            action(label(R.string.toolbar_expand))
            assertEquals(ToolbarSheetMode.Expanded, mode.value)
            val expanded = bounds(waitForNode("expanded hotkey viewport") { it.isScrollable })
            assertEquals(216f * density, expanded.height().toFloat(), 1f)
            val fourthRow = bounds(description("Key 8: F8"))
            assertEquals(40f * density, fourthRow.height().toFloat(), 1f)
            assertTrue("Expanded hotkeys use the space below two rows", fourthRow.bottom > expanded.top + 92 * density)
            drag(expanded.exactCenterX(), expanded.bottom - 8 * density, 0f, -64f * density)
            assertEquals(40f * density, bounds(description("Key 12: F12")).height().toFloat(), 1f)

            action(label(R.string.toolbar_default))
            assertEquals(ToolbarSheetMode.Default, mode.value)
            assertEquals(92f * density, bounds(waitForNode("collapsed hotkey viewport") { it.isScrollable }).height().toFloat(), 1f)
            assertNull(find { it.contentDescription?.toString() == "Key 12: F12" && !bounds(it).isEmpty })
        }
    }

    @Test fun defaultMemoryToolbarUsesTwoKeyRowsAndScrollsToSession() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.weight(1f))
                        TerminalToolbar(ToolbarConfig(tools = listOf(MEMORY_TOOL)), ToolbarSheetMode.Default,
                            {}, 240.dp, TerminalChromeColors(Color.Black)) { _, active ->
                            ToolbarMemoryTool(active, listOf(SessionUi("one", "NATIVE", "Visible session", "RUNNING")),
                                30, {}, sections = MemoryToolSections(app = false)) { """{"totalBytes":1048576,"availableBytes":524288,"sessions":{"one":1024}}""" }
                        }
                    }
                } }
            } }
            settle()
            assertNull(find { it.text?.toString() == label(R.string.toolbar_memory) })
            text(label(R.string.toolbar_memory_device))
            val density = context.resources.displayMetrics.density
            val handleBottom = bounds(handle()).bottom
            val viewport = waitForNode("memory scroll viewport") { it.isScrollable }
            assertEquals(92f * density, bounds(viewport).height().toFloat(), 1f)
            assertTrue(bounds(viewport).bottom <= handleBottom + 92 * density + 1)
            val area = bounds(viewport)
            drag(area.exactCenterX(), area.bottom - 8 * density, 0f, -72 * density)
            val session = text("Visible session")
            var row = session
            while (!row.isClickable && row.parent != null) row = row.parent
            assertTrue(row.isClickable)
            assertTrue("Session row is fully reachable by scrolling", bounds(row).height() >= 48 * density)
        }
    }

    @Test fun memoryCardsSessionRowsAndCommandsFollowChangingToolbarColors() {
        val colors = mutableStateOf(TerminalChromeColors(Color(0xFF123456)))
        val tool = mutableStateOf(MEMORY_TOOL)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.weight(1f))
                        TerminalToolbar(ToolbarConfig(tools = listOf(tool.value)), ToolbarSheetMode.Expanded,
                            {}, 400.dp, colors.value) { id, active ->
                            if (id == MEMORY_TOOL) {
                                ToolbarMemoryTool(active, listOf(SessionUi("one", "NATIVE", "Color session", "RUNNING")),
                                    30, {}) { """{"totalBytes":1048576,"availableBytes":524288,"sessions":{"one":1024}}""" }
                            } else {
                                ToolbarCommandsTool(listOf(ToolbarCommand("one", "Color command", "echo test")),
                                    active, "Color session", { _, _ -> }, {})
                            }
                        }
                    }
                } }
            } }
            fun assertContainer(title: String) {
                val area = bounds(text(title))
                val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    // The blank inset inside each rounded container avoids text and progress pixels.
                    val x = (16 * context.resources.displayMetrics.density).toInt()
                    assertEquals(title, colors.value.container.toArgb(), screenshot.getPixel(x, area.centerY()))
                } finally { screenshot.recycle() }
            }
            for (background in listOf(Color(0xFF123456), Color(0xFFF0E0D0))) {
                scenario.onActivity { colors.value = TerminalChromeColors(background); tool.value = MEMORY_TOOL }
                settle()
                assertContainer(label(R.string.toolbar_memory_device))
                assertContainer(label(R.string.toolbar_memory_app))
                assertContainer("Color session")
                scenario.onActivity { tool.value = COMMANDS_TOOL }
                settle()
                assertContainer("Color command")
            }
        }
    }

    @Test fun memorySectionsSettingsPersistIndependently() {
        val prefs = context.getSharedPreferences("memory-section-test", 0)
        prefs.edit().clear().commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    DroidTheme { TerminalHapticFeedbackProvider { ToolbarToolSettings("toolbar-memory", prefs, {}) } }
                } }
                settle()
                assertEquals(MemoryToolSections(), readMemoryToolSections(prefs))
                click(text(label(R.string.toolbar_memory_device)))
                assertEquals(MemoryToolSections(device = false), readMemoryToolSections(prefs))
                click(text(label(R.string.toolbar_memory_app)))
                click(text(label(R.string.toolbar_memory_sessions)))
                assertFalse(readMemoryToolSections(prefs).any)
                scenario.recreate()
                settle()
                assertFalse(readMemoryToolSections(prefs).any)
            }
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun appMemoryDetailsCanBeShownWithoutDeviceOrSessions() {
        val sections = mutableStateOf(MemoryToolSections(device = false, sessions = false))
        val loads = java.util.concurrent.atomic.AtomicInteger()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    ToolbarMemoryTool(true, listOf(SessionUi("one", "NATIVE", "Hidden session", "RUNNING")),
                        2, {}, sections = sections.value) {
                        loads.incrementAndGet()
                        """{"appMemory":{"total":1048576,"java-heap":524288,"native-heap":262144,"code":65536,"stack":65536,"graphics":0,"private-other":0,"system":131072}}"""
                    }
                } }
            } }
            settle()
            text(label(R.string.toolbar_memory_app))
            text(label(R.string.toolbar_memory_java))
            text(label(R.string.toolbar_memory_shared))
            assertNull(find { it.text?.toString() == "Hidden session" })
            scenario.onActivity { sections.value = MemoryToolSections(false, false, false) }
            settle()
            text(label(R.string.toolbar_memory_none))
            val count = loads.get()
            SystemClock.sleep(2200)
            assertEquals(count, loads.get())
        }
    }

    @Test fun memoryRowsSelectSessionAndDisableWhenInactive() {
        var selected: String? = null
        val active = mutableStateOf(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { DroidTheme { TerminalHapticFeedbackProvider {
                    ToolbarMemoryTool(active.value,
                        listOf(SessionUi("first", "NATIVE", "First session", "RUNNING"),
                            SessionUi("second", "AVF", "Second session", "RUNNING")), 30,
                        onSelectSession = { selected = it }) {
                        """{"totalBytes":1048576,"availableBytes":524288,"sessions":{"first":1024,"second":null}}"""
                    }
                } } }
            }
            settle()
            assertNull(find { it.text?.toString() == label(R.string.toolbar_memory_caveat) })
            click(text("Second session"))
            assertEquals("second", selected)
            click(text("First session"))
            assertEquals("first", selected)
            scenario.onActivity { active.value = false; selected = null }
            settle()
            click(text("Second session"))
            assertNull(selected)
        }
    }

    @Test fun commandSendRequiresConfirmationAndCancelsWhenInactive() {
        var sent: Pair<String, Boolean>? = null
        val active = mutableStateOf(true)
        val commands = listOf(ToolbarCommand("sample", "Sample", "echo first\necho second"))
        var edited: ToolbarCommand? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { DroidTheme { TerminalHapticFeedbackProvider {
                    Box(Modifier.padding(top = 32.dp)) {
                        ToolbarCommandsTool(commands, active.value, "Test session", { text, execute -> sent = text to execute }, { edited = it })
                    }
                } } }
            }
            settle()
            click(description(context.getString(R.string.toolbar_command_edit_item, "Sample")))
            text(label(R.string.toolbar_commands_edit))
            assertNull(edited)
            assertNull(sent)
            click(text(label(R.string.cancel)))
            assertNull(edited)
            click(description(context.getString(R.string.toolbar_command_edit_item, "Sample")))
            val commandField = waitForNode("command editor") { it.isEditable && it.text?.toString() == commands.single().text }
            commandField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "echo updated")
            })
            settle()
            click(text(label(R.string.save)))
            assertEquals(commands.single().copy(text = "echo updated"), edited)
            assertNull(sent)
            assertNull(find { it.text?.toString() == label(R.string.toolbar_run) })
            // A compact preview is available before opening the full confirmation.
            text("echo first\necho second")
            click(text("Sample"))
            text("echo first\necho second")
            text(context.getString(R.string.toolbar_send_target, "Test session"))
            assertNull(sent)
            click(text(label(R.string.toolbar_run)))
            assertEquals(commands.single().text to true, sent)
            scenario.onActivity { sent = null }
            click(text("Sample"))
            scenario.onActivity { active.value = false }
            settle()
            assertNull(find { it.text?.toString() == label(R.string.toolbar_run) })
            assertNull(sent)
            click(description(context.getString(R.string.toolbar_command_edit_item, "Sample")))
            text(label(R.string.toolbar_commands_edit))
            click(text(label(R.string.cancel)))
        }
    }

    @Test fun settingsDragMorphsCornersAtBothEndsWithoutGroupClipping() {
        val prefs = context.getSharedPreferences("toolbar-corners-test", 0)
        val original = listOf(HOTKEY_TOOL, "second", "third")
        val registry = toolbarTools + listOf(
            ToolbarTool("second", R.string.diagnostics_title, "fixture"),
            ToolbarTool("third", R.string.toolbar_empty, "fixture"))
        prefs.edit().clear().putString(KEY_TOOLBAR, serializeToolbarConfig(ToolbarConfig(tools = original))).commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    DroidTheme { TerminalHapticFeedbackProvider { ToolbarSettings(prefs, {}, registry) {} } }
                } }
                settle()
                click(description(label(R.string.toolbar_edit)))
                val moveLabel = context.getString(R.string.toolbar_move, label(R.string.diagnostics_title))
                fun rowBounds(): Rect {
                    var node = description(moveLabel)
                    while (!node.isClickable && node.parent != null) node = node.parent
                    return bounds(node)
                }
                val first = bounds(description(context.getString(R.string.toolbar_move, label(R.string.settings_hotkeys_title))))
                val handle = bounds(description(moveLabel))
                val row = rowBounds()
                val step = handle.exactCenterY() - first.exactCenterY()
                val gridTop = row.top - step
                val density = context.resources.displayMetrics.density
                val start = SystemClock.uptimeMillis()
                var pointerY = handle.exactCenterY()
                fun touch(action: Int) {
                    val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, handle.exactCenterX(), pointerY, 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                }
                touch(MotionEvent.ACTION_DOWN)
                try {
                    pointerY += 36f * density
                    touch(MotionEvent.ACTION_MOVE)
                    SystemClock.sleep(350)
                    fun cornersAt(slot: Float): Pair<Int, Int> {
                        pointerY += gridTop + step * slot - rowBounds().top
                        touch(MotionEvent.ACTION_MOVE)
                        SystemClock.sleep(350)
                        val lifted = rowBounds()
                        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                        try {
                            val inset = (2f * density).toInt()
                            val background = screenshot.getPixel(lifted.centerX(), lifted.top + inset)
                            assertEquals("Lifted surface extends beyond the former wrapper",
                                background, screenshot.getPixel(row.right + 2, lifted.centerY()))
                            fun cutout(y: Int): Int = (0..(40f * density).toInt()).first { x ->
                                screenshot.getPixel(lifted.left + x, y) == background
                            }
                            return cutout(lifted.top + inset) to cutout(lifted.bottom - inset)
                        } finally { screenshot.recycle() }
                    }
                    val middle = cornersAt(1f)
                    val nearTop = cornersAt(0.5f)
                    val top = cornersAt(0f)
                    assertTrue("Top corners grow progressively", middle.first < nearTop.first && nearTop.first < top.first)
                    assertEquals("Bottom corners stay small at the top", middle.second.toFloat(), top.second.toFloat(), 2f * density)
                    val nearBottom = cornersAt(1.5f)
                    val bottom = cornersAt(2f)
                    assertTrue("Bottom corners grow progressively", middle.second < nearBottom.second && nearBottom.second < bottom.second)
                    assertEquals("Top corners stay small at the bottom", middle.first.toFloat(), bottom.first.toFloat(), 2f * density)
                    assertEquals(original, parseToolbarConfig(prefs.getString(KEY_TOOLBAR, null), available = registry.map { it.id }.toSet()).tools)
                } finally { touch(MotionEvent.ACTION_CANCEL) }
                settle()
                assertEquals(original, parseToolbarConfig(prefs.getString(KEY_TOOLBAR, null), available = registry.map { it.id }.toSet()).tools)
                assertEquals(row.top, rowBounds().top)
            }
        } finally { prefs.edit().clear().commit() }
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
                fun toolRowHeight(): Int {
                    var node = text(label(R.string.settings_hotkeys_title))
                    while (!node.isClickable && node.parent != null) node = node.parent
                    return bounds(node).height()
                }
                val normalHeight = toolRowHeight()
                assertEquals(56f * context.resources.displayMetrics.density, normalHeight.toFloat(), 1f)
                click(description(label(R.string.toolbar_edit)))
                assertEquals(normalHeight, toolRowHeight())
                text(label(R.string.diagnostics_title))
                assertNull(find { it.contentDescription?.toString() == context.getString(R.string.toolbar_move, label(R.string.diagnostics_title)) })
                click(description(context.getString(R.string.toolbar_add, label(R.string.diagnostics_title))))
                val first = bounds(description(context.getString(R.string.toolbar_move, label(R.string.settings_hotkeys_title))))
                val second = bounds(description(context.getString(R.string.toolbar_move, label(R.string.diagnostics_title))))
                val original = listOf(HOTKEY_TOOL, "second")
                val delta = (second.exactCenterY() - first.exactCenterY()) * 1.4f
                fun assertDraggedCorners() {
                    var node = description(context.getString(R.string.toolbar_move, label(R.string.settings_hotkeys_title)))
                    while (!node.isClickable && node.parent != null) node = node.parent
                    val row = bounds(node)
                    val inset = (2f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                    val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try {
                        val background = screenshot.getPixel(row.right - inset, row.centerY())
                        for (x in listOf(row.left + inset, row.right - inset)) {
                            for (y in listOf(row.top + inset, row.bottom - inset)) {
                                assertNotEquals("Dragged row must retain rounded corners at ($x, $y)",
                                    background, screenshot.getPixel(x, y))
                            }
                        }
                    } finally { screenshot.recycle() }
                }
                drag(first.exactCenterX(), first.exactCenterY(), 0f, delta, cancel = true) {
                    assertDraggedCorners()
                    val movingNeighbor = bounds(description(context.getString(R.string.toolbar_move, label(R.string.diagnostics_title))))
                    assertTrue("Neighbor moves before drop", movingNeighbor.centerY() < second.centerY())
                    assertEquals(original, parseToolbarConfig(prefs.getString(KEY_TOOLBAR, null), available = registry.map { it.id }.toSet()).tools)
                }
                assertEquals(original, parseToolbarConfig(prefs.getString(KEY_TOOLBAR, null), available = registry.map { it.id }.toSet()).tools)
                assertEquals(second.centerY(), bounds(description(context.getString(R.string.toolbar_move, label(R.string.diagnostics_title)))).centerY())
                drag(first.exactCenterX(), first.exactCenterY(), 0f, delta) { assertDraggedCorners() }
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
