@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package dev.terminal.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.terminal.app.*
import dev.terminal.app.R
import dev.terminal.runtime.avf.system.*
import dev.terminal.runtime.native.NativeCompatibility
import dev.terminal.runtime.rootless.RootlessEngineInfo

internal data class DiagnosticRow(val title: Int, val value: String, val detail: String? = null)
internal data class DiagnosticSection(val title: Int, val rows: List<DiagnosticRow>, val note: String? = null)
internal val diagnosticMenuCategories = listOf(
    R.string.diagnostics_category_runtime to listOf(
        "diagnostics-native" to R.string.runtime_native_title,
        "diagnostics-userspace" to R.string.settings_userspace_title,
        "diagnostics-avf" to R.string.settings_avf_title,
    ),
    R.string.diagnostics_category_system to listOf("diagnostics-memory" to R.string.memory_diagnostics_title),
    R.string.diagnostics_category_reports to listOf("crash-reports" to R.string.crash_reports_title),
)

@Composable
internal fun DiagnosticsMenuScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    SettingsPageScaffold(stringResource(R.string.settings_diagnostics_title), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { Guidance(R.string.settings_diagnostics_description) }
            diagnosticMenuCategories.forEach { (category, entries) -> item(key = category) {
                Column(Modifier.fillMaxWidth()) {
                    Text(stringResource(category), Modifier.padding(start = 16.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    SettingsGroup {
                        entries.forEach { (destination, title) ->
                            ListItem(onClick = { onOpen(destination) },
                                trailingContent = { Icon(painterResource(R.drawable.ic_material_symbol_chevron_right_24), null) },
                                verticalAlignment = Alignment.CenterVertically, shapes = terminalListItemShapes(),
                                colors = terminalSettingsListItemColors(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(title)) }
                        }
                    }
                }
            } }
        }
    }
}

@Composable
private fun Guidance(text: Int) = Text(stringResource(text), Modifier.padding(horizontal = 4.dp),
    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun RefreshRow(loading: Boolean, onRefresh: () -> Unit) {
    SettingsGroup {
        ListItem(onClick = onRefresh, enabled = !loading, leadingContent = {
            if (loading) LoadingIndicator(Modifier.size(24.dp))
            else Icon(painterResource(R.drawable.ic_material_symbol_refresh_24), null)
        }, verticalAlignment = Alignment.CenterVertically, shapes = terminalListItemShapes(),
            colors = terminalSettingsListItemColors(), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (loading) R.string.diagnostics_refreshing else R.string.refresh))
        }
    }
}

