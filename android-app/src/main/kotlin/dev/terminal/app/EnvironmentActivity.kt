@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.terminal.app.ui.ProfileIcon
import dev.terminal.app.ui.FloatingToolbarAction
import dev.terminal.app.ui.SettingsPageScaffold
import dev.terminal.app.ui.TerminalActivity
import dev.terminal.app.ui.runtimeTitleResource
import dev.terminal.app.ui.SettingsItemSpacing
import dev.terminal.app.ui.settingsItemSurface
import dev.terminal.app.ui.terminalSettingsListItemColors
import dev.terminal.app.ui.terminalListItemShapes
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import dev.terminal.runtime.session.StorageRegistry
import dev.terminal.runtime.session.Environment
import dev.terminal.runtime.session.EnvironmentId
import dev.terminal.runtime.session.EnvironmentRuntime
import dev.terminal.runtime.session.EnvironmentDistribution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private val storageSizeUnit = Regex("(?<![\\p{L}])[kmgtpe]?b(?![\\p{L}])", RegexOption.IGNORE_CASE)

internal data class EnvironmentItem(val environment: Environment, val bytes: Long?,
    val profiles: List<Pair<String, String>>, val sessions: List<Pair<String, String>>,
    val runtimeState: String, val canDelete: Boolean, val incomplete: Boolean = false, val migratedLegacy: Boolean = false) {
    val runtime get() = environment.sessionRuntime.wireName
    val id get() = environment.id.value
    val storageId get() = environment.storage.id
    val name get() = environment.name
    val key get() = id
    val used get() = profiles.isNotEmpty() || sessions.isNotEmpty()
    val canStop get() = sessions.isEmpty() && runtimeState in setOf("READY", "IDLE", "ACTIVE")
    val canStart get() = runtimeState in setOf("STOPPED", "UNKNOWN")
    companion object {
        fun parse(encoded: String): List<EnvironmentItem> = JSONArray(encoded).let { array ->
            List(array.length()) { index -> array.getJSONObject(index).let { row ->
                fun names(field: String, title: String) = row.getJSONArray(field).let { entries ->
                    List(entries.length()) { i -> entries.getJSONObject(i).let { it.getString("id") to it.getString(title) } }
                }
                EnvironmentItem(Environment.fromJson(row), if (row.isNull("bytes")) null else row.getLong("bytes"),
                    names("profiles", "name"), names("sessions", "title"), row.getString("runtimeState"), row.getBoolean("canDelete"), row.optBoolean("incomplete"), row.optBoolean("migratedLegacy"))
            } }
        }
    }
}

@Composable private fun environmentRuntimeTitle(runtime: EnvironmentRuntime): String =
    stringResource(when (runtime) {
        EnvironmentRuntime.NATIVE -> R.string.runtime_native_title
        EnvironmentRuntime.ROOTLESS -> R.string.runtime_rootless_title
        EnvironmentRuntime.AVF -> R.string.environment_runtime_avf
    })

@Composable internal fun environmentDescription(environment: Environment): String {
    val runtime = environmentRuntimeTitle(environment.runtime)
    val distribution = environment.distribution?.let { stringResource(when (it) {
        EnvironmentDistribution.DEBIAN -> R.string.runtime_debian_title
        EnvironmentDistribution.UBUNTU -> R.string.runtime_ubuntu_title
        EnvironmentDistribution.ARCH -> R.string.runtime_arch_title
    }) }
    return listOfNotNull(runtime, distribution).joinToString(" · ")
}

@Composable private fun environmentState(state: String): String = stringResource(when (state) {
    "STOPPED" -> R.string.environment_stopped
    "ACTIVE" -> R.string.environment_active
    "READY", "IDLE" -> R.string.environment_ready
    "SUSPENDED" -> R.string.environment_suspended
    "PREPARING", "BOOTING", "STOPPING" -> R.string.environment_preparing
    else -> R.string.environment_unknown
})

class EnvironmentActivity : EnvironmentScreenActivity()

