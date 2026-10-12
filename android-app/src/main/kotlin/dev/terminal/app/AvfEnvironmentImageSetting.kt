@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.terminal.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.terminal.app.ui.AvfImageInstallCard
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
) {
    val context = LocalContext.current
    var installed by remember(storageId) { mutableStateOf<Boolean?>(null) }
    var aptPending by remember(storageId) { mutableStateOf(false) }
    var aptCompletedAt by remember(storageId) { mutableStateOf<Long?>(null) }
    LaunchedEffect(storageId, snapshot, revision) {
        do {
            val state = withContext(Dispatchers.IO) {
                val root = SessionStorage.root(context.filesDir, SessionRuntime.AVF, storageId).toPath()
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
        val progress = snapshot?.takeIf { it.active || (installed == false || aptPending) &&
            it.state in setOf(AvfImageInstallState.FAILED, AvfImageInstallState.CANCELLED) }
        if (progress != null) AvfImageInstallCard(progress)
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
            EnvironmentActionSetting(R.string.environment_distribution_remove, R.drawable.ic_material_symbol_delete_24,
                enabled = canRemove && !requesting && progress?.active != true,
                supporting = R.string.environment_software_protected, destructive = true, onClick = onRemove)
        }
    }
}
