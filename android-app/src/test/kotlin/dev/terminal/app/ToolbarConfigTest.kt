package dev.terminal.app

import org.junit.Assert.*
import org.junit.Test

class ToolbarConfigTest {
    @Test fun historyIsOptionalAndItsOrderSurvivesPersistence() {
        val config = ToolbarConfig().addTool(HISTORY_TOOL).moveTool(2, 0)
        assertEquals(config, parseToolbarConfig(serializeToolbarConfig(config)))
        assertEquals(listOf(SESSIONS_TOOL, HOTKEY_TOOL), config.removeTool(HISTORY_TOOL).tools)
    }
    @Test fun removedClipboardIsDroppedWithoutChangingOtherToolsOrEnabledState() {
        val previous = ToolbarConfig(false, listOf(MEMORY_TOOL, "clipboard", HOTKEY_TOOL, HISTORY_TOOL))
        assertEquals(previous.copy(tools = listOf(SESSIONS_TOOL, MEMORY_TOOL, HOTKEY_TOOL, HISTORY_TOOL)),
            parseToolbarConfig(serializeToolbarConfig(previous)))
        assertEquals(ToolbarConfig(tools = listOf(SESSIONS_TOOL)),
            parseToolbarConfig(serializeToolbarConfig(ToolbarConfig(tools = listOf("clipboard")))))
    }

    @Test fun removedCommandSetsAreDroppedWithoutChangingRemainingOrderOrEnabledState() {
        for (enabled in listOf(true, false)) {
            val previous = ToolbarConfig(enabled, listOf(MEMORY_TOOL, "commands", HOTKEY_TOOL, "commands", HISTORY_TOOL, SESSIONS_TOOL))
            val expected = ToolbarConfig(enabled, listOf(MEMORY_TOOL, HOTKEY_TOOL, HISTORY_TOOL, SESSIONS_TOOL))
            val parsed = parseToolbarConfig(serializeToolbarConfig(previous))
            assertEquals(expected, parsed)
            assertEquals(expected, parseToolbarConfig(serializeToolbarConfig(parsed)))
        }
        assertEquals(ToolbarConfig(tools = listOf(SESSIONS_TOOL)),
            parseToolbarConfig(serializeToolbarConfig(ToolbarConfig(tools = listOf("commands")))))
    }

    @Test fun legacyDisabledToolbarStaysDisabledAndKeepsItsTool() {
        assertEquals(ToolbarConfig(false), parseToolbarConfig(null, legacyEnabled = false))
    }

    @Test fun explicitConfigOverridesLegacyAndRestoresMandatoryTool() {
        val value = ToolbarConfig(true, emptyList())
        assertEquals(value.copy(tools = listOf(SESSIONS_TOOL)),
            parseToolbarConfig(serializeToolbarConfig(value), legacyEnabled = false))
    }

    @Test fun persistedOrderDeduplicatesAndDropsUnavailableTools() {
        val stored = ToolbarConfig(false, listOf("info", HOTKEY_TOOL, "info", "removed"))
        assertEquals(ToolbarConfig(false, listOf("info", HOTKEY_TOOL)),
            parseToolbarConfig(serializeToolbarConfig(stored), available = setOf("info", HOTKEY_TOOL)))
    }

    @Test fun reorderRemoveAndReaddDoNotLoseOtherTools() {
        val original = ToolbarConfig(tools = listOf("a", "b", "c"))
        val moved = original.moveTool(0, 2)
        assertEquals(listOf("b", "c", "a"), moved.tools)
        assertEquals(original, original.moveTool(-1, 2))
        assertEquals(original, original.moveTool(0, 3))
        assertEquals(moved, moved.addTool("a"))
        assertEquals(listOf("c", "a", "b"), moved.copy(tools = moved.tools - "b").addTool("b").tools)
    }

    @Test fun mandatoryToolCannotBeRemovedButCanBeReordered() {
        val config = ToolbarConfig(tools = listOf(SESSIONS_TOOL, MEMORY_TOOL))
        assertEquals(config, config.removeTool(SESSIONS_TOOL))
        assertEquals(listOf(SESSIONS_TOOL), config.removeTool(MEMORY_TOOL).tools)
        val moved = config.moveTool(0, 1)
        assertEquals(moved, parseToolbarConfig(serializeToolbarConfig(moved)))
    }

    @Test fun disabledOptionalToolsLeaveSessionNavigationAvailable() {
        assertEquals(ToolbarConfig(true, listOf(SESSIONS_TOOL)),
            ToolbarConfig(false, listOf(HOTKEY_TOOL, SESSIONS_TOOL, MEMORY_TOOL)).visibleConfig())
        assertEquals(ToolbarConfig(), ToolbarConfig(tools = listOf(HOTKEY_TOOL)).visibleConfig())
    }

    @Test fun corruptAndFutureFormatsUseLegacyFallback() {
        assertEquals(ToolbarConfig(false), parseToolbarConfig("not json", false))
        assertEquals(ToolbarConfig(), parseToolbarConfig("""{"version":2,"enabled":false,"tools":[]}"""))
    }
}