/** Environment presentation and service operations; each screen owns its Activity lifecycle. */
abstract class EnvironmentScreenActivity : TerminalActivity() {
    protected open val titleResource: Int get() = R.string.storage_title
    protected open fun selectedEnvironment(): EnvironmentId? = null
    private var runtime by mutableStateOf<IRuntimeService?>(null)
    private var imageInstallations by mutableStateOf(emptyList<dev.terminal.runtime.image.AvfImageInstallSnapshot>())
    private var installingImage by mutableStateOf<EnvironmentItem?>(null)
    private var binding = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { runtime = IRuntimeService.Stub.asInterface(binder) }
        override fun onServiceDisconnected(name: ComponentName) { runtime = null }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dev.terminal.runtime.image.AvfImageInstallMonitor(this).observe(this) { imageInstallations = it }
        val selected = try {
            selectedEnvironment()?.value
        } catch (_: IllegalArgumentException) {
            finish()
            return
        } catch (_: IllegalStateException) {
            finish()
            return
        }
        compose {
            var selectedKey by remember { mutableStateOf(selected) }
            var entries by remember { mutableStateOf<List<EnvironmentItem>?>(null) }
            var confirm by remember { mutableStateOf<EnvironmentItem?>(null) }
            var deleting by remember { mutableStateOf<EnvironmentItem?>(null) }
            var editingName by rememberSaveable { mutableStateOf(false) }
            var nameDraft by rememberSaveable { mutableStateOf("") }
            var operating by remember { mutableStateOf<Pair<EnvironmentItem, String>?>(null) }
            var software by remember { mutableStateOf<JSONObject?>(null) }
            var removeSoftwareTargetId by rememberSaveable { mutableStateOf<String?>(null) }
            var installRootlessTargetId by rememberSaveable { mutableStateOf<String?>(null) }
            var removeHome by rememberSaveable { mutableStateOf(false) }
            var imageInstallTargetId by rememberSaveable { mutableStateOf<String?>(null) }
            val imageInstallTarget = entries?.firstOrNull { it.id == imageInstallTargetId }
            var requestImageConfirmation by rememberSaveable { mutableStateOf(intent.getBooleanExtra(ProfileEditActivity.EXTRA_CONFIRM_IMAGE_INSTALL, false)) }
            var renaming by remember { mutableStateOf<Pair<EnvironmentItem, String>?>(null) }
            var error by remember { mutableStateOf<String?>(null) }
            var revision by remember { mutableIntStateOf(0) }
            val measurement = remember { Semaphore(1) }
            val scrollState = rememberLazyListState()
            LaunchedEffect(runtime, revision) {
                val service = runtime ?: return@LaunchedEffect
                while (true) {
                    runStorageOperation { withContext(Dispatchers.IO) { EnvironmentItem.parse(service.environmentsJson) } }
                        .onSuccess { rows ->
                            entries = rows
                        }.onFailure { entries = null; error = it.message }
                    delay(2000)
                }
            }
            LaunchedEffect(installingImage) {
                val row = installingImage ?: return@LaunchedEffect
                runStorageOperation { withContext(Dispatchers.IO) {
                    dev.terminal.runtime.image.RuntimeSetupCoordinator(applicationContext).requestImageInstallation(row.storageId)
                } }.onSuccess { revision++ }.onFailure { error = it.message }
                installingImage = null
            }
            LaunchedEffect(deleting) {
                val row = deleting ?: return@LaunchedEffect
                runStorageOperation { withContext(Dispatchers.IO) {
                    (runtime ?: error(getString(R.string.storage_unavailable))).deleteEnvironment(row.id)
                } }.onSuccess { result ->
                    if (result.isEmpty()) finish()
                    else error = result
                }.onFailure { error = it.message }
                deleting = null
            }
            LaunchedEffect(renaming) {
                val (row, value) = renaming ?: return@LaunchedEffect
                runStorageOperation { withContext(Dispatchers.IO) {
                    (runtime ?: error(getString(R.string.storage_unavailable))).renameEnvironment(row.id, value)
                } }.onSuccess { result ->
                    if (result.isEmpty()) {
                        entries = entries?.map { if (it.key == row.key) it.copy(environment = it.environment.copy(name = value.trim())) else it }
                        revision++
                    } else error = result
                }.onFailure { error = it.message }
                renaming = null
            }
            LaunchedEffect(operating) {
                val (row, operation) = operating ?: return@LaunchedEffect
                runStorageOperation { withContext(Dispatchers.IO) {
                    (runtime ?: error(getString(R.string.storage_unavailable))).operateEnvironment(row.id, operation)
                } }.onSuccess { if (it.isNotEmpty()) error = it else revision++ }.onFailure { error = it.message }
                operating = null
            }
            val detail = entries?.firstOrNull { it.key == selectedKey }
            LaunchedEffect(runtime, detail?.id, revision) {
                software = null
                val service = runtime ?: return@LaunchedEffect
                val row = detail?.takeUnless { it.environment.runtime == EnvironmentRuntime.NATIVE } ?: return@LaunchedEffect
                while (true) {
                    runStorageOperation { withContext(Dispatchers.IO) { JSONObject(service.getEnvironmentSoftwareJson(row.id)) } }
                        .onSuccess { software = it }.onFailure { software = null }
                    delay(1000)
                }
            }
            val softwareBusy = software?.optBoolean("busy") == true
            LaunchedEffect(detail?.id, requestImageConfirmation) {
                if (requestImageConfirmation && detail != null) {
                    imageInstallTargetId = detail.id
                    requestImageConfirmation = false
                }
            }
            SettingsPageScaffold(stringResource(titleResource), { finish() },
                floatingActionButtonPosition = FabPosition.Center,
                floatingActionButton = {
                    detail?.let { row ->
                        EnvironmentActions(
                            enabled = runtime != null && renaming == null && deleting == null && operating == null && !softwareBusy,
                            canStart = row.canStart, canStop = row.canStop,
                            onStart = { operating = row to "START" }, onStop = { operating = row to "STOP" },
                            canDelete = row.canDelete,
                            onRename = { nameDraft = row.name.orEmpty(); editingName = true },
                            onDelete = { confirm = row },
                        )
                    }
                }) { padding ->
                LazyColumn(Modifier.fillMaxSize().padding(padding),
                    state = scrollState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp,
                        bottom = if (detail != null) FloatingToolbarDefaults.ContainerSize + FloatingToolbarDefaults.ScreenOffset * 2 else 8.dp)) {
                    item(key = "description") {
                        Text(stringResource(R.string.storage_summary), Modifier.padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    error?.let { message -> item(key = "operation-error") {
                        Card(Modifier.fillMaxWidth().padding(top = 20.dp), shape = MaterialTheme.shapes.extraLarge,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Column(Modifier.padding(16.dp)) {
                                Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                                TextButton(onClick = { error = null }, shapes = ButtonDefaults.shapes()) {
                                    Text(stringResource(R.string.usb_dismiss_error))
                                }
                            }
                        }
                    } }
                    if (entries == null) item(key = "loading") {
                        EnvironmentSection(R.string.storage_title) {
                            ListItem(modifier = Modifier.settingsItemSurface(), colors = terminalSettingsListItemColors(),
                                leadingContent = if (runtime != null) { { LoadingIndicator(Modifier.size(32.dp)) } } else null,
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(if (runtime == null) R.string.storage_unavailable else R.string.storage_loading))
                            }
                        }
                    }
                    if (selectedKey != null && entries != null && detail == null) item(key = "missing") {
                        EnvironmentSection(R.string.storage_title) { EnvironmentEmpty(R.string.storage_missing) }
                    } else if (detail != null) {
                        item(key = "information") { EnvironmentSection(R.string.storage_information) {
                            EnvironmentInfoRow(R.string.storage_name, detail.name ?: stringResource(R.string.profile_not_set))
                            EnvironmentInfoRow(R.string.profile_runtime, environmentDescription(detail.environment))
                            EnvironmentInfoRow(R.string.environment_runtime_state, environmentState(detail.runtimeState))
                            EnvironmentInfoRow(R.string.storage_id, detail.id)
                            EnvironmentInfoRow(R.string.storage_size, environmentSize(detail, runtime, measurement, revision))
                            EnvironmentInfoRow(R.string.storage_profile_count, stringResource(R.string.storage_count_value, detail.profiles.size))
                            EnvironmentInfoRow(R.string.storage_session_count, stringResource(R.string.storage_count_value, detail.sessions.size))
                            detail.environment.runtimeMetadata.vmInstanceId?.let { instance ->
                                EnvironmentInfoRow(R.string.environment_vm_identity, "droid-$instance")
                                EnvironmentInfoRow(R.string.environment_disks, "system.img · home.img · config.img")
                            }
                        } }
                        item(key = "profiles") {
                            EnvironmentSection(R.string.storage_profiles) {
                                if (detail.profiles.isEmpty()) EnvironmentEmpty()
                                detail.profiles.forEach { profile ->
                                    ListItem(onClick = { startActivity(Intent(this@EnvironmentScreenActivity, ProfileEditActivity::class.java).putExtra("profileId", profile.first)) },
                                        leadingContent = { ProfileIcon("auto", detail.runtime) },
                                        trailingContent = { EnvironmentChevron() }, verticalAlignment = Alignment.CenterVertically,
                                        colors = terminalSettingsListItemColors(),
                                        shapes = terminalListItemShapes(), modifier = Modifier.fillMaxWidth()) { Text(profile.second) }
                                }
                            }
                        }
                        if (detail.sessions.isNotEmpty()) item(key = "sessions") {
                            EnvironmentSection(R.string.storage_sessions) {
                                detail.sessions.forEach { session ->
                                    ListItem(modifier = Modifier.settingsItemSurface(), supportingContent = { Text(session.first, style = MaterialTheme.typography.bodySmall) },
                                        colors = terminalSettingsListItemColors(),
                                        verticalAlignment = Alignment.CenterVertically) { Text(session.second.ifBlank { session.first }) }
                                }
                            }
                        }
                        if (detail.environment.runtime == EnvironmentRuntime.ROOTLESS) item(key = "rootless-software") {
                            RootlessEnvironmentSoftwareSetting(software, runtime != null && operating == null && deleting == null,
                                onInstall = { installRootlessTargetId = detail.id },
                                onRemove = { removeHome = false; removeSoftwareTargetId = detail.id })
                        }
                        if (detail.environment.runtime == EnvironmentRuntime.AVF) item(key = "avf-image") {
                            AvfEnvironmentImageSetting(detail.storageId, imageInstallations.firstOrNull {
                                it.storageId == detail.storageId || detail.migratedLegacy && it.storageId == "legacy"
                            },
                                revision, savedStorage = true, requesting = imageInstallTarget != null,
                                onInstall = { imageInstallTargetId = detail.id },
                                canRemove = software?.optBoolean("canRemove") == true && operating == null && deleting == null,
                                hasData = software?.optBoolean("hasData") == true,
                                onRemove = { removeHome = false; removeSoftwareTargetId = detail.id })
                        }
                    } else if (selectedKey == null && entries != null) {
                        listOf(true, false).forEach { used ->
                            val group = entries.orEmpty().filter { it.used == used }
                            if (!used && group.isEmpty()) return@forEach
                            val title = if (used) R.string.storage_used else R.string.storage_unused
                            item(key = "group:$used") {
                                Text(stringResource(title), Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
                                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            }
                            if (group.isEmpty()) item(key = "empty:$used") { EnvironmentGroupCard(true, true) { EnvironmentEmpty() } }
                            // Keep each row lazy with small gaps and reduced inner corners.
                            itemsIndexed(group, key = { _, row -> row.key }) { index, row ->
                                EnvironmentGroupCard(index == 0, index == group.lastIndex) {
                                    EnvironmentRow(row, runtime, measurement) {
                                        startActivity(EnvironmentDetailActivity.intent(this@EnvironmentScreenActivity, row.id))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            entries?.firstOrNull { it.key == selectedKey }?.let { row ->
                if (editingName) AlertDialog(onDismissRequest = { editingName = false },
                    title = { Text(stringResource(R.string.storage_name)) },
                    text = { Column {
                        OutlinedTextField(nameDraft, { value ->
                            nameDraft = value.filterNot { it.isISOControl() }.take(StorageRegistry.MAX_NAME_LENGTH)
                        }, singleLine = true, label = { Text(stringResource(R.string.storage_name)) }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.storage_name_hint), style = MaterialTheme.typography.bodySmall)
                    } },
                    confirmButton = { Button(enabled = nameDraft.isNotBlank(), onClick = { renaming = row to nameDraft; editingName = false }, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.save))
                    } },
                    dismissButton = { TextButton(onClick = { editingName = false }, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.cancel))
                    } })
            }
            imageInstallTarget?.let { row -> AlertDialog(onDismissRequest = { imageInstallTargetId = null },
                title = { Text(stringResource(R.string.avf_image_install_confirm_title)) },
                text = { Text(stringResource(R.string.avf_image_install_confirm_message, row.name)) },
                confirmButton = { TextButton(onClick = {
                    imageInstallTargetId = null
                    installingImage = row
                }) { Text(stringResource(R.string.avf_image_install)) } },
                dismissButton = { TextButton(onClick = { imageInstallTargetId = null }) { Text(stringResource(R.string.avf_image_install_later)) } }) }
            entries?.firstOrNull { it.id == installRootlessTargetId }?.let { row ->
                AlertDialog(onDismissRequest = { installRootlessTargetId = null },
                    title = { Text(stringResource(R.string.environment_distribution_install)) },
                    text = { Column {
                        Text(row.name)
                        Text(stringResource(R.string.rootless_install_note), style = MaterialTheme.typography.bodySmall)
                    } },
                    confirmButton = { TextButton(onClick = { installRootlessTargetId = null; operating = row to "INSTALL" }) {
                        Text(stringResource(R.string.environment_distribution_install))
                    } },
                    dismissButton = { TextButton(onClick = { installRootlessTargetId = null }) { Text(stringResource(R.string.cancel)) } })
            }
            entries?.firstOrNull { it.id == removeSoftwareTargetId }?.let { row ->
                val rootless = row.environment.runtime == EnvironmentRuntime.ROOTLESS
                val label = if (rootless) R.string.environment_distribution_remove else R.string.environment_debian_remove
                AlertDialog(onDismissRequest = { removeSoftwareTargetId = null },
                    title = { Text(stringResource(label)) },
                    text = { Column {
                        Text(row.name)
                        Text(stringResource(if (rootless) R.string.environment_distribution_remove_message else R.string.environment_debian_remove_message))
                        if (rootless) Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = removeHome, onCheckedChange = { removeHome = it })
                            Text(stringResource(R.string.rootless_remove_home))
                        }
                    } },
                    confirmButton = { TextButton(onClick = {
                        removeSoftwareTargetId = null
                        operating = row to if (rootless && removeHome) "REMOVE_SOFTWARE_AND_HOME" else "REMOVE_SOFTWARE"
                    }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(label)) } },
                    dismissButton = { TextButton(onClick = { removeSoftwareTargetId = null }) { Text(stringResource(R.string.cancel)) } })
            }
            confirm?.let { row -> AlertDialog(onDismissRequest = { confirm = null },
                title = { Text(stringResource(R.string.storage_delete)) },
                text = { Text(stringResource(R.string.storage_delete_confirm, row.name ?: row.id)) },
                confirmButton = { TextButton(onClick = { confirm = null; deleting = row }, shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.storage_delete)) } },
                dismissButton = { TextButton(onClick = { confirm = null }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) } }) }
        }
    }
    @Composable private fun environmentSize(row: EnvironmentItem, service: IRuntimeService?, measurement: Semaphore, revision: Int = 0): String {
        var state by remember(row.key) { mutableStateOf(StorageSizeState(row.bytes?.let { StorageRegistry.Usage(it, row.incomplete) })) }
        LaunchedEffect(row.key, service, revision) {
            val owner = service ?: return@LaunchedEffect
            while (true) {
                val result = runStorageOperation { measurement.withPermit { withContext(Dispatchers.IO) {
                    StorageRegistry.Usage.parse(owner.measureEnvironmentUsageJson(row.id))
                } } }
                state = state.updated(result)
                delay(60_000)
            }
        }
        val usage = state.usage ?: return stringResource(if (state.failed) R.string.storage_size_unknown else R.string.storage_size_calculating)
        val formatted = Formatter.formatShortFileSize(this, usage.bytes).replace(storageSizeUnit) { unit ->
            unit.value.uppercase(Locale.ROOT)
        }
        return if (usage.incomplete) stringResource(R.string.storage_size_partial, formatted) else formatted
    }
    @Composable private fun EnvironmentRow(row: EnvironmentItem, service: IRuntimeService?, measurement: Semaphore, click: () -> Unit) {
        val size = environmentSize(row, service, measurement)
        val runtimeTitle = environmentRuntimeTitle(row.environment.runtime)
        val colors = terminalSettingsListItemColors()
        ListItem(onClick = click, supportingContent = { Text(runtimeTitle) },
            trailingContent = { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(size, style = MaterialTheme.typography.labelLarge)
                EnvironmentChevron()
            } }, verticalAlignment = Alignment.CenterVertically,
            shapes = terminalListItemShapes(), modifier = Modifier.fillMaxWidth(), colors = colors) { Text(row.name ?: runtimeTitle) }
    }
    @Composable private fun EnvironmentInfoRow(label: Int, value: String) {
        ListItem(modifier = Modifier.settingsItemSurface(), supportingContent = { Text(value) }, verticalAlignment = Alignment.CenterVertically,
            colors = terminalSettingsListItemColors()) {
            Text(stringResource(label))
        }
    }
    @Composable private fun EnvironmentActions(enabled: Boolean, canDelete: Boolean, canStart: Boolean, canStop: Boolean,
        onStart: () -> Unit, onStop: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
        HorizontalFloatingToolbar(expanded = true) {
            if (canStart) FloatingToolbarAction(stringResource(R.string.environment_start), R.drawable.ic_material_symbol_power_settings_new_24,
                enabled = enabled, onClick = onStart)
            if (canStop) FloatingToolbarAction(stringResource(R.string.environment_stop), R.drawable.ic_material_symbol_close_24,
                enabled = enabled, onClick = onStop)
            FloatingToolbarAction(stringResource(R.string.storage_rename), R.drawable.ic_toolbar_edit,
                enabled = enabled, onClick = onRename)
            FloatingToolbarAction(stringResource(R.string.storage_delete), R.drawable.ic_material_symbol_delete_24,
                enabled = enabled && canDelete, onClick = onDelete, destructive = true)
        }
    }
    @Composable private fun EnvironmentGroupCard(first: Boolean, last: Boolean, content: @Composable ColumnScope.() -> Unit) {
        val corners = MaterialTheme.shapes.extraLarge
        Card(Modifier.fillMaxWidth().padding(bottom = if (last) 0.dp else SettingsItemSpacing), shape = corners.copy(
            topStart = if (first) corners.topStart else CornerSize(4.dp),
            topEnd = if (first) corners.topEnd else CornerSize(4.dp),
            bottomStart = if (last) corners.bottomStart else CornerSize(4.dp),
            bottomEnd = if (last) corners.bottomEnd else CornerSize(4.dp)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), content = content)
    }
    @Composable private fun EnvironmentEmpty(message: Int = R.string.storage_none) {
        ListItem(modifier = Modifier.settingsItemSurface(), colors = terminalSettingsListItemColors()) {
            Text(stringResource(message), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    @Composable private fun EnvironmentChevron() {
        Icon(painterResource(R.drawable.ic_material_symbol_chevron_right_24), null)
    }

    override fun onStart() {
        super.onStart()
        binding = bindService(Intent(this, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE)
    }
    override fun onStop() {
        runtime = null
        if (binding) unbindService(connection)
        binding = false
        super.onStop()
    }
}
