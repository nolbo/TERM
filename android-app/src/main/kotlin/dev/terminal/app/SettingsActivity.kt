package dev.terminal.app

import android.content.Intent
import android.os.Bundle
import dev.terminal.app.ui.SettingsSearchTarget
import dev.terminal.app.ui.SettingsOverview
import dev.terminal.app.ui.TerminalActivity

/** Settings overview: categories keep infrequent controls out of the main list. */
class SettingsActivity : TerminalActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        compose { SettingsOverview({ finish() }, ::open) }
    }

    private fun open(target: SettingsSearchTarget) {
        val activity = when (target.screen) {
            "profiles" -> ProfilesActivity::class.java
            "updates" -> AppUpdatesActivity::class.java
            "usb" -> UsbDevicesActivity::class.java
            "palette" -> PaletteEditorActivity::class.java
            else -> SettingsDetailActivity::class.java
        }
        startActivity(Intent(this, activity)
            .putExtra(SettingsDetailActivity.EXTRA_CATEGORY, target.screen)
            .putExtra(SettingsDetailActivity.EXTRA_SECTION_INDEX, target.sectionIndex))
    }
}
