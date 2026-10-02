@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app

import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.terminal.app.ui.SettingsGroup
import dev.terminal.app.ui.LocalSettingsGroup
import dev.terminal.app.ui.SettingsItemSpacing
import dev.terminal.app.ui.SettingsPageScaffold
import dev.terminal.app.ui.TerminalActivity
import dev.terminal.app.ui.settingsItemSurface
import dev.terminal.app.ui.terminalListItemShapes
import dev.terminal.app.ui.terminalSettingsListItemColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Installed APK metadata and the license documents actually bundled with it. */
class AppInfoActivity : TerminalActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_LICENSE_PATH)
        if (path != null && !(path == "licenses" || path.startsWith("licenses/") && path.split('/').none { it == ".." || it.isEmpty() })) {
            finish()
            return
        }
        compose {
            if (path == null) AppInfo() else LicensePage(path)
        }
    }

    private fun openLicense(path: String) {
        startActivity(Intent(this, AppInfoActivity::class.java).putExtra(EXTRA_LICENSE_PATH, path))
    }

    @Composable
    private fun AppInfo() {
        SettingsPageScaffold(stringResource(R.string.app_info), { finish() }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                item {
                    SettingsGroup {
                        InfoRow(R.string.app_version, BuildConfig.VERSION_NAME)
                        InfoRow(R.string.app_package_name, packageName)
                        InfoRow(R.string.app_build_type, BuildConfig.BUILD_TYPE)
                        InfoRow(R.string.app_build_number, BuildConfig.VERSION_CODE.toString())
                        InfoRow(R.string.update_channel_label, BuildConfig.UPDATE_CHANNEL)
                        InfoRow(R.string.app_build_revision, BuildConfig.UPDATE_REVISION)
                    }
                }
                item {
                    SettingsGroup {
                        NavigationRow(stringResource(R.string.open_source_licenses), stringResource(R.string.open_source_licenses_summary)) {
                            openLicense("licenses")
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun InfoRow(title: Int, value: String) {
        ListItem(supportingContent = { SelectionContainer { Text(value) } },
            shapes = terminalListItemShapes(), colors = terminalSettingsListItemColors(),
            modifier = Modifier.fillMaxWidth()) { Text(stringResource(title)) }
    }

    @Composable
    private fun NavigationRow(title: String, summary: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
        ListItem(onClick = onClick, supportingContent = { Text(summary) },
            trailingContent = { Icon(painterResource(R.drawable.ic_material_symbol_chevron_right_24), null) },
            verticalAlignment = Alignment.CenterVertically,
            shapes = terminalListItemShapes(), colors = terminalSettingsListItemColors(),
            modifier = modifier.fillMaxWidth()) { Text(title) }
    }

    @Composable
    private fun LicensePage(path: String) {
        val page by produceState<Result<LicenseContent>?>(null, path) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val children = assets.list(path).orEmpty().sorted()
                    if (children.isEmpty()) LicenseContent.Text(assets.open(path).bufferedReader().use { it.readText() })
                    else LicenseContent.Directory(children.map {
                        val child = "$path/$it"
                        LicenseEntry(child, assets.list(child).orEmpty().isNotEmpty())
                    })
                }
            }
        }
        SettingsPageScaffold(licenseTitle(path), { finish() }) { padding ->
            when (val content = page?.getOrNull()) {
                is LicenseContent.Directory -> LazyColumn(Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(SettingsItemSpacing)) {
                    itemsIndexed(content.entries, key = { _, entry -> entry.path }) { index, entry ->
                        val outer = MaterialTheme.shapes.extraLarge
                        val shape = RoundedCornerShape(
                            topStart = if (index == 0) outer.topStart else MaterialTheme.shapes.extraSmall.topStart,
                            topEnd = if (index == 0) outer.topEnd else MaterialTheme.shapes.extraSmall.topEnd,
                            bottomStart = if (index == content.entries.lastIndex) outer.bottomStart else MaterialTheme.shapes.extraSmall.bottomStart,
                            bottomEnd = if (index == content.entries.lastIndex) outer.bottomEnd else MaterialTheme.shapes.extraSmall.bottomEnd,
                        )
                        CompositionLocalProvider(LocalSettingsGroup provides true) {
                            NavigationRow(licenseTitle(entry.path),
                                if (entry.directory) stringResource(R.string.license_directory_summary) else licenseSummary(entry.path),
                                Modifier.clip(shape)) { openLicense(entry.path) }
                        }
                    }
                }
                is LicenseContent.Text -> SelectionContainer {
                    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
                        SettingsGroup {
                            Text(content.value, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth().settingsItemSurface().padding(16.dp))
                        }
                    }
                }
                null -> Box(Modifier.fillMaxSize().padding(padding).padding(16.dp), contentAlignment = Alignment.Center) {
                    if (page == null) LoadingIndicator()
                    else Text(stringResource(R.string.license_read_error), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    @Composable
    private fun licenseTitle(path: String): String = when (path) {
        "licenses" -> stringResource(R.string.open_source_licenses)
        "licenses/iphone" -> stringResource(R.string.license_iphone_runtime)
        "licenses/iphone-tools" -> stringResource(R.string.license_iphone_tools)
        "licenses/sidestore" -> "SideStore"
        "licenses/usb-serial" -> "USB Serial for Android"
        "licenses/GoogleSans-OFL.txt" -> "Google Sans"
        "licenses/GoogleSansCode-OFL.txt" -> "Google Sans Code"
        "licenses/NotoSansCJK-OFL.txt" -> "Noto Sans CJK"
        "licenses/AndroidX-Kotlin-Apache-2.0.txt" -> "AndroidX · Kotlin · kotlinx.coroutines"
        "licenses/TERM-GPL-3.0.txt" -> stringResource(R.string.app_name)
        "licenses/Termux-terminal-Apache-2.0.txt" -> "Termux terminal-view · terminal-emulator"
        "licenses/material-symbols-Apache-2.0.txt" -> "Material Symbols"
        "licenses/lucide-LICENSE.txt" -> "Lucide · Feather"
        "licenses/rootless-THIRD-PARTY-NOTICES.txt" -> stringResource(R.string.license_rootless_notices)
        "licenses/runtime-logos-NOTICE.txt" -> stringResource(R.string.license_runtime_logos)
        else -> path.substringAfterLast('/').removeSuffix(".txt").replace('-', ' ')
    }

    @Composable
    private fun licenseSummary(path: String): String = when {
        path.endsWith("-OFL.txt") -> "SIL Open Font License 1.1"
        path.endsWith("-Apache-2.0.txt") -> "Apache License 2.0"
        path.endsWith("-GPL-3.0.txt") -> "GNU GPL 3.0"
        path.endsWith("-GPL-2.0-or-later.txt") -> "GNU GPL 2.0 or later"
        path.endsWith("-BSD-2-Clause.txt") -> "BSD 2-Clause"
        path.endsWith("-BSD-3-Clause.txt") -> "BSD 3-Clause"
        path.endsWith("-0BSD.txt") -> "BSD Zero Clause"
        path.endsWith("lucide-LICENSE.txt") -> "ISC · MIT"
        else -> stringResource(R.string.license_document_summary)
    }

    private data class LicenseEntry(val path: String, val directory: Boolean)
    private sealed interface LicenseContent {
        data class Directory(val entries: List<LicenseEntry>) : LicenseContent
        data class Text(val value: String) : LicenseContent
    }

    companion object {
        const val EXTRA_LICENSE_PATH = "license-path"
    }
}
