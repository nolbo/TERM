package dev.terminal.app

import android.content.Intent
import android.os.Bundle
import dev.terminal.app.databinding.ActivitySetupBinding
import dev.terminal.app.ui.TerminalActivity

class SetupActivity : TerminalActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        installRootInsets(binding.root)
        if (getSharedPreferences("crash-report", MODE_PRIVATE).contains("report")) {
            startActivityWithMotion(Intent(this, CrashReportActivity::class.java))
        }
        binding.automaticSetupButton.setOnClickListener {
            // Notification permission is optional for opening the console and must
            // never gate the primary zero-setup shell path.
            launchTerminal()
        }
        binding.localImageButton.setOnClickListener {
            startActivityWithMotion(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_IMAGE_PICKER, true))
        }
    }

    private fun launchTerminal() = startActivityWithMotion(
        Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_START_RUNTIME, true),
    )
}
