package dev.terminal.app

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal const val KEY_TOOLBAR = "terminal-toolbar-v1"
internal const val KEY_SKIP_SESSION_CLOSE_CONFIRMATION = "session-skip-close-confirmation"
internal const val SESSIONS_TOOL = "sessions"
internal const val HOTKEY_TOOL = "hotkeys"
internal const val MEMORY_TOOL = "memory"
internal const val COMMANDS_TOOL = "commands"
internal const val HISTORY_TOOL = "history"

/** Stable IDs are shared by persistence, settings destinations and pager content. */
internal data class ToolbarTool(val id: String, val title: Int, val settings: String?, val removable: Boolean = true)
internal val toolbarTools = listOf(
    ToolbarTool(SESSIONS_TOOL, R.string.toolbar_sessions, "toolbar-sessions", removable = false),
    ToolbarTool(HOTKEY_TOOL, R.string.settings_hotkeys_title, "hotkeys"),
    ToolbarTool(MEMORY_TOOL, R.string.toolbar_memory, "toolbar-memory"),
    ToolbarTool(COMMANDS_TOOL, R.string.toolbar_commands, "toolbar-commands"),
    ToolbarTool(HISTORY_TOOL, R.string.toolbar_history, "toolbar-history"),
)

internal data class ToolbarConfig(val enabled: Boolean = true, val tools: List<String> = listOf(SESSIONS_TOOL, HOTKEY_TOOL))

internal fun parseToolbarConfig(value: String?, legacyEnabled: Boolean = true,
    available: Set<String> = toolbarTools.map { it.id }.toSet()): ToolbarConfig = runCatching {
    if (value == null) return ToolbarConfig(enabled = legacyEnabled)
    val json = JSONObject(value)
    require(json.getInt("version") == 1)
    val array = json.getJSONArray("tools")
    ToolbarConfig(json.getBoolean("enabled"), (0 until array.length()).map { array.getString(it) }
        .filter { it in available }.distinct().let { tools ->
            if (SESSIONS_TOOL in available && SESSIONS_TOOL !in tools) listOf(SESSIONS_TOOL) + tools else tools
        })
}.getOrElse { ToolbarConfig(enabled = legacyEnabled) }

internal fun readToolbarConfig(preferences: SharedPreferences) =
    parseToolbarConfig(preferences.getString(KEY_TOOLBAR, null), readHotkeyLayout(preferences).enabled)

internal fun serializeToolbarConfig(config: ToolbarConfig): String = JSONObject()
    .put("version", 1).put("enabled", config.enabled).put("tools", JSONArray(config.tools)).toString()

internal fun ToolbarConfig.addTool(id: String): ToolbarConfig =
    if (id in tools) this else copy(tools = tools + id)

internal fun ToolbarConfig.removeTool(id: String): ToolbarConfig =
    if (toolbarTools.any { it.id == id && !it.removable }) this else copy(tools = tools - id)

/** Navigation remains reachable; empty Workspace restrictions never change saved tools. */
internal fun ToolbarConfig.visibleConfig(sessionsOnly: Boolean = false): ToolbarConfig = copy(enabled = true,
    tools = if (sessionsOnly || !enabled) listOf(SESSIONS_TOOL)
        else if (SESSIONS_TOOL in tools) tools else listOf(SESSIONS_TOOL) + tools)

internal fun ToolbarConfig.moveTool(from: Int, to: Int): ToolbarConfig =
    if (from !in tools.indices || to !in tools.indices) this
    else copy(tools = tools.toMutableList().apply { add(to, removeAt(from)) })

internal enum class ToolbarSheetMode { Hidden, Default, Expanded }
