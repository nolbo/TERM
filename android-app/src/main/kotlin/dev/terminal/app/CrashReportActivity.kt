package dev.terminal.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import dev.terminal.app.databinding.ActivityCrashReportBinding
import dev.terminal.app.ui.TerminalActivity

class CrashReportActivity : TerminalActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityCrashReportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        installRootInsets(binding.root)
        val preferences = getSharedPreferences("crash-report", MODE_PRIVATE)
        val report = preferences.getString("report", "저장된 크래시 보고서가 없습니다.").orEmpty()
        binding.reportText.text = report
        binding.toolbar.setNavigationOnClickListener { finishWithMotion() }
        binding.copyButton.setOnClickListener {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("Droid Terminal crash", report))
        }
        binding.closeButton.setOnClickListener { preferences.edit().remove("report").apply(); finishWithMotion() }
    }
}