@Composable
private fun DiagnosticPage(title: Int, description: Int, loading: Boolean, onBack: () -> Unit, onRefresh: () -> Unit,
    sections: List<DiagnosticSection>) {
    SettingsPageScaffold(stringResource(title), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = rememberLazyListState(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { Guidance(description) }
            item { RefreshRow(loading, onRefresh) }
            sections.forEach { section -> item {
                Column {
                    Text(stringResource(section.title), Modifier.padding(start = 16.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    SettingsGroup { section.rows.forEach { row ->
                        ListItem(supportingContent = row.detail?.let { { SelectionContainer { Text(it) } } },
                                trailingContent = { SelectionContainer { Text(row.value, Modifier.widthIn(max = 180.dp),
                                    style = MaterialTheme.typography.labelLarge) } },
                                verticalAlignment = Alignment.CenterVertically, colors = terminalSettingsListItemColors(),
                                modifier = Modifier.fillMaxWidth().settingsItemSurface()) { Text(stringResource(row.title)) }
                    } }
                    section.note?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            } }
        }
    }
}

@Composable
private fun observed(value: Boolean?, yes: Int = R.string.userspace_available, no: Int = R.string.userspace_unavailable) =
    stringResource(when (value) { true -> yes; false -> no; null -> R.string.diagnostics_unknown })

@Composable
internal fun NativeDiagnosticsScreen(info: NativeCompatibility?, loading: Boolean, onBack: () -> Unit, onRefresh: () -> Unit) {
    DiagnosticPage(R.string.runtime_native_title, R.string.native_diagnostics_description, loading, onBack, onRefresh,
        listOf(DiagnosticSection(R.string.diagnostics_compatibility, listOf(
            DiagnosticRow(R.string.native_backend_available, observed(info?.backendAvailable), info?.backendError),
            DiagnosticRow(R.string.native_shell_executable, observed(info?.shellExecutable), info?.shellError),
            DiagnosticRow(R.string.native_default_shell, info?.shellPath ?: stringResource(R.string.diagnostics_unknown)),
        ))))
}

@Composable
internal fun UserspaceLinuxDiagnosticsScreen(info: RootlessEngineInfo?, loading: Boolean, onBack: () -> Unit, onRefresh: () -> Unit) {
    val unknown = stringResource(R.string.diagnostics_unknown)
    val enabled = R.string.userspace_active
    val disabled = R.string.userspace_inactive
    DiagnosticPage(R.string.settings_userspace_title, R.string.userspace_diagnostics_description, loading, onBack, onRefresh,
        listOf(DiagnosticSection(R.string.diagnostics_compatibility, listOf(
            DiagnosticRow(R.string.userspace_proot, observed(info?.prootAvailable)),
            DiagnosticRow(R.string.userspace_architecture, info?.abis?.firstOrNull() ?: unknown),
            DiagnosticRow(R.string.userspace_acceleration, observed(info?.acceleration, enabled, disabled)),
        )), DiagnosticSection(R.string.diagnostics_engine_details, listOf(
            DiagnosticRow(R.string.userspace_proot_version, info?.prootVersion ?: unknown),
            DiagnosticRow(R.string.userspace_loader, observed(info?.loaderAvailable)),
            DiagnosticRow(R.string.userspace_acceleration_profile, observed(info?.acceleration, enabled, disabled),
                info?.accelerationProfile?.takeIf { it.isNotEmpty() }?.joinToString(", ")),
            DiagnosticRow(R.string.userspace_faststat, observed(info?.faststat, enabled, disabled)),
            DiagnosticRow(R.string.userspace_abi, info?.abis?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: unknown),
            DiagnosticRow(R.string.userspace_storage_links, if (info == null) unknown else stringResource(R.string.userspace_storage_bind)),
        ), stringResource(R.string.userspace_limitations_note))))
}

@Composable
private fun avfStatus(status: AvfCheckStatus?) = stringResource(when (status) {
    AvfCheckStatus.SATISFIED -> R.string.diagnostics_satisfied
    AvfCheckStatus.UNSATISFIED -> R.string.diagnostics_unsatisfied
    else -> R.string.diagnostics_unknown
})

private fun avfTitle(condition: AvfCondition) = when (condition) {
    AvfCondition.ANDROID_VERSION -> R.string.avf_check_android
    AvfCondition.SYSTEM_FEATURE -> R.string.avf_check_feature
    AvfCondition.MANAGE_PERMISSION -> R.string.avf_check_manage
    AvfCondition.CUSTOM_PERMISSION -> R.string.avf_check_custom
    AvfCondition.CUSTOM_IMAGE_API -> R.string.avf_check_image_api
    AvfCondition.VM_MANAGER -> R.string.avf_check_manager
    AvfCondition.PLATFORM_DRIVER -> R.string.avf_check_driver
    AvfCondition.SHARED_DIRECTORY_API -> R.string.avf_check_sharing_api
}

@Composable
private fun avfReason(check: AvfCheck): String? {
    val reason = when (check.reason) {
        AvfCheckReason.OBSERVED -> null
        AvfCheckReason.ANDROID_TOO_OLD -> R.string.avf_reason_android
        AvfCheckReason.FEATURE_MISSING -> R.string.avf_reason_feature
        AvfCheckReason.PERMISSION_MISSING -> R.string.avf_reason_permission
        AvfCheckReason.PREREQUISITES_REQUIRED -> R.string.avf_reason_prerequisites
        AvfCheckReason.API_ACCESS_OR_ABI -> R.string.avf_reason_api
        AvfCheckReason.API_DENIED -> R.string.avf_reason_denied
        AvfCheckReason.INSPECTION_FAILED -> R.string.avf_reason_failed
        AvfCheckReason.MANAGER_MISSING -> R.string.avf_reason_manager
        AvfCheckReason.DRIVER_MISSING -> R.string.avf_reason_driver
    }
    return listOfNotNull(reason?.let { stringResource(it) }, check.detail).takeIf { it.isNotEmpty() }?.joinToString("\n")
}

@Composable
internal fun AvfLinuxDiagnosticsScreen(info: AvfDiagnostics?, loading: Boolean, onBack: () -> Unit, onRefresh: () -> Unit) {
    DiagnosticPage(R.string.settings_avf_title, R.string.avf_diagnostics_description, loading, onBack, onRefresh,
        listOf(DiagnosticSection(R.string.avf_prerequisites, listOf(
            DiagnosticRow(R.string.avf_overall, avfStatus(info?.status)),
        ) + AvfCondition.entries.filter { it.required }.map { condition ->
            val check = info?.checks?.singleOrNull { it.condition == condition }
            DiagnosticRow(avfTitle(condition), avfStatus(check?.status), check?.let { avfReason(it) })
        }, stringResource(R.string.avf_diagnostics_caveat)),
            DiagnosticSection(R.string.avf_storage_sharing, AvfCondition.entries.filter { !it.required }.map { condition ->
                val check = info?.checks?.singleOrNull { it.condition == condition }
                DiagnosticRow(avfTitle(condition), avfStatus(check?.status), check?.let { avfReason(it) })
            }, stringResource(R.string.avf_sharing_diagnostics_caveat))))
}

@Composable
internal fun memoryValue(bytes: Long?) = bytes?.let {
    String.format(java.util.Locale.getDefault(), "%.1f MiB", it / 1048576.0)
} ?: stringResource(R.string.diagnostics_unknown)

@Composable
internal fun CrashReportsScreen(reports: List<CrashReport>, loading: Boolean, error: String?, onBack: () -> Unit,
    onRefresh: () -> Unit, onOpen: (CrashReport) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    SettingsPageScaffold(stringResource(R.string.crash_reports_title), onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = rememberLazyListState(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { Guidance(R.string.crash_reports_summary) }
            item { RefreshRow(loading, onRefresh) }
            item {
                SettingsGroup {
                    when {
                        loading -> ReportMessage(stringResource(R.string.crash_reports_loading))
                        error != null -> ReportMessage(error)
                        reports.isEmpty() -> ReportMessage(stringResource(R.string.crash_reports_empty))
                        else -> reports.forEach { report ->
                            val date = remember(report.createdAt, locale) { java.text.DateFormat.getDateTimeInstance(
                                java.text.DateFormat.MEDIUM, java.text.DateFormat.MEDIUM, locale).format(java.util.Date(report.createdAt)) }
                            ListItem(onClick = { onOpen(report) }, supportingContent = { Text(date) },
                                trailingContent = { Icon(painterResource(R.drawable.ic_material_symbol_chevron_right_24), null) },
                                shapes = terminalListItemShapes(), colors = terminalSettingsListItemColors(),
                                verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(report.summary.ifBlank { stringResource(R.string.crash_report_title) })
                            }
                        }
                    }
                }
            }
            item { Text(stringResource(R.string.crash_reports_retention, CrashReportStore.MAX_REPORTS),
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun ReportMessage(message: String) = ListItem(colors = terminalSettingsListItemColors(),
    modifier = Modifier.fillMaxWidth().settingsItemSurface()) { Text(message) }

@Composable
internal fun SessionInfoScreen(title: String, rss: Long?, loading: Boolean, onBack: () -> Unit, onRefresh: () -> Unit) {
    DiagnosticPage(R.string.session_info_title, R.string.session_memory_description, loading, onBack, onRefresh,
        listOf(DiagnosticSection(R.string.session_info_title, listOf(
            DiagnosticRow(R.string.session_info_name, title),
            DiagnosticRow(R.string.session_memory_rss, memoryValue(rss)),
        ), stringResource(R.string.session_memory_caveat))))
}
