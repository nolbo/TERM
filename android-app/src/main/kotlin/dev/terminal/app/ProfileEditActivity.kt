@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.terminal.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import dev.terminal.app.ui.SettingsChoice
import dev.terminal.app.ui.SettingsChoiceRow
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import dev.terminal.app.ui.SettingsGroup
import dev.terminal.app.ui.SettingsItemSpacing
import dev.terminal.app.ui.settingsItemSurface
import dev.terminal.app.ui.terminalSettingsListItemColors
import dev.terminal.app.ui.terminalListItemShapes
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.terminal.app.ui.FloatingToolbarAction
import dev.terminal.app.ui.SettingsPageScaffold
import dev.terminal.app.ui.TerminalActivity
import dev.terminal.app.ui.runtimeTitleResource
import dev.terminal.runtime.session.SessionProfile
import dev.terminal.runtime.session.SessionRuntime
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.terminal.runtime.session.Environment
import dev.terminal.runtime.session.EnvironmentDistribution
import dev.terminal.runtime.session.EnvironmentId
import dev.terminal.runtime.session.EnvironmentRegistry

class ProfileEditActivity : TerminalActivity() {
    private var hasAvfPrivileges by mutableStateOf(false)
    private var environmentRevision by mutableIntStateOf(0)
    private var storagePermissionGranted by mutableStateOf(false)
    override fun onResume() {
        super.onResume()
        hasAvfPrivileges = SessionProfiles(this).hasAvfPrivileges()
        storagePermissionGranted = android.os.Environment.isExternalStorageManager()
        environmentRevision++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = SessionProfiles(this)
        hasAvfPrivileges = store.hasAvfPrivileges()
        val requestedId = intent.getStringExtra("profileId")
        val source = requestedId?.let(store::find)
        if (requestedId != null && source == null) { finish(); return }
        if (intent.getBooleanExtra(EXTRA_CONFIRM_IMAGE_INSTALL, false) && source?.environmentId != null) {
            startActivity(EnvironmentDetailActivity.intent(this, requireNotNull(source.environmentId).value).putExtra(EXTRA_CONFIRM_IMAGE_INSTALL, true))
            finish()
            return
        }
        val initial = if (source == null) store.duplicate(store.builtIns().first())
            else if (intent.getBooleanExtra("duplicate", false)) store.duplicate(source).copy(
                storageId = source.storageId, environmentId = source.environmentId) else source
        compose {
            val id = rememberSaveable { initial.id }
            val otherProfiles = remember { store.all().filter { it.id != id } }
            var runtime by rememberSaveable { mutableStateOf(initial.runtime.wireName) }
            var avfDistribution by rememberSaveable { mutableStateOf((source?.environmentId ?: initial.environmentId)?.let {
                store.environments().firstOrNull { environment -> environment.id == it }?.distribution?.name
            } ?: "DEBIAN") }
            var nameIsAutomatic by rememberSaveable { mutableStateOf(source == null) }
            var name by rememberSaveable { mutableStateOf(if (source == null)
                defaultProfileName(initial.runtime, EnvironmentDistribution.valueOf(avfDistribution), otherProfiles.map { it.name })
                else initial.name) }
            var shell by rememberSaveable { mutableStateOf(initial.shell) }
            var arguments by rememberSaveable { mutableStateOf(JSONArray(initial.shellArguments).toString()) }
            var cwd by rememberSaveable { mutableStateOf(initial.workingDirectory) }
            var startup by rememberSaveable { mutableStateOf(initial.startupCommand) }
            var environment by rememberSaveable { mutableStateOf(initial.environment.entries.joinToString("\n") { "${it.key}=${it.value}" }) }
            var error by rememberSaveable { mutableStateOf<String?>(null) }
            var storageId by rememberSaveable { mutableStateOf(initial.storageId) }
            var storageChoice by rememberSaveable { mutableStateOf(when {
                source == null -> "fresh"
                initial.id == source.id -> "keep"
                else -> "existing"
            }) }
            var freshStorageId by rememberSaveable { mutableStateOf(if (storageChoice == "fresh") initial.storageId else java.util.UUID.randomUUID().toString()) }
            var freshEnvironmentId by rememberSaveable { mutableStateOf(java.util.UUID.randomUUID().toString()) }
            var selectedEnvironmentId by rememberSaveable { mutableStateOf(initial.environmentId?.value.orEmpty()) }
            var freshStorageName by rememberSaveable { mutableStateOf("") }
            var freshInternalStorageAccess by rememberSaveable { mutableStateOf(false) }
            var environments by remember { mutableStateOf(emptyList<Environment>()) }
            val scope = rememberCoroutineScope()
            var saving by remember { mutableStateOf(false) }
            LaunchedEffect(environmentRevision) {
                runCatching { withContext(Dispatchers.IO) { store.environments() } }
                    .onSuccess { environments = it }.onFailure { error = it.message }
            }
            var icon by rememberSaveable { mutableStateOf(initial.icon) }
            var palette by rememberSaveable { mutableStateOf(initial.palette?.joinToString(",")) }
            var dirty by rememberSaveable { mutableStateOf(false) }
            var discard by rememberSaveable { mutableStateOf(false) }
            var deleting by rememberSaveable { mutableStateOf(false) }
            val editing = source != null && initial.id == source.id
            val back = { if (dirty) discard = true else finish() }
            fun updateAutomaticName(environmentName: String? = null) {
                if (nameIsAutomatic) name = defaultProfileName(SessionRuntime.valueOf(runtime),
                    EnvironmentDistribution.valueOf(avfDistribution), otherProfiles.map { it.name }, environmentName)
            }
            val updateRuntime: (SessionRuntime) -> Unit = { option ->
                val old = store.builtIns().first { it.runtime.wireName == runtime }
                val new = store.builtIns().first { it.runtime == option }
                if (shell == old.shell) shell = new.shell
                if (cwd == old.workingDirectory) cwd = new.workingDirectory
                if (arguments == JSONArray(old.shellArguments).toString()) arguments = JSONArray(new.shellArguments).toString()
                runtime = option.wireName
                dirty = true
            }
            val selectRuntime: (SessionRuntime) -> Unit = { option ->
                if (runtime != option.wireName) {
                    freshStorageId = java.util.UUID.randomUUID().toString()
                    storageId = freshStorageId
                    storageChoice = "fresh"
                    selectedEnvironmentId = ""
                    freshEnvironmentId = java.util.UUID.randomUUID().toString()
                }
                updateRuntime(option)
                updateAutomaticName()
            }
            val selectEnvironment: (Environment) -> Unit = { selected ->
                updateRuntime(selected.sessionRuntime)
                avfDistribution = selected.distribution?.name ?: "DEBIAN"
                selectedEnvironmentId = selected.id.value
                storageId = selected.storage.id
                updateAutomaticName(selected.name)
            }
            val rootless = runtime in listOf("ROOTLESS", "ROOTLESS_UBUNTU", "ROOTLESS_ARCH")
            val saveProfile: () -> Unit = {
                saving = true
                scope.launch {
                    val fresh = storageChoice == "fresh"
                    runCatching {
                        val profile = SessionProfile(id, name.trim(), SessionRuntime.valueOf(runtime), shell,
                            parseProfileArguments(arguments), cwd, parseProfileEnvironment(environment), startup, storageId, icon, palette?.split(','),
                            if (fresh) null else selectedEnvironmentId.takeIf(String::isNotEmpty)?.let(::EnvironmentId))
                        withContext(Dispatchers.IO) {
                            if (fresh) store.save(profile, Environment.create(profile.runtime,
                                freshStorageName.ifBlank { EnvironmentRegistry.defaultEnvironmentName(name) }, freshStorageId, EnvironmentId(freshEnvironmentId),
                                internalStorageAccess = freshInternalStorageAccess,
                                avfDistribution = EnvironmentDistribution.valueOf(avfDistribution)))
                            else { store.save(profile); requireNotNull(store.find(profile.id)) }
                        }
                    }.onSuccess { profile ->
                        if (fresh && profile.runtime == SessionRuntime.AVF) {
                            startActivity(EnvironmentDetailActivity.intent(this@ProfileEditActivity, requireNotNull(profile.environmentId).value)
                                .putExtra(EXTRA_CONFIRM_IMAGE_INSTALL, true))
                        }
                        finish()
                    }.onFailure { error = it.message }
                    saving = false
                }
            }
            BackHandler { back() }
            SettingsPageScaffold(
                stringResource(if (editing) R.string.profile_settings else R.string.add_profile), back,
                floatingActionButtonPosition = FabPosition.Center,
                floatingActionButton = {
                    if (editing) ProfileActions(saving = saving, canSave = storageId.isNotEmpty(), dirty = dirty,
                        onSave = saveProfile,
                        onDuplicate = {
                            startActivity(Intent(this@ProfileEditActivity, ProfileEditActivity::class.java)
                                .putExtra("profileId", id).putExtra("duplicate", true))
                        },
                        onDelete = { deleting = true })
                },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp,
                        bottom = if (editing) FloatingToolbarDefaults.ContainerSize + FloatingToolbarDefaults.ScreenOffset * 2 else 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text(stringResource(R.string.profile_future_only), modifier = Modifier.padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ProfileSection(R.string.profile_identity) {
                        ProfileField(R.string.profile_name, name, confirmLabel = R.string.confirm) {
                            if (it != name) nameIsAutomatic = false
                            name = it
                            dirty = true
                        }
                        dev.terminal.app.ui.ProfileIconSetting(icon, runtime) { icon = it; dirty = true }
                    }
                    ProfileSection(R.string.profile_palette) {
                        ProfilePaletteSetting(palette?.split(','), otherProfiles) {
                            palette = it?.joinToString(","); dirty = true
                        }
                    }
                    ProfileSection(R.string.profile_storage) {
                        val current = environments.firstOrNull { it.id.value == initial.environmentId?.value }
                        val targets = environments.sortedBy { it.name }
                        val choices = buildList {
                            if (editing && current != null)
                                add(SettingsChoice(stringResource(R.string.profile_storage_current), "keep"))
                            add(SettingsChoice(stringResource(R.string.profile_storage_new), "fresh"))
                            add(SettingsChoice(stringResource(R.string.profile_storage_existing), "existing"))
                        }
                        SettingsChoiceRow(stringResource(R.string.profile_storage), null, choices, storageChoice,
                            confirmLabel = R.string.confirm) { selected ->
                            storageChoice = selected
                            when (selected) {
                                "keep" -> current?.let(selectEnvironment)
                                "fresh" -> { selectedEnvironmentId = ""; storageId = freshStorageId; updateAutomaticName() }
                                else -> { selectedEnvironmentId = ""; storageId = ""; updateAutomaticName() }
                            }
                            dirty = true
                        }
                        if (storageChoice == "fresh") {
                            SettingsChoiceRow(stringResource(R.string.profile_runtime), null,
                                listOf(SessionRuntime.NATIVE, SessionRuntime.ROOTLESS, SessionRuntime.AVF)
                                    .filter { it != SessionRuntime.AVF || hasAvfPrivileges }.map {
                                    SettingsChoice(stringResource(if (it == SessionRuntime.ROOTLESS) R.string.runtime_rootless_title
                                        else runtimeTitleResource(it.wireName)), it.wireName)
                                }, if (rootless) SessionRuntime.ROOTLESS.wireName else runtime,
                                selectedLabel = stringResource(runtimeTitleResource(runtime))) {
                                selectRuntime(if (it == SessionRuntime.ROOTLESS.wireName && rootless) SessionRuntime.valueOf(runtime)
                                    else SessionRuntime.valueOf(it))
                            }
                            if (rootless) SettingsChoiceRow(stringResource(R.string.profile_distribution), null,
                                listOf(SessionRuntime.ROOTLESS, SessionRuntime.ROOTLESS_UBUNTU, SessionRuntime.ROOTLESS_ARCH).map {
                                    SettingsChoice(stringResource(runtimeTitleResource(it.wireName)), it.wireName)
                                }, runtime) { selectRuntime(SessionRuntime.valueOf(it)) }
                            if (runtime == SessionRuntime.AVF.wireName) SettingsChoiceRow(stringResource(R.string.profile_distribution), null,
                                EnvironmentDistribution.entries.map {
                                    SettingsChoice(it.avfDistribution.displayName, it.name)
                                }, avfDistribution) { selected ->
                                if (selected != avfDistribution) {
                                    avfDistribution = selected
                                    freshStorageId = java.util.UUID.randomUUID().toString()
                                    storageId = freshStorageId
                                    freshEnvironmentId = java.util.UUID.randomUUID().toString()
                                    updateAutomaticName()
                                    dirty = true
                                }
                            }
                            val suggestedName = if (name.isBlank()) "" else EnvironmentRegistry.defaultEnvironmentName(name, environments.map { it.name })
                            ProfileField(R.string.profile_storage_name, freshStorageName.ifBlank { suggestedName },
                                hint = R.string.profile_storage_name_hint) {
                                val value = it.filterNot { char -> char.isISOControl() }.take(dev.terminal.runtime.session.StorageRegistry.MAX_NAME_LENGTH)
                                freshStorageName = if (value.trim() == suggestedName) "" else value
                                dirty = true
                            }
                        } else if (storageChoice == "existing") Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(SettingsItemSpacing)) {
                            targets.forEach { target ->
                                ProfileEnvironmentRow(target.name, environmentDescription(target), selectedEnvironmentId == target.id.value) {
                                    selectEnvironment(target)
                                }
                            }
                            if (targets.isEmpty()) ListItem(modifier = Modifier.settingsItemSurface(), colors = terminalSettingsListItemColors()) {
                                Text(stringResource(R.string.profile_storage_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (storageChoice == "keep") environments.firstOrNull { it.id.value == selectedEnvironmentId }?.let { selected ->
                            ListItem(onClick = { startActivity(EnvironmentDetailActivity.intent(this@ProfileEditActivity, selected.id.value)) },
                                supportingContent = { Text(environmentDescription(selected)) }, shapes = terminalListItemShapes(),
                                colors = terminalSettingsListItemColors()) { Text(selected.name) }
                        }
                    }
                    Text(stringResource(R.string.profile_storage_hint), modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (storageChoice == "fresh") ProfileSection(R.string.environment_permissions) {
                        EnvironmentStorageAccessSettings(
                            if (runtime == SessionRuntime.NATIVE.wireName) dev.terminal.runtime.session.EnvironmentRuntime.NATIVE
                            else if (runtime == SessionRuntime.AVF.wireName) dev.terminal.runtime.session.EnvironmentRuntime.AVF
                            else dev.terminal.runtime.session.EnvironmentRuntime.ROOTLESS,
                            freshInternalStorageAccess, storagePermissionGranted, !saving,
                            onChange = { freshInternalStorageAccess = it; dirty = true },
                            onOpenSettings = { openInternalStorageSettings(this@ProfileEditActivity) })
                    }
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
                    if (editing) {
                        if (dirty) Text(stringResource(R.string.profile_duplicate_save_first),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Button(onClick = saveProfile, enabled = !saving && storageId.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
                            Text(stringResource(R.string.profile_save))
                        }
                    }
                }
            }
            if (deleting) AlertDialog(onDismissRequest = { deleting = false },
                title = { Text(stringResource(R.string.profile_delete)) },
                text = { Text(stringResource(R.string.profile_delete_message, source?.name.orEmpty())) },
                confirmButton = { TextButton(onClick = {
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { store.delete(id) } }
                            .onSuccess { finish() }.onFailure { error = it.message; deleting = false }
                    }
                }) { Text(stringResource(R.string.profile_delete), color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(android.R.string.cancel)) } })
            if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(stringResource(R.string.profile_discard)) },
                confirmButton = { TextButton(onClick = { finish() }) { Text(stringResource(android.R.string.ok)) } },
                dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(android.R.string.cancel)) } })
        }
    }

    companion object { const val EXTRA_CONFIRM_IMAGE_INSTALL = "confirm_avf_image_install" }
}

