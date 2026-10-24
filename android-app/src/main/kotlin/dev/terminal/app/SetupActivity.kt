package dev.terminal.app

import android.content.Intent
import android.os.Bundle
import dev.terminal.app.ui.SetupScreen
import dev.terminal.app.ui.TerminalActivity

class SetupActivity : TerminalActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (getSharedPreferences("crash-report", MODE_PRIVATE).contains("report")) {
            startActivity(Intent(this, CrashReportActivity::class.java))
            finish()
            return
        }
        if (setupCompleted() && !intent.getBooleanExtra(EXTRA_SHOW_SETUP, false)) {
            launchMain()
            finish()
            return
        }
        compose { SetupScreen(onStart = {
            // Notification permission is optional for opening the console and must
            // never gate profile selection or session creation.
            completeSetup()
            launchMain()
            finish()
        }) }
    }

    private fun setupCompleted() = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        .getBoolean(KEY_COMPLETED, false)

    private fun completeSetup() = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_COMPLETED, true)
        .apply()

    private fun launchMain() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    companion object {
        const val EXTRA_SHOW_SETUP = "show_setup"
        private const val PREFERENCES = "setup-state"
        private const val KEY_COMPLETED = "completed"
    }
}
