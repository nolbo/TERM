@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.terminal.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.terminal.app.ui.AvfImageInstallProgress
import dev.terminal.app.ui.settingsItemSurface
import dev.terminal.app.ui.terminalSettingsListItemColors
import dev.terminal.image.BundledAvfImageCatalog
import dev.terminal.image.AvfImageVerifier
import dev.terminal.image.AvfPackageInitialization
import dev.terminal.runtime.image.AvfImageInstallSnapshot
import dev.terminal.runtime.image.AvfImageInstallState
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.session.SessionStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Displays this environment's files and persisted install progress, independently of VM readiness. */
@Composable internal fun AvfEnvironmentImageSetting(
    storageId: String,
    distribution: dev.terminal.image.AvfDistribution,
    snapshot: AvfImageInstallSnapshot?,
    revision: Int,
    savedStorage: Boolean,
    requesting: Boolean,
    onInstall: () -> Unit,
    canRemove: Boolean = false,
    hasData: Boolean = false,
    onRemove: () -> Unit = {},
    canManage: Boolean = false,
    onManage: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var installed by remember(storageId) { mutableStateOf<Boolean?>(null) }
    var aptPending by remember(storageId) { mutableStateOf(false) }
    var aptCompletedAt by remember(storageId) { mutableStateOf<Long?>(null) }
    var hasPrevious by remember(storageId) { mutableStateOf(false) }
    var confirmedAction by rememberSaveable(storageId) { mutableStateOf<String?>(null) }
    LaunchedEffect(storageId, snapshot, revision) {
        do {
            val state = withContext(Dispatchers.IO) {
                val root = SessionStorage.root(context.filesDir, SessionRuntime.AVF, storageId).toPath()
                hasPrevious = dev.terminal.image.AvfImageTransitions.previous(root) != null
                Triple(AvfImageVerifier.hasInstalledImage(root, distribution), AvfPackageInitialization.isRequired(root),
                    AvfPackageInitialization.completedAt(root))
            }
            installed = state.first
            aptCompletedAt = state.third
            aptPending = state.second && state.third == null
            // A later session can finish a cancelled installation's pending
            // refresh without changing its old WorkManager result.
            if (aptPending) kotlinx.coroutines.delay(2_000)
        } while (aptPending)
    }
    val progress = snapshot?.takeIf { it.active ||
        it.state in setOf(AvfImageInstallState.FAILED, AvfImageInstallState.CANCELLED) }
    EnvironmentSection(R.string.avf_image_section) {
        ListItem(modifier = Modifier.settingsItemSurface(), colors = terminalSettingsListItemColors(),
            supportingContent = { Text(stringResource(when (installed) {
                true -> R.string.avf_image_installed
                false -> R.string.avf_image_not_installed
                null -> R.string.avf_image_checking
            })) }) { Text(stringResource(R.string.avf_image_installation_status)) }
        if (installed == true && (aptPending || aptCompletedAt != null)) {
            ListItem(modifier = Modifier.settingsItemSurface(), colors = terminalSettingsListItemColors(),
                supportingContent = { Text(if (aptPending) stringResource(R.string.avf_apt_pending)
                    else stringResource(R.string.avf_apt_updated_at,
                        java.text.DateFormat.getDateTimeInstance().format(java.util.Date(requireNotNull(aptCompletedAt) * 1000)))) }) {
                Text(stringResource(R.string.avf_apt_status))
            }
        }
        if (progress != null) AvfImageInstallProgress(progress)
        if ((installed == false || aptPending) && progress?.active != true) {
            val available = BundledAvfImageCatalog.find(distribution) != null
            EnvironmentActionSetting(if (aptPending) R.string.avf_apt_retry else R.string.avf_image_install, R.drawable.ic_material_symbol_download_24,
                enabled = savedStorage && available && !requesting,
                supporting = when {
                    !savedStorage -> R.string.avf_image_save_first
                    !available -> R.string.avf_image_catalog_missing
                    else -> null
                }, onClick = onInstall)
        }
        if (installed == true || hasData) {
            if (installed == true) {
                AvfImageUpdateSetting(SessionStorage.root(context.filesDir, SessionRuntime.AVF, storageId).toPath(),
                    BundledAvfImageCatalog.find(distribution), revision, requesting || progress?.active == true,
                    canManage, onUpdate = { confirmedAction = it })
            }
            EnvironmentActionSetting(R.string.environment_distribution_remove, R.drawable.ic_material_symbol_delete_24,
                enabled = canRemove && !requesting && progress?.active != true,
                supporting = R.string.environment_software_protected, destructive = true, onClick = onRemove)
        }
    }
    if ((installed == true || hasData) && hasPrevious) {
        EnvironmentSection(R.string.avf_recovery_section) {
            val enabled = canManage && !requesting && progress?.active != true
            EnvironmentActionSetting(R.string.avf_rollback_image, R.drawable.ic_material_symbol_download_24,
                enabled = enabled, supporting = R.string.avf_rollback_image_summary,
                onClick = { confirmedAction = "ROLLBACK_IMAGE" })
            EnvironmentActionSetting(R.string.avf_discard_history, R.drawable.ic_material_symbol_delete_24,
                enabled = enabled, supporting = R.string.avf_discard_history_summary, destructive = true,
                onClick = { confirmedAction = "DISCARD_IMAGE_HISTORY" })
        }
    }
    confirmedAction?.let { action ->
        val title = when (action) {
            "UPDATE_COMPONENTS" -> R.string.avf_update_components
            "UPDATE_IMAGE" -> R.string.avf_update_image
            "ROLLBACK_IMAGE" -> R.string.avf_rollback_image
            else -> R.string.avf_discard_history
        }
        val help = when (action) {
            "UPDATE_COMPONENTS" -> R.string.avf_update_components_help
            "UPDATE_IMAGE" -> R.string.avf_update_image_help
            "ROLLBACK_IMAGE" -> R.string.avf_rollback_image_help
            else -> R.string.avf_discard_history_help
        }
        AlertDialog(onDismissRequest = { confirmedAction = null }, title = { Text(stringResource(title)) },
            text = { Text(stringResource(help)) },
            confirmButton = { TextButton(onClick = { confirmedAction = null; onManage(action) }) { Text(stringResource(android.R.string.ok)) } },
            dismissButton = { TextButton(onClick = { confirmedAction = null }) { Text(stringResource(android.R.string.cancel)) } })
    }
}