@Composable private fun ProfileActions(
    saving: Boolean,
    canSave: Boolean,
    dirty: Boolean,
    onSave: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    HorizontalFloatingToolbar(expanded = true) {
        FloatingToolbarAction(stringResource(R.string.profile_save), R.drawable.ic_material_symbol_save_24,
            enabled = !saving && canSave, onClick = onSave)
        FloatingToolbarAction(stringResource(R.string.profile_duplicate), R.drawable.ic_material_symbol_content_copy_24,
            enabled = !saving && !dirty, onClick = onDuplicate)
        FloatingToolbarAction(stringResource(R.string.profile_delete), R.drawable.ic_material_symbol_delete_24,
            enabled = !saving, onClick = onDelete, destructive = true)
    }
}

@Composable private fun ProfileEnvironmentRow(title: String, supporting: String?, selected: Boolean, onClick: () -> Unit) {
    ListItem(onClick = onClick, supportingContent = supporting?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        trailingContent = { RadioButton(selected = selected, onClick = null) },
        verticalAlignment = Alignment.CenterVertically, shapes = terminalListItemShapes(),
        colors = terminalSettingsListItemColors(), modifier = Modifier.fillMaxWidth().semantics {
            role = Role.RadioButton
            this.selected = selected
        }) { Text(title) }
}

