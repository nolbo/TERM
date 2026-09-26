package dev.terminal.app

import org.junit.Assert.*
import org.junit.Test

class ToolbarConfigTest {
    @Test fun legacyDisabledToolbarStaysDisabledAndKeepsItsTool() {
        assertEquals(ToolbarConfig(false), parseToolbarConfig(null, legacyEnabled = false))
    }

    @Test fun explicitConfigOverridesLegacyAndPreservesAnEmptyToolList() {
        val value = ToolbarConfig(true, emptyList())
        assertEquals(value, parseToolbarConfig(serializeToolbarConfig(value), legacyEnabled = false))
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

    @Test fun corruptAndFutureFormatsUseLegacyFallback() {
        assertEquals(ToolbarConfig(false), parseToolbarConfig("not json", false))
        assertEquals(ToolbarConfig(), parseToolbarConfig("""{"version":2,"enabled":false,"tools":[]}"""))
    }
}
