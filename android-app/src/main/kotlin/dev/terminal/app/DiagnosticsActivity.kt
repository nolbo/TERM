package dev.terminal.app

import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.terminal.app.ui.DiagnosticsAndReportsScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

internal data class RuntimeDiagnostics(
    val state: String,
    val detail: String,
    val imageStatus: String,
    val wakePolicy: String,
    val sessionCount: Int,
)

/** The sole settings destination for runtime diagnostics and saved crash reports. */
class DiagnosticsActivity : RuntimeInformationActivity() {
    private var diagnostics by mutableStateOf<RuntimeDiagnostics?>(null)
    private var diagnosticsUnavailable by mutableStateOf(false)
    private var reports by mutableStateOf<List<CrashReport>>(emptyList())
    private var reportsLoading by mutableStateOf(true)
    private var reportsError by mutableStateOf<String?>(null)
    private var reportsJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        compose { DiagnosticsAndReportsScreen(
            { finish() }, diagnostics, diagnosticsUnavailable, reports, reportsLoading, reportsError,
            { if (!reportsLoading) { render(); loadReports() } },
            { report -> startActivity(CrashReportActivity.intent(this, report.id)) },
            intent.getIntExtra(SettingsDetailActivity.EXTRA_SECTION_INDEX, 0),
        ) }
    }

    override fun onResume() {
        super.onResume()
        loadReports()
    }

    override fun runtimeInformationChanged() = render()

    private fun loadReports() {
        reportsJob?.cancel()
        reportsJob = lifecycleScope.launch {
            reportsLoading = true
            reportsError = null
            runCatching { withContext(Dispatchers.IO) {
                CrashReportStore.from(this@DiagnosticsActivity).run {
                    importPending(this@DiagnosticsActivity)
                    reports().take(CrashReportStore.MAX_REPORTS)
                }
            } }.onSuccess { reports = it }.onFailure {
                if (it is CancellationException) throw it
                reportsError = getString(R.string.crash_reports_unavailable)
            }
            reportsLoading = false
        }
    }

    private fun render() {
        val service = runtime
        if (service == null) {
            diagnostics = null
            diagnosticsUnavailable = false
            return
        }
        val result = runCatching {
            RuntimeDiagnostics(service.state, service.detail, service.imageStatus, service.wakePolicy,
                JSONArray(service.sessionsJson).length())
        }
        diagnostics = result.getOrNull()
        diagnosticsUnavailable = result.isFailure
    }
}
