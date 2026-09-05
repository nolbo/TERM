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
        }
        compose { SetupScreen(onStart = {
            // Notification permission is optional for opening the console and must
            // never gate the primary zero-setup shell path.
            launchTerminal()
        }, onImage = {
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_IMAGE_PICKER, true))
        }) }
    }

    private fun launchTerminal() = startActivity(
        Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_START_RUNTIME, true),
    )
}
