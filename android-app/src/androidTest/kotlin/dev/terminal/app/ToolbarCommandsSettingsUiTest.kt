package dev.terminal.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.DroidTheme
import dev.terminal.app.ui.TerminalHapticFeedbackProvider
import dev.terminal.app.ui.ToolbarCommandsTool
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolbarCommandsSettingsUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val preferences get() = context.getSharedPreferences(SettingsDetailActivity.TERMINAL_PREFERENCES, Context.MODE_PRIVATE)
    private val commands = listOf(
        ToolbarCommand("first", "Long command name with multiple words to wrap across the row", "printf 'first\\n'\npwd"),
        ToolbarCommand("second", "Second command", "echo second"),
    )

    @Test fun sharedReorderingPersistsOnlyOnDropAndReachesTheTerminalTool() = withCommands(commands) { prefs ->
        ActivityScenario.launch<SettingsDetailActivity>(settingsIntent()).use { activity ->
            assertNull(find { it.hasLabel(moveLabel(commands.first())) })
            click(label(R.string.toolbar_edit))
            node(moveLabel(commands.first()))
            assertTrue(rowBounds(commands.first().name).height() > rowBounds(commands.last().name).height())
            click(commands.first().name)
            assertNull("Editing order must not navigate to the item editor", find { it.isEditable })
            drag(bounds(node(moveLabel(commands.first()))), bounds(node(moveLabel(commands.last()))).exactCenterY(), cancel = true) {
                assertEquals(commands, readToolbarCommands(prefs))
            }
            assertEquals(commands, readToolbarCommands(prefs))
            drag(bounds(node(moveLabel(commands.first()))), bounds(node(moveLabel(commands.last()))).exactCenterY()) {
                assertEquals("A drag preview must not write commands", commands, readToolbarCommands(prefs))
            }
            await { readToolbarCommands(prefs) == commands.reversed() }
            activity.recreate()
            node(label(R.string.toolbar_done))
            move(commands.first(), R.string.toolbar_move_up)
            await { readToolbarCommands(prefs) == commands }
            move(commands.first(), R.string.toolbar_move_down)
            await { readToolbarCommands(prefs) == commands.reversed() }
            click(label(R.string.toolbar_done))
            await { find { it.hasLabel(moveLabel(commands.first())) } == null }
            assertTrue(rowBounds(commands.last().name).top < rowBounds(commands.first().name).top)
            click(commands.last().name)
            node(label(R.string.shell_editor_language))
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            node(label(R.string.toolbar_commands_list))
            assertEquals(commands.reversed(), readToolbarCommands(prefs))

            activity.onActivity { it.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    ToolbarCommandsTool(readToolbarCommands(prefs), true, "Test session", { _, _ -> }, {})
                } }
            } }
            settle()
            assertTrue("The terminal uses the same saved order", rowBounds(commands.last().name).top < rowBounds(commands.first().name).top)
        }
    }

    @Test fun editorRestoresInputDraftAndSavesWithoutMovingTheCommand() = withCommands(commands.reversed()) { prefs ->
        val original = readToolbarCommands(prefs)
        ActivityScenario.launch<ToolbarCommandEditActivity>(ToolbarCommandEditActivity.intent(context, commands.first().id)).use { activity ->
            click(commands.first().name)
            replaceInput("Cancelled name")
            click(label(R.string.cancel))
            node(commands.first().name)
            rename(commands.first().name, "Renamed command")
            replaceInput("if true; then\n\tprintf '%s\\n' \"\$HOME\"\nfi # 한글🙂")
            focusInput()
            val scriptField = input()
            assertTrue(scriptField.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 4)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 8)
            }))
            settle()
            assertEquals(4, input().textSelectionStart)
            assertEquals(8, input().textSelectionEnd)
            activity.recreate()
            node("Renamed command")
            focusInput()
            assertEquals(4, input().textSelectionStart)
            assertEquals(8, input().textSelectionEnd)
            assertEquals(original, readToolbarCommands(prefs))
            click(label(R.string.back))
            node(label(R.string.profile_discard))
            click(label(R.string.cancel))
            click(label(R.string.save))
            val updated = commands.first().copy(name = "Renamed command", text = "if true; then\n\tprintf '%s\\n' \"\$HOME\"\nfi # 한글🙂")
            await { readToolbarCommands(prefs) == listOf(commands.last(), updated) }
        }
    }

    @Test fun creationAppendsAndEditorOffersOnlyBackNameAndSave() = withCommands(commands.reversed()) { prefs ->
        val original = readToolbarCommands(prefs)
        ActivityScenario.launch<ToolbarCommandEditActivity>(ToolbarCommandEditActivity.intent(context)).use { activity ->
            assertFalse(clickable(node(label(R.string.save))).isEnabled)
            assertNull("The document has no inline name form", find { it.hasLabel(label(R.string.toolbar_command_name)) })
            replaceInput("a".repeat(MAX_TOOLBAR_TEXT + 1))
            assertFalse(clickable(node(label(R.string.save))).isEnabled)
            replaceInput("echo\u001b[31m")
            assertFalse(clickable(node(label(R.string.save))).isEnabled)
            replaceInput("echo new\npwd")
            activity.recreate()
            node("echo new\npwd")
            assertEquals(original, readToolbarCommands(prefs))
            click(label(R.string.save))
            node(label(R.string.toolbar_command_rename))
            replaceInput("a".repeat(61))
            assertFalse(clickable(node(label(R.string.confirm))).isEnabled)
            replaceInput("New command")
            activity.recreate()
            node("New command")
            assertEquals(original, readToolbarCommands(prefs))
            click(label(R.string.cancel))
            node(label(R.string.toolbar_command_untitled))
            click(label(R.string.save))
            replaceInput("New command")
            click(label(R.string.confirm))
            await { readToolbarCommands(prefs).size == original.size + 1 }
        }
        val added = readToolbarCommands(prefs).last()
        assertEquals(original, readToolbarCommands(prefs).dropLast(1))
        assertEquals("New command", added.name)
        assertEquals("echo new\npwd", added.text)
        ActivityScenario.launch<ToolbarCommandEditActivity>(ToolbarCommandEditActivity.intent(context, added.id)).use {
            node(added.name)
            node(label(R.string.back))
            node(label(R.string.save))
            assertNull(find { it.hasLabel(label(R.string.toolbar_command_editor_options)) })
            assertNull(find { it.hasLabel(label(R.string.toolbar_command_rename)) })
            assertNull(find { it.hasLabel(label(R.string.delete)) })
            assertEquals(original + added, readToolbarCommands(prefs))
        }
    }

    @Test fun editorHasOneDocumentAndScrollsToTheCaret() = withCommands(commands) { prefs ->
        ActivityScenario.launch<ToolbarCommandEditActivity>(ToolbarCommandEditActivity.intent(context, commands.last().id)).use {
            val field = input()
            var fields = 0
            find { if (it.isEditable) fields++; false }
            assertEquals("The main screen is one code document", 1, fields)
            val rootBounds = bounds(requireNotNull(instrumentation.uiAutomation.rootInActiveWindow))
            assertTrue("Code fills the space below the app bar", bounds(field).height() > rootBounds.height() * 0.65f)
            val text = (1..100).joinToString("\n") { "echo line_$it" }
            replaceInput(text)
            focusInput()
            val current = input()
            if (current.textSelectionStart != text.length || current.textSelectionEnd != text.length) {
                assertTrue("Move caret from ${current.textSelectionStart}..${current.textSelectionEnd} to ${text.length}",
                    current.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
                    }))
            }
            node(context.getString(R.string.shell_editor_position, 100, "echo line_100".length + 1))
            await { find { node -> node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD } } != null }
            assertTrue(clickable(node(label(R.string.save))).isVisibleToUser)
            assertEquals("Editing and scrolling do not persist a draft", commands, readToolbarCommands(prefs))
        }
    }

    @Test fun editorHeaderFloatsAboveCodeAndRestoresAtTheTop() {
        val long = commands.last().copy(text = (1..100).joinToString("\n") { "echo line_$it" })
        withCommands(listOf(long)) { prefs ->
            ActivityScenario.launch<ToolbarCommandEditActivity>(ToolbarCommandEditActivity.intent(context, long.id)).use { activity ->
                node(long.name)
                val initialTop = bounds(input()).top
                val buttons = listOf(label(R.string.back), long.name, label(R.string.save))
                val initialButtons = buttons.map { bounds(clickable(node(it))) }
                val screen = bounds(requireNotNull(instrumentation.uiAutomation.rootInActiveWindow))
                val start = Rect(screen.centerX() - 1, screen.height() * 3 / 4 - 1,
                    screen.centerX() + 1, screen.height() * 3 / 4 + 1)
                drag(start, screen.height() * 2f / 5)
                await { bounds(input()).top < initialTop - 20 }
                buttons.forEachIndexed { index, label ->
                    assertEquals("Floating controls remain pinned", initialButtons[index], bounds(clickable(node(label))))
                }
                assertNull(find { it.hasLabel(label(R.string.toolbar_command_editor_options)) })
                assertNull(find { it.hasLabel(label(R.string.delete)) })
                // The transparent gap between capsules must still pass taps to the code below.
                val gapX = (initialButtons[0].right + initialButtons[1].left) / 2f
                val gapY = initialButtons[0].exactCenterY()
                val now = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, gapX, gapY, 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                }
                await { input().textSelectionStart > 0 }
                assertEquals(listOf(long), readToolbarCommands(prefs))
                settle()
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                settle()
                activity.recreate()
                node(long.name)
                assertTrue("The restored header remains floating", bounds(input()).top < initialTop - 20)
                val downStart = Rect(screen.centerX() - 1, screen.height() / 3 - 1,
                    screen.centerX() + 1, screen.height() / 3 + 1)
                repeat(3) { drag(downStart, screen.height() * 0.85f) }
                await { bounds(input()).top >= initialTop - 2 }
                buttons.forEachIndexed { index, label ->
                    assertEquals(initialButtons[index], bounds(clickable(node(label))))
                }
            }
        }
    }

    @Test fun emptyAndFullListsExposeTheCorrectAddState() = withCommands(emptyList()) { prefs ->
        ActivityScenario.launch<SettingsDetailActivity>(settingsIntent()).use {
            node(label(R.string.toolbar_commands_none))
            assertTrue(clickable(node(label(R.string.toolbar_command_add))).isEnabled)
            assertNull(find { it.hasLabel(label(R.string.toolbar_edit)) })
            val full = List(MAX_TOOLBAR_COMMANDS) { ToolbarCommand("command-$it", "Command $it", "echo $it") }
            prefs.edit().putString(KEY_TOOLBAR_COMMANDS, serializeToolbarCommands(full)).commit()
            node(label(R.string.toolbar_commands_limit))
            assertFalse(clickable(node(label(R.string.toolbar_command_add))).isEnabled)
            node(label(R.string.toolbar_edit))
            assertEquals(full, readToolbarCommands(prefs))
        }
    }

    private fun settingsIntent() = Intent(context, SettingsDetailActivity::class.java)
        .putExtra(SettingsDetailActivity.EXTRA_CATEGORY, "toolbar-commands")
    private fun withCommands(value: List<ToolbarCommand>, test: (SharedPreferences) -> Unit) {
        val prefs = preferences
        val previous = prefs.getString(KEY_TOOLBAR_COMMANDS, null)
        try {
            prefs.edit().putString(KEY_TOOLBAR_COMMANDS, serializeToolbarCommands(value)).commit()
            test(prefs)
        } finally {
            prefs.edit().apply {
                if (previous == null) remove(KEY_TOOLBAR_COMMANDS) else putString(KEY_TOOLBAR_COMMANDS, previous)
            }.commit()
        }
    }
    private fun label(id: Int) = context.getString(id)
    private fun moveLabel(command: ToolbarCommand) = context.getString(R.string.toolbar_move, command.name)
    private fun AccessibilityNodeInfo.hasLabel(label: String) =
        text?.toString()?.lineSequence()?.any { it == label } == true || text?.toString() == label || contentDescription?.toString() == label
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || !node.isVisibleToUser) return null
            if (predicate(node)) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun node(label: String): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        await { result = find { it.hasLabel(label) }; result != null }
        return requireNotNull(result)
    }
    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var target = node
        while (!target.isClickable && target.parent != null) target = target.parent
        return target
    }
    private fun click(label: String, button: Boolean = false) {
        val target = if (button) {
            var result: AccessibilityNodeInfo? = null
            await { result = find { it.hasLabel(label) && clickable(it).isClickable }; result != null }
            clickable(requireNotNull(result))
        } else clickable(node(label))
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        settle()
    }
    private fun input(): AccessibilityNodeInfo {
        var field: AccessibilityNodeInfo? = null
        // There is one document, or one name field in the active rename dialog.
        await {
            field = find { it.isEditable }
            field != null
        }
        return requireNotNull(field)
    }
    private fun replaceInput(value: String) {
        assertTrue(input().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
        settle()
    }
    private fun focusInput() {
        assertTrue(input().performAction(AccessibilityNodeInfo.ACTION_FOCUS))
        settle()
    }
    private fun rename(currentName: String, value: String) {
        click(currentName)
        replaceInput(value)
        click(label(R.string.confirm))
        node(value)
    }
    private fun move(command: ToolbarCommand, actionLabel: Int) {
        var handle: AccessibilityNodeInfo? = null
        var action: AccessibilityNodeInfo.AccessibilityAction? = null
        await {
            handle = find { it.hasLabel(moveLabel(command)) }
            action = handle?.actionList?.firstOrNull { it.label?.toString() == label(actionLabel) }
            action != null
        }
        assertTrue(requireNotNull(handle).performAction(requireNotNull(action).id))
        settle()
    }
    private fun rowBounds(label: String) = bounds(clickable(node(label)))
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun settle() { SystemClock.sleep(800); instrumentation.waitForIdleSync() }
    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 7000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (check()) return
            SystemClock.sleep(100)
        }
        error("Command set UI did not reach the expected state")
    }
    private fun drag(start: Rect, endY: Float, cancel: Boolean = false, duringDrag: (() -> Unit)? = null) {
        val downTime = SystemClock.uptimeMillis()
        for (step in 0..20) {
            val action = when (step) {
                0 -> MotionEvent.ACTION_DOWN
                20 -> if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                start.exactCenterX(), start.exactCenterY() + (endY - start.exactCenterY()) * step / 20, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            if (step == 19) duringDrag?.invoke()
            SystemClock.sleep(20)
        }
        settle()
    }
}
