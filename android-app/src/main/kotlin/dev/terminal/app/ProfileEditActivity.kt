@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.terminal.app

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.terminal.app.ui.SettingsPageScaffold
import dev.terminal.app.ui.TerminalActivity
import dev.terminal.app.ui.runtimeTitleResource
import dev.terminal.runtime.session.SessionProfile
import dev.terminal.runtime.session.SessionRuntime
import org.json.JSONArray

class ProfileEditActivity : TerminalActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SessionProfiles(this)
        val requestedId = intent.getStringExtra("profileId")
        val source = requestedId?.let(store::find)
        if (requestedId != null && source == null) { finish(); return }
        val initial = if (source == null) store.duplicate(store.builtIns().first()).copy(name = "")
            else if (source.builtIn || intent.getBooleanExtra("duplicate", false)) store.duplicate(source) else source
        compose {
            val id = rememberSaveable { initial.id }
            var name by rememberSaveable { mutableStateOf(initial.name) }
            var runtime by rememberSaveable { mutableStateOf(initial.runtime.wireName) }
            var shell by rememberSaveable { mutableStateOf(initial.shell) }
            var arguments by rememberSaveable { mutableStateOf(JSONArray(initial.shellArguments).toString()) }
            var cwd by rememberSaveable { mutableStateOf(initial.workingDirectory) }
            var startup by rememberSaveable { mutableStateOf(initial.startupCommand) }
            var environment by rememberSaveable { mutableStateOf(initial.environment.entries.joinToString("\n") { "${it.key}=${it.value}" }) }
            var error by rememberSaveable { mutableStateOf<String?>(null) }
            var expanded by remember { mutableStateOf(false) }
            var dirty by rememberSaveable { mutableStateOf(false) }
            var discard by rememberSaveable { mutableStateOf(false) }
            val back = { if (dirty) discard = true else finish() }
            BackHandler { back() }
            SettingsPageScaffold(stringResource(if (source == null || initial.id != source.id) R.string.add_profile else R.string.profile_edit), back) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.profile_future_only))
                    ProfileField(R.string.profile_name, name) { name = it; dirty = true }
                    Box {
                        OutlinedButton(onClick = { expanded = true }) { Text(stringResource(R.string.profile_runtime) + ": " + stringResource(runtimeTitleResource(runtime))) }
                        DropdownMenu(expanded, { expanded = false }) {
                            SessionRuntime.entries.forEach { option -> DropdownMenuItem(text = { Text(stringResource(runtimeTitleResource(option.wireName))) }, onClick = {
                                val old = store.builtIns().first { it.runtime.wireName == runtime }
                                val new = store.builtIns().first { it.runtime == option }
                                if (shell == old.shell) shell = new.shell
                                if (cwd == old.workingDirectory) cwd = new.workingDirectory
                                if (arguments == JSONArray(old.shellArguments).toString()) arguments = JSONArray(new.shellArguments).toString()
                                runtime = option.wireName; expanded = false; dirty = true
                            }) }
                        }
                    }
                    ProfileField(R.string.profile_shell, shell) { shell = it; dirty = true }
                    ProfileField(R.string.profile_arguments, arguments, false) { arguments = it; dirty = true }
                    Text(stringResource(R.string.profile_arguments_hint), style = MaterialTheme.typography.bodySmall)
                    ProfileField(R.string.profile_directory, cwd) { cwd = it; dirty = true }
                    ProfileField(R.string.profile_startup, startup, false) { startup = it; dirty = true }
                    ProfileField(R.string.profile_environment, environment, false) { environment = it; dirty = true }
                    Text(stringResource(R.string.profile_environment_hint), style = MaterialTheme.typography.bodySmall)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(onClick = {
                        runCatching {
                            store.save(SessionProfile(id, name.trim(), SessionRuntime.valueOf(runtime), shell,
                                parseProfileArguments(arguments), cwd, parseProfileEnvironment(environment), startup))
                        }.onSuccess { finish() }.onFailure { error = it.message }
                    }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.profile_save)) }
                }
            }
            if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(stringResource(R.string.profile_discard)) },
                confirmButton = { TextButton(onClick = { finish() }) { Text(stringResource(android.R.string.ok)) } },
                dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(android.R.string.cancel)) } })
        }
    }
}

@Composable private fun ProfileField(label: Int, value: String, singleLine: Boolean = true, change: (String) -> Unit) {
    OutlinedTextField(value, change, label = { Text(stringResource(label)) }, singleLine = singleLine, modifier = Modifier.fillMaxWidth())
}

internal fun parseProfileArguments(value: String): List<String> {
    val array = JSONArray(value)
    return List(array.length()) { require(array.get(it) is String) { "Arguments must be JSON strings" }; array.getString(it) }
}
internal fun parseProfileEnvironment(value: String): Map<String, String> = buildMap {
    value.lines().filter { it.isNotBlank() }.forEach { line ->
        val index = line.indexOf('=')
        require(index > 0) { "Environment entries must be KEY=value" }
        val key = line.substring(0, index)
        require(!containsKey(key)) { "Duplicate environment variable: $key" }
        put(key, line.substring(index + 1))
    }
}
