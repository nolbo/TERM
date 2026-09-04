package dev.terminal.app

import android.content.Intent
import android.os.Bundle
import dev.terminal.app.databinding.ActivitySettingsBinding
import dev.terminal.app.ui.TerminalActivity

/** Settings overview: categories keep infrequent controls out of the main list. */
class SettingsActivity : TerminalActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        installRootInsets(binding.root)
        binding.toolbar.setNavigationOnClickListener { finishWithMotion() }
        binding.appearanceItem.setOnClickListener { open(SettingsDetailActivity.APPEARANCE) }
        binding.rootlessItem.setOnClickListener { open(SettingsDetailActivity.ROOTLESS) }
        binding.avfItem.setOnClickListener { open(SettingsDetailActivity.AVF) }
        binding.nativeItem.setOnClickListener { open(SettingsDetailActivity.NATIVE) }
    }

    private fun open(category: String) = startActivityWithMotion(Intent(this, SettingsDetailActivity::class.java)
        .putExtra(SettingsDetailActivity.EXTRA_CATEGORY, category))
}
