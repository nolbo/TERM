package dev.terminal.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.*
import dev.terminal.app.ui.TerminalActivity
import dev.terminal.app.ui.ToolbarCommandEditor

class ToolbarCommandEditActivity : TerminalActivity() {
    private var error by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences(SettingsDetailActivity.TERMINAL_PREFERENCES, MODE_PRIVATE)
        val requestedId = intent.getStringExtra(EXTRA_COMMAND_ID)
        val source = if (requestedId == null) ToolbarCommand(name = "", text = "")
            else readToolbarCommands(preferences).firstOrNull { it.id == requestedId } ?: run { finish(); return }
        compose {
            ToolbarCommandEditor(source, newItem = requestedId == null, error = error, onBack = { finish() },
                onSave = { draft ->
                    val commands = readToolbarCommands(preferences)
                    when {
                        requestedId == null && commands.size >= MAX_TOOLBAR_COMMANDS -> error = getString(R.string.toolbar_commands_limit)
                        requestedId != null && commands.none { it.id == requestedId } -> error = getString(R.string.toolbar_command_missing)
                        else -> {
                            val updated = if (requestedId == null) commands + draft
                                else commands.map { if (it.id == requestedId) draft else it }
                            preferences.edit().putString(KEY_TOOLBAR_COMMANDS, serializeToolbarCommands(updated)).apply()
                            finish()
                        }
                    }
                }, onDelete = {
                    preferences.edit().putString(KEY_TOOLBAR_COMMANDS,
                        serializeToolbarCommands(readToolbarCommands(preferences).filterNot { it.id == requestedId })).apply()
                    finish()
                })
        }
    }

    companion object {
        private const val EXTRA_COMMAND_ID = "toolbar-command-id"
        fun intent(context: Context, id: String? = null) = Intent(context, ToolbarCommandEditActivity::class.java)
            .putExtra(EXTRA_COMMAND_ID, id)
    }
}
