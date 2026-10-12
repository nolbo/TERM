package dev.terminal.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import dev.terminal.app.ui.CrashScreen
import dev.terminal.app.ui.TerminalActivity

class CrashReportActivity : TerminalActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("crash-report", MODE_PRIVATE)
        val report = preferences.getString("report", getString(R.string.crash_report_empty)).orEmpty()
        compose { CrashScreen({ finish() }, report, onCopy = {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("TERM crash", report))
        }, onClose = { preferences.edit().remove("report").apply(); finish() }) }
    }
}
