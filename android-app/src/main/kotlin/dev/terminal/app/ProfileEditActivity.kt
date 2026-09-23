@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.terminal.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.painterResource
import dev.terminal.app.ui.SettingsChoice
import dev.terminal.app.ui.SettingsChoiceRow
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import dev.terminal.app.ui.terminalListItemShapes
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
            else if (intent.getBooleanExtra("duplicate", false)) store.duplicate(source) else source
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
            var storageId by rememberSaveable { mutableStateOf(initial.storageId) }
            val otherProfiles = remember { store.all().filter { it.id != initial.id } }
            var dirty by rememberSaveable { mutableStateOf(false) }
            var discard by rememberSaveable { mutableStateOf(false) }
            var deleting by rememberSaveable { mutableStateOf(false) }
            val editing = source != null && initial.id == source.id
            val back = { if (dirty) discard = true else finish() }
            val selectRuntime: (SessionRuntime) -> Unit = { option ->
                if (runtime != option.wireName) storageId = java.util.UUID.randomUUID().toString()
                val old = store.builtIns().first { it.runtime.wireName == runtime }
                val new = store.builtIns().first { it.runtime == option }
                if (shell == old.shell) shell = new.shell
                if (cwd == old.workingDirectory) cwd = new.workingDirectory
                if (arguments == JSONArray(old.shellArguments).toString()) arguments = JSONArray(new.shellArguments).toString()
                runtime = option.wireName
                dirty = true
            }
            val rootless = runtime in listOf("ROOTLESS", "ROOTLESS_UBUNTU", "ROOTLESS_ARCH")
            BackHandler { back() }
            SettingsPageScaffold(stringResource(if (editing) R.string.profile_settings else R.string.add_profile), back) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text(stringResource(R.string.profile_future_only), modifier = Modifier.padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ProfileSection(R.string.profile_identity) {
                        ProfileField(R.string.profile_name, name) { name = it; dirty = true }
                        SettingsChoiceRow(stringResource(R.string.profile_runtime), null,
                            listOf(SessionRuntime.NATIVE, SessionRuntime.ROOTLESS, SessionRuntime.AVF).map {
                                SettingsChoice(stringResource(if (it == SessionRuntime.ROOTLESS) R.string.runtime_rootless_title
                                    else runtimeTitleResource(it.wireName)), it.wireName)
                            }, if (rootless) SessionRuntime.ROOTLESS.wireName else runtime) {
                            selectRuntime(if (it == SessionRuntime.ROOTLESS.wireName && rootless) SessionRuntime.valueOf(runtime)
                                else SessionRuntime.valueOf(it))
                        }
                        if (rootless) SettingsChoiceRow(stringResource(R.string.profile_distribution), null,
                            listOf(SessionRuntime.ROOTLESS, SessionRuntime.ROOTLESS_UBUNTU, SessionRuntime.ROOTLESS_ARCH).map {
                                SettingsChoice(stringResource(runtimeTitleResource(it.wireName)), it.wireName)
                            }, runtime) { selectRuntime(SessionRuntime.valueOf(it)) }
                    }
                    ProfileSection(R.string.profile_storage) {
                        val targets = otherProfiles.filter {
                            if (rootless) it.runtime in listOf(SessionRuntime.ROOTLESS, SessionRuntime.ROOTLESS_UBUNTU, SessionRuntime.ROOTLESS_ARCH)
                            else it.runtime.wireName == runtime
                        }
                        val shared = targets.firstOrNull { it.runtime.wireName == runtime && it.storageId == storageId }
                        val currentLabel = if (shared != null) stringResource(R.string.profile_storage_shared, shared.name)
                            else stringResource(if (editing && storageId == initial.storageId && runtime == initial.runtime.wireName)
                                R.string.profile_storage_current else R.string.profile_storage_new)
                        SettingsChoiceRow(stringResource(R.string.profile_storage), null,
                            listOf(SettingsChoice(currentLabel, "current")) +
                                (if (shared != null || (editing && storageId == initial.storageId && runtime == initial.runtime.wireName))
                                    listOf(SettingsChoice(stringResource(R.string.profile_storage_new), "new")) else emptyList()) +
                                targets.filter { it != shared }.map {
                                    SettingsChoice(stringResource(R.string.profile_storage_shared, it.name), it.id)
                                }, "current") { selected ->
                            when (selected) {
                                "current" -> Unit
                                "new" -> { storageId = java.util.UUID.randomUUID().toString(); dirty = true }
                                else -> targets.first { it.id == selected }.let { target ->
                                    selectRuntime(target.runtime); storageId = target.storageId; dirty = true
                                }
                            }
                        }
                    }
                    Text(stringResource(R.string.profile_storage_hint), modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ProfileSection(R.string.profile_launch) {
                        ProfileField(R.string.profile_shell, shell) { shell = it; dirty = true }
                        ProfileField(R.string.profile_arguments, arguments, false, R.string.profile_arguments_hint) { arguments = it; dirty = true }
                        ProfileField(R.string.profile_directory, cwd) { cwd = it; dirty = true }
                        ProfileField(R.string.profile_startup, startup, false) { startup = it; dirty = true }
                    }
                    ProfileSection(R.string.profile_environment) {
                        ProfileField(R.string.profile_environment, environment, false, R.string.profile_environment_hint) { environment = it; dirty = true }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(onClick = {
                        runCatching {
                            store.save(SessionProfile(id, name.trim(), SessionRuntime.valueOf(runtime), shell,
                                parseProfileArguments(arguments), cwd, parseProfileEnvironment(environment), startup, storageId))
                        }.onSuccess { finish() }.onFailure { error = it.message }
                    }, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.profile_save)) }
                    if (editing) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = {
                                startActivity(Intent(this@ProfileEditActivity, ProfileEditActivity::class.java)
                                    .putExtra("profileId", id).putExtra("duplicate", true))
                            }, enabled = !dirty, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                shapes = ButtonDefaults.shapes()) {
                                Icon(painterResource(R.drawable.ic_material_symbol_content_copy_24), null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.profile_duplicate))
                            }
                            TextButton(onClick = { deleting = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                shapes = ButtonDefaults.shapes(),
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                                Icon(painterResource(R.drawable.ic_material_symbol_delete_24), null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.profile_delete))
                            }
                        }
                        if (dirty) Text(stringResource(R.string.profile_duplicate_save_first),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (deleting) AlertDialog(onDismissRequest = { deleting = false },
                title = { Text(stringResource(R.string.profile_delete)) },
                text = { Text(stringResource(R.string.profile_delete_message, source?.name.orEmpty())) },
                confirmButton = { TextButton(onClick = {
                    runCatching { store.delete(id) }.onSuccess { finish() }.onFailure { error = it.message; deleting = false }
                }) { Text(stringResource(R.string.profile_delete), color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(android.R.string.cancel)) } })
            if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(stringResource(R.string.profile_discard)) },
                confirmButton = { TextButton(onClick = { finish() }) { Text(stringResource(android.R.string.ok)) } },
                dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(android.R.string.cancel)) } })
        }
    }
}

@Composable internal fun ProfileSection(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(title), modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(content = content)
        }
    }
}

@Composable private fun ProfileField(label: Int, value: String, singleLine: Boolean = true,
    hint: Int? = null, change: (String) -> Unit) {
    var visible by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf(value) }
    ListItem(onClick = { draft = value; visible = true },
        supportingContent = { Text(value.ifBlank { stringResource(R.string.profile_not_set) },
            maxLines = 2, overflow = TextOverflow.Ellipsis) },
        verticalAlignment = Alignment.CenterVertically, shapes = terminalListItemShapes(),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent), modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(label)) }
    if (visible) AlertDialog(onDismissRequest = { visible = false }, title = { Text(stringResource(label)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(draft, { draft = it }, label = { Text(stringResource(label)) },
                    singleLine = singleLine, modifier = Modifier.fillMaxWidth())
                hint?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { Button(onClick = { change(draft); visible = false }, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.save))
        } },
        dismissButton = { TextButton(onClick = { visible = false }, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.cancel))
        } })
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
