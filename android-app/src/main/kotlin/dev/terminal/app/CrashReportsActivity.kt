package dev.terminal.app

import android.os.Bundle
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import dev.terminal.app.ui.CrashReportsScreen
import dev.terminal.app.ui.TerminalActivity
import kotlinx.coroutines.*

class CrashReportsActivity : TerminalActivity() {
    private var reports by mutableStateOf<List<CrashReport>>(emptyList())
    private var loading by mutableStateOf(true)
    private var error by mutableStateOf<String?>(null)
    private var job: Job? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        compose { CrashReportsScreen(reports, loading, error, { finish() }, ::refresh) { report ->
            startActivity(CrashReportActivity.intent(this, report.id))
        } }
    }
    override fun onResume() { super.onResume(); refresh() }
    override fun onPause() { job?.cancel(); super.onPause() }
    private fun refresh() {
        job?.cancel()
        job = lifecycleScope.launch {
            loading = true
            error = null
            try { reports = withContext(Dispatchers.IO) {
                CrashReportStore.from(this@CrashReportsActivity).run {
                    importPending(this@CrashReportsActivity)
                    reports().take(CrashReportStore.MAX_REPORTS)
                }
            } } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                error = getString(R.string.crash_reports_unavailable)
            }
            loading = false
        }
    }
}