@Composable internal fun ProfileSection(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(title), modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        SettingsGroup(content = content)
    }
}

@Composable private fun ProfileField(label: Int, value: String, singleLine: Boolean = true,
    hint: Int? = null, confirmLabel: Int = R.string.save, change: (String) -> Unit) {
    dev.terminal.app.ui.SettingsTextRow(label, value, singleLine, hint, confirmLabel = confirmLabel, onChange = change)
}

internal fun parseProfileArguments(value: String): List<String> {
    val array = JSONArray(value)
    return List(array.length()) { require(array.get(it) is String) { "Arguments must be JSON strings" }; array.getString(it) }
}
internal fun defaultProfileName(runtime: SessionRuntime, avfDistribution: EnvironmentDistribution,
    existingNames: Collection<String>, environmentName: String? = null): String {
    val base = environmentName ?: when (runtime) {
        SessionRuntime.NATIVE -> "Native"
        SessionRuntime.ROOTLESS -> EnvironmentDistribution.DEBIAN.avfDistribution.displayName
        SessionRuntime.ROOTLESS_UBUNTU -> EnvironmentDistribution.UBUNTU.avfDistribution.displayName
        SessionRuntime.ROOTLESS_ARCH -> EnvironmentDistribution.ARCH.avfDistribution.displayName
        SessionRuntime.AVF -> avfDistribution.avfDistribution.displayName
    }
    return EnvironmentRegistry.defaultName(base, existingNames)
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
