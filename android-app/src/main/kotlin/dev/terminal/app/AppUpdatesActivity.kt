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
import dev.terminal.app.ui.TerminalActivity
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
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.update_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.update_channel_label), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        UpdateChannel.entries.forEach { channel ->
                            FilterChip(selected = updates.channel == channel, enabled = !updates.busy,
                                onClick = { updates.selectChannel(channel) },
                                label = { Text(stringResource(if (channel == UpdateChannel.MAIN) R.string.update_channel_main else R.string.update_channel_dev)) })
                        }
                    }
                    if (updates.channel == UpdateChannel.DEV) Text(stringResource(R.string.update_dev_warning),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Card(shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.update_installed_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.titleMedium)
                            Text(when (state) {
                                AppUpdateState.Idle -> stringResource(R.string.update_idle)
                                AppUpdateState.Checking -> stringResource(R.string.update_checking)
                                AppUpdateState.NoRelease -> stringResource(R.string.update_no_release)
                                AppUpdateState.Current -> stringResource(R.string.update_current)
                                is AppUpdateState.Available -> stringResource(R.string.update_available, state.manifest.versionName)
                                is AppUpdateState.Downloading -> stringResource(R.string.update_downloading)
                                is AppUpdateState.Ready -> stringResource(R.string.update_ready, state.manifest.versionName)
                                is AppUpdateState.Failed -> stringResource(state.message)
                            }, color = if (state is AppUpdateState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            when (state) {
                                AppUpdateState.Checking -> LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                                is AppUpdateState.Downloading -> {
                                    LinearWavyProgressIndicator(progress = { state.completed.toFloat() / state.manifest.size }, modifier = Modifier.fillMaxWidth())
                                    Text(String.format(Locale.getDefault(), "%.1f / %.1f MiB", state.completed / 1048576.0, state.manifest.size / 1048576.0))
                                }
                                is AppUpdateState.Available -> Button(onClick = updates::download, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.update_download)) }
                                is AppUpdateState.Ready -> Button(onClick = { installConfirmation = true }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.install)) }
                                else -> Unit
                            }
                            if (state !is AppUpdateState.Checking && state !is AppUpdateState.Downloading) {
                                TextButton(onClick = updates::check, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.update_check)) }
                            }
                        }
                    }
                    Text(stringResource(R.string.update_download_lifetime), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
