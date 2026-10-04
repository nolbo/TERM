@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.terminal.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.terminal.app.ui.AvfImageInstallCard
import dev.terminal.app.ui.settingsItemSurface
import dev.terminal.app.ui.terminalSettingsListItemColors
import dev.terminal.image.BundledDebianImageCatalog
import dev.terminal.image.DebianImageVerifier
import dev.terminal.runtime.image.AvfImageInstallSnapshot
import dev.terminal.runtime.image.AvfImageInstallState
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.session.SessionStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun hasAvfProfileImage(context: Context, storageId: String): Boolean =
    DebianImageVerifier.hasInstalledImage(SessionStorage.root(context.filesDir, SessionRuntime.AVF, storageId).toPath())

/** Displays this storage's files and persisted install progress, independently of VM readiness. */
@Composable internal fun AvfProfileImageSetting(
    storageId: String,
    snapshot: AvfImageInstallSnapshot?,
    revision: Int,
    savedStorage: Boolean,
    requesting: Boolean,
    onInstall: () -> Unit,
) {
    val context = LocalContext.current
    var installed by remember(storageId) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(storageId, snapshot, revision) {
        installed = withContext(Dispatchers.IO) { hasAvfProfileImage(context, storageId) }
    }
    ProfileSection(R.string.debian_image) {
        ListItem(modifier = Modifier.settingsItemSurface(), colors = terminalSettingsListItemColors(),
            supportingContent = { Text(stringResource(when (installed) {
                true -> R.string.avf_image_installed
                false -> R.string.avf_image_not_installed
                null -> R.string.avf_image_checking
            })) }) { Text(stringResource(R.string.avf_image_installation_status)) }
        val progress = snapshot?.takeIf { it.active || installed == false &&
            it.state in setOf(AvfImageInstallState.FAILED, AvfImageInstallState.CANCELLED) }
        if (progress != null) AvfImageInstallCard(progress)
        if (installed == false && progress?.active != true) {
            val available = BundledDebianImageCatalog.find(BundledDebianImageCatalog.DEFAULT_RELEASE_ID) != null
            Button(onClick = onInstall, enabled = savedStorage && available && !requesting,
                modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.avf_image_install))
            }
            if (!savedStorage) Text(stringResource(R.string.avf_image_save_first), style = MaterialTheme.typography.bodySmall)
            else if (!available) Text(stringResource(R.string.avf_image_catalog_missing), style = MaterialTheme.typography.bodySmall)
        }
    }
}
