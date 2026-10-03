@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.terminal.app.ui.SettingsPageScaffold
import dev.terminal.app.ui.ReleaseNotesMarkdown
import dev.terminal.app.ui.TerminalActivity
import dev.terminal.app.ui.TerminalStatusBlue
import dev.terminal.app.update.AppUpdateState
import dev.terminal.app.update.UpdateChannel
import java.util.Locale

/** Dedicated subclass follows FileProvider's device compatibility contract. */
class AppUpdateFileProvider : FileProvider()

class AppUpdatesActivity : TerminalActivity() {
    private val updates get() = (application as TerminalApplication).appUpdates

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        compose {
            val state = updates.state
            var installConfirmation by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(updates.channel) { installConfirmation = false }
            SettingsPageScaffold(stringResource(R.string.app_updates), { finish() }) { padding ->
                val manifest = when (state) {
                    is AppUpdateState.Available -> state.manifest
                    is AppUpdateState.Downloading -> state.manifest
                    is AppUpdateState.Ready -> state.manifest
                    else -> null
                }
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.update_channel_label), style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            UpdateChannel.entries.forEach { channel ->
                                FilterChip(selected = updates.channel == channel, enabled = updates.releaseUpdatesSupported && !updates.busy,
                                    onClick = { updates.selectChannel(channel) },
                                    label = { Text(stringResource(if (channel == UpdateChannel.MAIN) R.string.update_channel_main else R.string.update_channel_dev)) })
                            }
                        }
                        if (updates.channel == UpdateChannel.DEV) Text(stringResource(R.string.update_dev_warning),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp))
                    }
                    Card(shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(stringResource(when (state) {
                                AppUpdateState.Idle -> if (updates.releaseUpdatesSupported) R.string.update_idle_title else R.string.update_debug_title
                                AppUpdateState.Checking -> R.string.update_checking_title
                                AppUpdateState.NoRelease -> R.string.update_no_release_title
                                AppUpdateState.Current -> R.string.update_current_title
                                is AppUpdateState.Available -> R.string.update_available_title
                                is AppUpdateState.Downloading -> R.string.update_downloading_title
                                is AppUpdateState.Ready -> R.string.update_ready_title
                                is AppUpdateState.Failed -> R.string.update_failed_title
                            }), style = MaterialTheme.typography.titleLarge,
                                color = if (state is AppUpdateState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            Text(when (state) {
                                AppUpdateState.Idle -> stringResource(if (updates.releaseUpdatesSupported) R.string.update_idle else R.string.update_debug_manual)
                                AppUpdateState.Checking -> stringResource(R.string.update_checking)
                                AppUpdateState.NoRelease -> stringResource(R.string.update_no_release)
                                AppUpdateState.Current -> stringResource(R.string.update_current)
                                is AppUpdateState.Available -> stringResource(R.string.update_available_description)
                                is AppUpdateState.Downloading -> stringResource(R.string.update_downloading)
                                is AppUpdateState.Ready -> stringResource(R.string.update_ready_description)
                                is AppUpdateState.Failed -> stringResource(state.message)
                            }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (manifest != null) stringResource(R.string.update_available, manifest.versionName)
                                else stringResource(R.string.update_installed_version, BuildConfig.VERSION_NAME),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            when (state) {
                                AppUpdateState.Checking -> LinearWavyProgressIndicator(Modifier.fillMaxWidth(), color = TerminalStatusBlue)
                                is AppUpdateState.Downloading -> {
                                    val progress = (state.completed.toFloat() / state.manifest.size).coerceIn(0f, 1f)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(String.format(Locale.getDefault(), "%.0f%%", progress * 100),
                                            style = MaterialTheme.typography.bodyMedium, color = TerminalStatusBlue)
                                        Text(String.format(Locale.getDefault(), "%.1f / %.1f MiB", state.completed / 1048576.0, state.manifest.size / 1048576.0),
                                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = TerminalStatusBlue)
                                }
                                is AppUpdateState.Available -> Button(onClick = updates::download, modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = TerminalStatusBlue, contentColor = MaterialTheme.colorScheme.onPrimary),
                                    shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.update_download)) }
                                is AppUpdateState.Ready -> Button(onClick = { installConfirmation = true }, modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = TerminalStatusBlue, contentColor = MaterialTheme.colorScheme.onPrimary),
                                    shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.install)) }
                                else -> Button(onClick = updates::check, modifier = Modifier.fillMaxWidth(), enabled = updates.releaseUpdatesSupported,
                                    shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.update_check)) }
                            }
                            if (state is AppUpdateState.Available || state is AppUpdateState.Ready) {
                                TextButton(onClick = updates::check, enabled = updates.releaseUpdatesSupported,
                                    shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.update_check)) }
                            }
                        }
                    }
                    val hint = when (state) {
                        is AppUpdateState.Available -> R.string.update_download_before_install
                        is AppUpdateState.Downloading -> R.string.update_download_lifetime
                        is AppUpdateState.Ready -> R.string.update_save_work
                        else -> null
                    }
                    if (hint != null) Text(stringResource(hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
                    if (manifest != null) {
                        Text(stringResource(R.string.update_release_notes), style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 4.dp))
                        Card(shape = MaterialTheme.shapes.extraLarge,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                val notes = manifest.releaseNotes
                                if (notes != null) ReleaseNotesMarkdown(notes)
                                else Text(stringResource(R.string.update_release_notes_unavailable),
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            if (installConfirmation && state is AppUpdateState.Ready) AlertDialog(
                onDismissRequest = { installConfirmation = false },
                title = { Text(stringResource(R.string.update_install_title)) },
                text = { Text(stringResource(R.string.update_install_warning)) },
                confirmButton = { TextButton(onClick = { installConfirmation = false; install(state) }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.install)) } },
                dismissButton = { TextButton(onClick = { installConfirmation = false }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }

    private fun install(ready: AppUpdateState.Ready) {
        try {
            if (!ready.apk.isFile) { Toast.makeText(this, R.string.update_cache_missing, Toast.LENGTH_LONG).show(); updates.check(); return }
            if (!packageManager.canRequestPackageInstalls()) {
                Toast.makeText(this, R.string.update_allow_install, Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                return
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.app-updates", ready.apk)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: ActivityNotFoundException) { Toast.makeText(this, R.string.update_installer_missing, Toast.LENGTH_LONG).show() }
        catch (_: SecurityException) { Toast.makeText(this, R.string.update_installer_missing, Toast.LENGTH_LONG).show() }
    }
}
