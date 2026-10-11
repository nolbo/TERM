package dev.terminal.app

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal const val KEY_TOOLBAR_COMMANDS = "toolbar-commands-v1"
internal const val MAX_TOOLBAR_COMMANDS = 64
internal const val MAX_TOOLBAR_TEXT = 8192
internal data class ToolbarCommand(val id: String = UUID.randomUUID().toString(), val name: String, val text: String)
internal fun validToolbarCommand(command: ToolbarCommand): Boolean = command.id.length in 1..80 &&
    command.name.isNotBlank() && command.name.length <= 60 && command.name.none { it.isISOControl() } &&
    command.text.isNotBlank() && command.text.length <= MAX_TOOLBAR_TEXT &&
    command.text.none { it.isISOControl() && it != '\n' && it != '\t' }

internal fun parseToolbarCommands(value: String?): List<ToolbarCommand> = runCatching {
    if (value == null) return emptyList()
    val json = JSONObject(value)
    require(json.getInt("version") == 1)
    val array = json.getJSONArray("commands")
    require(array.length() <= MAX_TOOLBAR_COMMANDS)
    (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        ToolbarCommand(item.getString("id"), item.getString("name"), item.getString("text"))
            .also { require(validToolbarCommand(it)) }
    }.also { require(it.distinctBy { it.id }.size == it.size) }
}.getOrDefault(emptyList())

internal fun readToolbarCommands(preferences: SharedPreferences) = parseToolbarCommands(preferences.getString(KEY_TOOLBAR_COMMANDS, null))
internal fun serializeToolbarCommands(commands: List<ToolbarCommand>): String {
    require(commands.size <= MAX_TOOLBAR_COMMANDS && commands.all(::validToolbarCommand) && commands.distinctBy { it.id }.size == commands.size)
    return JSONObject().put("version", 1).put("commands", JSONArray().apply {
        commands.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("text", it.text)) }
    }).toString()
}
