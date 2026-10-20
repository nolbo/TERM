@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.terminal.app.ui.SettingsGroup
import dev.terminal.app.ui.SettingsPageScaffold
import dev.terminal.app.ui.TerminalActivity
import dev.terminal.app.ui.terminalListItemShapes
import dev.terminal.app.ui.terminalSettingsListItemColors

/** Compiled and registered only in debug APKs. Opening this screen starts no runtime. */
class DebugSettingsActivity : TerminalActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val metrics = resources.displayMetrics
        val info = listOf(
            R.string.app_version to BuildConfig.VERSION_NAME,
            R.string.app_package_name to packageName,
            R.string.app_build_type to BuildConfig.BUILD_TYPE,
            R.string.app_build_number to BuildConfig.VERSION_CODE.toString(),
            R.string.update_channel_label to BuildConfig.UPDATE_CHANNEL,
            R.string.app_build_revision to BuildConfig.UPDATE_REVISION,
            R.string.debug_device to "${Build.MANUFACTURER} ${Build.MODEL}",
            R.string.debug_android_version to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            R.string.debug_abis to Build.SUPPORTED_ABIS.joinToString(),
            R.string.debug_display to "${metrics.widthPixels} × ${metrics.heightPixels} px · ${metrics.densityDpi} dpi",
            R.string.debug_font_scale to resources.configuration.fontScale.toString(),
        )
        compose {
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = intent.getIntExtra(SettingsDetailActivity.EXTRA_SECTION_INDEX, 0).coerceIn(0, 3))
            SettingsPageScaffold(stringResource(R.string.settings_debug_title), { finish() }) { padding ->
                LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    item {
                        Text(stringResource(R.string.settings_debug_summary), Modifier.padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item {
                        Section(R.string.debug_ui_section) {
                            NavigationRow(R.string.debug_open_setup, R.string.debug_open_setup_summary) {
                                startActivity(Intent(this@DebugSettingsActivity, SetupActivity::class.java)
                                    .putExtra(SetupActivity.EXTRA_SHOW_SETUP, true))
                            }
                            ActionRow(R.string.debug_recreate_activity, R.string.debug_recreate_activity_summary,
                                R.drawable.ic_material_symbol_refresh_24) { recreate() }
                        }
                    }
                    item {
                        Section(R.string.settings_diagnostics_title) {
                            ActionRow(R.string.debug_copy_info, R.string.debug_copy_info_summary,
                                R.drawable.ic_material_symbol_content_copy_24) {
                                val report = info.joinToString("\n") { (label, value) -> "${getString(label)}: $value" }
                                getSystemService(ClipboardManager::class.java)
                                    .setPrimaryClip(ClipData.newPlainText("TERM debug", report))
                                Toast.makeText(this@DebugSettingsActivity, R.string.debug_info_copied, Toast.LENGTH_SHORT).show()
                            }
                            NavigationRow(R.string.debug_app_settings, R.string.debug_app_settings_summary) {
                                runCatching {
                                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", packageName, null)))
                                }.onFailure {
                                    Toast.makeText(this@DebugSettingsActivity, R.string.debug_app_settings_unavailable, Toast.LENGTH_LONG).show()
                                }
                            }
                            NavigationRow(R.string.memory_diagnostics_title, R.string.memory_diagnostics_description) {
                                startActivity(Intent(this@DebugSettingsActivity, MemoryDiagnosticsActivity::class.java))
                            }
                            NavigationRow(R.string.crash_reports_title, R.string.crash_reports_summary) {
                                startActivity(Intent(this@DebugSettingsActivity, CrashReportsActivity::class.java))
                            }
                        }
                    }
                    item {
                        Section(R.string.debug_build_device) {
                            info.forEach { (label, value) ->
                                ListItem(supportingContent = { SelectionContainer { Text(value) } },
                                    shapes = terminalListItemShapes(), colors = terminalSettingsListItemColors(),
                                    modifier = Modifier.fillMaxWidth()) { Text(stringResource(label)) }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Section(title: Int, content: @Composable ColumnScope.() -> Unit) {
        Column {
            Text(stringResource(title), Modifier.padding(start = 16.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            SettingsGroup(content = content)
        }
    }

    @Composable
    private fun NavigationRow(title: Int, summary: Int, onClick: () -> Unit) =
        ActionRow(title, summary, R.drawable.ic_material_symbol_chevron_right_24, onClick)

    @Composable
    private fun ActionRow(title: Int, summary: Int, icon: Int, onClick: () -> Unit) {
        ListItem(onClick = onClick, supportingContent = { Text(stringResource(summary)) },
            trailingContent = { Icon(painterResource(icon), null) }, verticalAlignment = Alignment.CenterVertically,
            shapes = terminalListItemShapes(), colors = terminalSettingsListItemColors(),
            modifier = Modifier.fillMaxWidth()) { Text(stringResource(title)) }
    }
}
