package dev.terminal.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import dev.terminal.app.ui.CrashScreen
import dev.terminal.app.ui.TerminalActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CrashReportActivity : TerminalActivity() {
    private var report by mutableStateOf<CrashReport?>(null)
    private var loading by mutableStateOf(true)
    private var deleting by mutableStateOf(false)
    private var deleteError by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            report = withContext(Dispatchers.IO) { runCatching {
                val store = CrashReportStore.from(this@CrashReportActivity)
                val requestedId = intent.getStringExtra(EXTRA_REPORT_ID)
                val saved = if (requestedId == null) store.importPending(this@CrashReportActivity) else store.read(requestedId)
                saved?.also { store.acknowledge(this@CrashReportActivity, it.id) }
            }.getOrNull() }
            loading = false
        }
        compose { CrashScreen(::closeReport,
            if (loading) getString(R.string.crash_reports_loading) else report?.text ?: getString(R.string.crash_report_empty), onCopy = {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("TERM crash", report?.text.orEmpty()))
        }, onDelete = ::deleteReport, canCopy = report != null, deleting = deleting, deleteError = deleteError) }
    }

    private fun deleteReport() {
        val selected = report ?: return
        if (deleting) return
        deleting = true
        deleteError = null
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { CrashReportStore.from(this@CrashReportActivity).delete(selected.id) }
                closeReport()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                deleteError = getString(R.string.crash_report_delete_failed)
            } finally {
                deleting = false
            }
        }
    }

    private fun closeReport() {
        finish()
    }

    companion object {
        private const val EXTRA_REPORT_ID = "crash_report_id"
        internal fun intent(context: Context, reportId: String) =
            Intent(context, CrashReportActivity::class.java).putExtra(EXTRA_REPORT_ID, reportId)
    }
}
