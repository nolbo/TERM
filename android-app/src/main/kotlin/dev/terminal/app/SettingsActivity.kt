package dev.terminal.app

import android.content.Intent
import android.os.Bundle
import dev.terminal.app.ui.SettingsOverview
import dev.terminal.app.ui.TerminalActivity

/** Settings overview: categories keep infrequent controls out of the main list. */
class SettingsActivity : TerminalActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        compose { SettingsOverview({ finish() }, ::open) }
    }

    private fun open(category: String) {
        if (category == "profiles") startActivity(Intent(this, ProfilesActivity::class.java))
        else if (category == "updates") startActivity(Intent(this, AppUpdatesActivity::class.java))
        else startActivity(Intent(this, SettingsDetailActivity::class.java)
            .putExtra(SettingsDetailActivity.EXTRA_CATEGORY, category))
    }
}
