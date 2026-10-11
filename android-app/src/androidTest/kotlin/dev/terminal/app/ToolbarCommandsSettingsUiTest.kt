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
            assertNull("Editing order must not navigate to the item editor", find { it.hasLabel(label(R.string.toolbar_command_name)) })
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
            node(label(R.string.toolbar_command_name))
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
            click(label(R.string.toolbar_command_name))
            replaceInput("Cancelled name")
            click(label(R.string.cancel))
            assertEquals(original, readToolbarCommands(prefs))
            node(commands.first().name)
            click(label(R.string.toolbar_command_name))
            replaceInput("Renamed command")
            activity.recreate()
            node("Renamed command")
            click(label(R.string.confirm))
            assertEquals("Confirm updates the draft only", original, readToolbarCommands(prefs))
            click(label(R.string.toolbar_command_text))
            replaceInput("echo edited\npwd")
            click(label(R.string.confirm))
            activity.recreate()
            node("Renamed command")
            assertEquals(original, readToolbarCommands(prefs))
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            node(label(R.string.profile_discard))
            click(label(R.string.cancel))
            click(label(R.string.save))
            val updated = commands.first().copy(name = "Renamed command", text = "echo edited\npwd")
            await { readToolbarCommands(prefs) == listOf(commands.last(), updated) }
        }
    }

    @Test fun creationAppendsAndDeletionRequiresConfirmation() = withCommands(commands.reversed()) { prefs ->
        val original = readToolbarCommands(prefs)
        ActivityScenario.launch<ToolbarCommandEditActivity>(ToolbarCommandEditActivity.intent(context)).use { activity ->
            assertFalse(clickable(node(label(R.string.save))).isEnabled)
            click(label(R.string.toolbar_command_name))
            replaceInput("New command")
            click(label(R.string.confirm))
            click(label(R.string.toolbar_command_text))
            replaceInput("echo new\npwd")
            activity.recreate()
            node("echo new\npwd")
            click(label(R.string.confirm))
            assertEquals(original, readToolbarCommands(prefs))
            click(label(R.string.save))
            await { readToolbarCommands(prefs).size == original.size + 1 }
        }
        val added = readToolbarCommands(prefs).last()
        assertEquals(original, readToolbarCommands(prefs).dropLast(1))
        assertEquals("New command", added.name)
        assertEquals("echo new\npwd", added.text)
        ActivityScenario.launch<ToolbarCommandEditActivity>(ToolbarCommandEditActivity.intent(context, added.id)).use {
            click(label(R.string.delete))
            node(label(R.string.toolbar_command_delete))
            click(label(R.string.cancel))
            assertEquals(original + added, readToolbarCommands(prefs))
            click(label(R.string.delete))
            click(label(R.string.toolbar_command_delete), button = true)
            await { readToolbarCommands(prefs) == original }
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
    private fun replaceInput(value: String) {
        var field: AccessibilityNodeInfo? = null
        await { field = find { it.isEditable }; field != null }
        assertTrue(requireNotNull(field).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
        settle()
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
