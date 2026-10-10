@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.terminal.app

import android.content.*
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.terminal.app.ui.*
import dev.terminal.runtime.backup.*
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import dev.terminal.runtime.session.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID

/** SAF and presentation only. All archive IO, cancellation and activation belong to :vmhost. */
class EnvironmentBackupActivity : TerminalActivity() {
    private var runtime by mutableStateOf<IRuntimeService?>(null)
    private var binding = false
    private var selectedUri by mutableStateOf<String?>(null)
    private var selectedName by mutableStateOf("")
    private var operationId by mutableStateOf<String?>(null)
    private var error by mutableStateOf<String?>(null)
    private var submitting by mutableStateOf(false)
    // Deliberately excluded from saved state, preferences and operation journals.
    private var password by mutableStateOf("")
    private var confirmation by mutableStateOf("")
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { runtime = IRuntimeService.Stub.asInterface(binder) }
        override fun onServiceDisconnected(name: ComponentName) { runtime = null }
    }
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val uri = data.data ?: return@registerForActivityResult
        try {
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            contentResolver.takePersistableUriPermission(uri, flags)
            selectedUri = uri.toString(); operationId = null; error = null
            lifecycleScope.launch {
                selectedName = withContext(Dispatchers.IO) {
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0)?.take(256) else null
                    }.orEmpty()
                }
            }
        } catch (failure: Exception) { error = getString(R.string.backup_location_unavailable) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedUri = savedInstanceState?.getString("backupUri")
        selectedName = savedInstanceState?.getString("backupName").orEmpty()
        operationId = savedInstanceState?.getString("backupOperation")
        val environmentId = intent.getStringExtra("environmentId")
        compose {
            var environment by remember { mutableStateOf<EnvironmentItem?>(null) }
            var workspaceAvailable by remember { mutableStateOf(false) }
            var records by remember { mutableStateOf(emptyList<JSONObject>()) }
            var adopted by rememberSaveable { mutableStateOf(operationId != null) }
            var full by rememberSaveable { mutableStateOf(false) }
            var workspace by rememberSaveable { mutableStateOf(false) }
            var sharedConsent by rememberSaveable { mutableStateOf(false) }
            var profiles by rememberSaveable { mutableStateOf(true) }
            var encrypted by rememberSaveable { mutableStateOf(true) }
            var importProfiles by rememberSaveable { mutableStateOf(false) }
            var trusted by rememberSaveable { mutableStateOf(false) }
            var restoredName by rememberSaveable { mutableStateOf("") }
            LaunchedEffect(runtime) {
                val service = runtime ?: return@LaunchedEffect
                while (isActive) {
                    runStorageOperation { withContext(Dispatchers.IO) {
                        if (environmentId != null) {
                            val row = EnvironmentItem.parse(service.environmentsJson).firstOrNull { it.id == environmentId }
                            val available = row?.let {
                                val root = SessionStorage.root(filesDir.canonicalFile, it.environment.sessionRuntime, it.storageId).toPath()
                                val shared = it.environment.runtime == EnvironmentRuntime.ROOTLESS && (it.storageId == "legacy" || it.migratedLegacy)
                                Files.isDirectory(if (shared) filesDir.canonicalFile.toPath().resolve("workspace") else root.resolve("workspace"), NOFOLLOW_LINKS)
                            } == true
                            row to available
                        } else null to false
                    } }.onSuccess { (row, available) -> environment = row; workspaceAvailable = available }.onFailure { error = it.message }
                    runStorageOperation { withContext(Dispatchers.IO) { JSONArray(service.environmentBackupsJson).let { array ->
                        List(array.length()) { array.getJSONObject(it) }
                    } } }.onSuccess { loaded ->
                        records = loaded
                        if (!adopted) {
                            operationId = loaded.firstOrNull { it.getJSONObject("request").optString("environmentId") == environmentId.orEmpty() ||
                                environmentId == null }?.getString("id")
                            adopted = true
                        }
                    }.onFailure { error = it.message }
                    delay(750)
                }
            }
            val operation = records.firstOrNull { it.getString("id") == operationId }
            val running = submitting || records.any { it.optString("state") == "RUNNING" }
            val row = environment
            val legacyWorkspace = row?.environment?.runtime == EnvironmentRuntime.ROOTLESS && (row.storageId == "legacy" || row.migratedLegacy)
            val preview = operation?.takeIf { it.optString("state") == "SUCCEEDED" &&
                it.getJSONObject("request").getString("operation") == "INSPECT" }?.optJSONObject("manifest")
            LaunchedEffect(operationId, preview?.toString()) {
                if (preview != null) { restoredName = preview.getJSONObject("source").getString("name"); trusted = false }
            }
            SettingsPageScaffold(stringResource(if (environmentId == null) R.string.backup_title else R.string.backup_create), { finish() }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text(stringResource(R.string.backup_sensitive), Modifier.padding(horizontal = 4.dp),
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (environmentId != null) {
                        if (row == null) Text(stringResource(R.string.storage_unavailable))
                        else {
                            ProfileSection(R.string.backup_contents) {
                                BackupInfo(R.string.storage_name, row.name)
                                val typeSummary = stringResource(if (full) R.string.backup_full_summary else R.string.backup_data_summary)
                                if (!running) SettingsChoiceRow(stringResource(R.string.backup_type), typeSummary,
                                    buildList {
                                        add(SettingsChoice(stringResource(R.string.backup_data), "DATA"))
                                        if (row.environment.runtime == EnvironmentRuntime.ROOTLESS) add(SettingsChoice(stringResource(R.string.backup_full), "FULL"))
                                    }, if (full) "FULL" else "DATA", onChange = { full = it == "FULL" })
                                else BackupInfo(R.string.backup_type,
                                    stringResource(if (full) R.string.backup_full else R.string.backup_data) + "\n" + typeSummary)
                                BackupToggle(R.string.backup_profiles, R.string.backup_profiles_summary, profiles, !running) { profiles = it }
                                BackupToggle(R.string.backup_workspace, if (legacyWorkspace) R.string.backup_workspace_shared else R.string.backup_workspace_private,
                                    workspace, !running && workspaceAvailable) { workspace = it; sharedConsent = false }
                                if (workspace && legacyWorkspace) BackupToggle(R.string.backup_shared_consent, R.string.backup_shared_warning,
                                    sharedConsent, !running) { sharedConsent = it }
                            }
                            Text(stringResource(R.string.backup_idle_required), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ProfileSection(R.string.backup_security) {
                                BackupToggle(R.string.backup_encrypt, R.string.backup_encrypt_summary, encrypted, !running) { encrypted = it }
                                if (encrypted) {
                                    PasswordField(password, { password = it }, R.string.backup_password, !running)
                                    PasswordField(confirmation, { confirmation = it }, R.string.backup_password_confirm, !running)
                                }
                            }
                            ProfileSection(R.string.backup_location) {
                                EnvironmentActionSetting(R.string.backup_choose_destination, icon = null, enabled = !running) {
                                    chooseFile(create = true, title = "TERM-${row.name.replace(Regex("[^\\p{L}\\p{N} _-]"), "_").take(64)}.termbackup")
                                }
                                selectedUri?.let { BackupInfo(R.string.backup_selected_file, selectedName.ifBlank { getString(R.string.backup_file) }) }
                            }
                        }
                    } else {
                        ProfileSection(R.string.backup_import) {
                            EnvironmentActionSetting(R.string.backup_choose_source, R.drawable.ic_material_symbol_download_24, !running) {
                                trusted = false; chooseFile(create = false)
                            }
                            selectedUri?.let { BackupInfo(R.string.backup_selected_file, selectedName.ifBlank { getString(R.string.backup_file) }) }
                            PasswordField(password, { password = it }, R.string.backup_password_optional, !running)
                            EnvironmentActionSetting(R.string.backup_inspect, R.drawable.ic_material_symbol_search_24,
                                runtime != null && !running && selectedUri != null) {
                                adopted = true
                                submit(JSONObject().put("operation", "INSPECT").put("uri", selectedUri), password)
                            }
                        }
                        if (preview != null && selectedUri == operation?.getJSONObject("request")?.optString("uri")) {
                            ProfileSection(R.string.backup_preview) {
                                BackupInfo(R.string.backup_type, stringResource(if (preview.getString("kind") == "FULL") R.string.backup_full else R.string.backup_data))
                                BackupInfo(R.string.profile_runtime, environmentDescription(Environment.fromJson(preview.getJSONObject("source"))))
                                BackupInfo(R.string.backup_compatibility, preview.getString("architecture") + " · TERM " + preview.getString("termVersion"))
                                BackupInfo(R.string.backup_size, Formatter.formatShortFileSize(this@EnvironmentBackupActivity, preview.getLong("bytes")))
                                BackupInfo(R.string.backup_workspace, stringResource(when (preview.getString("workspace")) {
                                    "ENVIRONMENT" -> R.string.backup_workspace_environment
                                    "LEGACY_SHARED" -> R.string.backup_workspace_legacy
                                    else -> R.string.backup_workspace_none
                                }))
                                OutlinedTextField(restoredName, { restoredName = it.take(StorageRegistry.MAX_NAME_LENGTH).filterNot(Char::isISOControl) },
                                    label = { Text(stringResource(R.string.backup_new_name)) }, enabled = !running, singleLine = true,
                                    modifier = Modifier.settingsItemSurface().fillMaxWidth().padding(16.dp))
                                BackupToggle(R.string.backup_import_profiles, R.string.backup_import_profiles_summary, importProfiles, !running) { importProfiles = it }
                                BackupToggle(R.string.backup_trusted, R.string.backup_trusted_summary, trusted, !running) { trusted = it }
                                EnvironmentActionSetting(R.string.backup_restore_new, R.drawable.ic_material_symbol_download_24,
                                    runtime != null && !running && trusted && restoredName.isNotBlank()) {
                                    submit(JSONObject().put("operation", "RESTORE").put("uri", selectedUri).put("profiles", importProfiles).put("name", restoredName), password)
                                }
                            }
                            Text(stringResource(R.string.backup_preview_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ProfileSection(R.string.backup_create) {
                            EnvironmentActionSetting(R.string.backup_open_environments, R.drawable.ic_material_symbol_deployed_code_24, !running) {
                                startActivity(Intent(this@EnvironmentBackupActivity, EnvironmentActivity::class.java))
                            }
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    operation?.let { record ->
                        ProfileSection(R.string.backup_result) {
                            BackupResult(record)
                            val state = record.getString("state")
                            if (state == "RUNNING") EnvironmentActionSetting(R.string.cancel, R.drawable.ic_material_symbol_close_24,
                                runtime != null && record.optString("phase") !in setOf("CANCELLING", "ACTIVATING")) {
                                lifecycleScope.launch { runStorageOperation { withContext(Dispatchers.IO) {
                                    runtime?.cancelEnvironmentBackup(record.getString("id"))
                                } }.onFailure { error = it.message } }
                            }
                            record.optString("resultEnvironmentId").takeIf(String::isNotEmpty)?.let { id ->
                                EnvironmentActionSetting(R.string.backup_open_restored, R.drawable.ic_material_symbol_deployed_code_24, true) {
                                    startActivity(EnvironmentDetailActivity.intent(this@EnvironmentBackupActivity, id))
                                }
                            }
                            if (state in setOf("FAILED", "CANCELLED", "INTERRUPTED")) EnvironmentActionSetting(R.string.backup_retry,
                                R.drawable.ic_material_symbol_refresh_24, !running) {
                                operationId = null; error = null
                                if (record.getJSONObject("request").getString("operation") == "EXPORT") {
                                    selectedUri = null; chooseFile(create = true, title = "TERM-retry.termbackup")
                                } else {
                                    selectedUri = record.getJSONObject("request").getString("uri")
                                }
                            }
                        }
                    }
                    if (environmentId == null && records.isNotEmpty()) ProfileSection(R.string.backup_history) {
                        records.take(20).forEach { record ->
                            ListItem(onClick = { operationId = record.getString("id"); adopted = true },
                                supportingContent = { Text(stringResource(backupPhase(record.getString("phase")))) },
                                shapes = terminalListItemShapes(), colors = terminalSettingsListItemColors(), modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(when (record.getJSONObject("request").getString("operation")) {
                                    "EXPORT" -> R.string.backup_create; "INSPECT" -> R.string.backup_inspect; else -> R.string.backup_restore_new
                                }))
                            }
                        }
                    }
                    Text(stringResource(R.string.backup_scope), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (environmentId != null && row != null) Button(onClick = {
                        adopted = true
                        submit(JSONObject().put("operation", "EXPORT").put("uri", selectedUri).put("environmentId", row.id)
                            .put("kind", if (full) "FULL" else "DATA").put("profiles", profiles)
                            .put("defaultProfiles", JSONArray(SessionProfiles(this@EnvironmentBackupActivity).all()
                                .filter { it.builtIn && it.environmentId == row.environment.id }.map { it.resolve(row.environment).toJson() }))
                            .put("workspace", if (!workspace) "NONE" else if (legacyWorkspace) "LEGACY_SHARED" else "ENVIRONMENT"),
                            if (encrypted) password else "")
                    }, enabled = runtime != null && !running && selectedUri != null && (!workspace || !legacyWorkspace || sharedConsent) &&
                        (!encrypted || password.length in 8..1024 && password == confirmation),
                        modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.backup_start))
                    }
                }
            }
        }
    }
    private fun chooseFile(create: Boolean, title: String = "TERM.termbackup") {
        picker.launch(Intent(if (create) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (create) "application/octet-stream" else "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            if (create) { addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION); putExtra(Intent.EXTRA_TITLE, title) }
        })
    }
    private fun submit(request: JSONObject, value: String) {
        val service = runtime ?: return
        val id = UUID.randomUUID().toString()
        operationId = id; submitting = true; error = null
        val secret = value.toCharArray()
        lifecycleScope.launch {
            try {
                runStorageOperation { withContext(Dispatchers.IO) { service.startEnvironmentBackup(id, request.toString(), secret) } }
                    .onFailure { error = it.message }
                if (request.getString("operation") != "INSPECT") { password = ""; confirmation = "" }
            } finally { secret.fill('\u0000'); submitting = false }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("backupUri", selectedUri); outState.putString("backupName", selectedName); outState.putString("backupOperation", operationId)
        super.onSaveInstanceState(outState)
    }
    override fun onStart() {
        super.onStart(); binding = bindService(Intent(this, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE)
    }
    override fun onStop() {
        runtime = null
        if (binding) unbindService(connection)
        binding = false; super.onStop()
    }
    companion object {
        fun createIntent(context: Context, environmentId: String) = Intent(context, EnvironmentBackupActivity::class.java).putExtra("environmentId", environmentId)
    }
}

@Composable private fun BackupInfo(title: Int, value: String) {
    ListItem(supportingContent = { Text(value) }, colors = terminalSettingsListItemColors(), modifier = Modifier.settingsItemSurface()) { Text(stringResource(title)) }
}
@Composable private fun BackupToggle(title: Int, summary: Int, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    ListItem(onClick = { change(!checked) }, enabled = enabled, supportingContent = { Text(stringResource(summary)) },
        trailingContent = { Switch(checked, onCheckedChange = null, enabled = enabled) },
        shapes = terminalListItemShapes(), colors = terminalSettingsListItemColors(), verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().semantics { role = Role.Switch; toggleableState = ToggleableState(checked) }) { Text(stringResource(title)) }
}
@Composable private fun PasswordField(value: String, change: (String) -> Unit, title: Int, enabled: Boolean) {
    OutlinedTextField(value, { change(it.take(1024)) }, enabled = enabled, singleLine = true,
        label = { Text(stringResource(title)) }, visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.settingsItemSurface().fillMaxWidth().padding(16.dp))
}
@Composable private fun BackupResult(record: JSONObject) {
    val running = record.getString("state") == "RUNNING"
    val total = record.optLong("total")
    val bytes = record.optLong("bytes")
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.settingsItemSurface().fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(backupPhase(record.getString("phase"))), style = MaterialTheme.typography.titleMedium)
        if (total > 0) Text(stringResource(R.string.backup_processed, Formatter.formatShortFileSize(context, bytes), Formatter.formatShortFileSize(context, total)))
        else if (bytes > 0) Text(Formatter.formatShortFileSize(context, bytes))
        if (running) {
            if (total > 0 && record.optString("phase") in setOf("WRITING", "EXTRACTING"))
                LinearWavyProgressIndicator(progress = { (bytes.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            else LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        record.optString("error").takeIf(String::isNotEmpty)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
private fun backupPhase(value: String): Int = when (value) {
    "QUEUED" -> R.string.backup_phase_queued
    "SCANNING" -> R.string.backup_phase_scanning
    "WRITING" -> R.string.backup_phase_writing
    "EXTRACTING" -> R.string.backup_phase_extracting
    "VERIFYING" -> R.string.backup_phase_verifying
    "ACTIVATING" -> R.string.backup_phase_activating
    "COMPLETE" -> R.string.backup_phase_complete
    "CANCELLED" -> R.string.backup_phase_cancelled
    "CANCELLING" -> R.string.backup_phase_cancelling
    "INTERRUPTED" -> R.string.backup_phase_interrupted
    else -> R.string.backup_phase_failed
}
