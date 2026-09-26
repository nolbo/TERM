package dev.terminal.app

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal const val KEY_TOOLBAR = "terminal-toolbar-v1"
internal const val HOTKEY_TOOL = "hotkeys"
internal const val MEMORY_TOOL = "memory"
internal const val CLIPBOARD_TOOL = "clipboard"
internal const val COMMANDS_TOOL = "commands"

/** Stable IDs are shared by persistence, settings destinations and pager content. */
internal data class ToolbarTool(val id: String, val title: Int, val settings: String)
internal val toolbarTools = listOf(
    ToolbarTool(HOTKEY_TOOL, R.string.settings_hotkeys_title, "hotkeys"),
    ToolbarTool(MEMORY_TOOL, R.string.toolbar_memory, "toolbar-memory"),
    ToolbarTool(CLIPBOARD_TOOL, R.string.toolbar_clipboard, "toolbar-clipboard"),
    ToolbarTool(COMMANDS_TOOL, R.string.toolbar_commands, "toolbar-commands"),
)

internal data class ToolbarConfig(val enabled: Boolean = true, val tools: List<String> = listOf(HOTKEY_TOOL))

internal fun parseToolbarConfig(value: String?, legacyEnabled: Boolean = true,
    available: Set<String> = toolbarTools.map { it.id }.toSet()): ToolbarConfig = runCatching {
    if (value == null) return ToolbarConfig(enabled = legacyEnabled)
    val json = JSONObject(value)
    require(json.getInt("version") == 1)
    val array = json.getJSONArray("tools")
    ToolbarConfig(json.getBoolean("enabled"), (0 until array.length()).map { array.getString(it) }
        .filter { it in available }.distinct())
}.getOrElse { ToolbarConfig(enabled = legacyEnabled) }

internal fun readToolbarConfig(preferences: SharedPreferences) =
    parseToolbarConfig(preferences.getString(KEY_TOOLBAR, null), readHotkeyLayout(preferences).enabled)

internal fun serializeToolbarConfig(config: ToolbarConfig): String = JSONObject()
    .put("version", 1).put("enabled", config.enabled).put("tools", JSONArray(config.tools)).toString()

internal fun ToolbarConfig.addTool(id: String): ToolbarConfig =
    if (id in tools) this else copy(tools = tools + id)

internal fun ToolbarConfig.moveTool(from: Int, to: Int): ToolbarConfig =
    if (from !in tools.indices || to !in tools.indices) this
    else copy(tools = tools.toMutableList().apply { add(to, removeAt(from)) })

internal enum class ToolbarSheetMode { Hidden, Default, Expanded }
