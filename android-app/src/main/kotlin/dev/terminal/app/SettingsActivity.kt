package dev.terminal.app

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.terminal.app.ui.SettingsSearchTarget
import dev.terminal.app.ui.SettingsOverview
import dev.terminal.app.ui.TerminalActivity

/** Settings overview: categories keep infrequent controls out of the main list. */
class SettingsActivity : TerminalActivity() {
    private var hasAvfPrivileges by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasAvfPrivileges = SessionProfiles(this).hasAvfPrivileges()
        compose { SettingsOverview({ finish() }, ::open, hasAvfPrivileges) }
    }

    override fun onResume() {
        super.onResume()
        hasAvfPrivileges = SessionProfiles(this).hasAvfPrivileges()
    }

    private fun open(target: SettingsSearchTarget) {
        if (target.screen == "debug") {
            if (BuildConfig.DEBUG) {
                startActivity(Intent().setClassName(this, "dev.terminal.app.DebugSettingsActivity")
                    .putExtra(SettingsDetailActivity.EXTRA_SECTION_INDEX, target.sectionIndex))
            }
            return
        }
        if (target.screen == SettingsDetailActivity.AVF && !SessionProfiles(this).hasAvfPrivileges()) return
        val activity = when (target.screen) {
            "environments", "storage" -> EnvironmentActivity::class.java
            "profiles" -> ProfilesActivity::class.java
            "workspaces" -> WorkspacesActivity::class.java
            "shared-folders" -> SharedFoldersActivity::class.java
            "backups" -> EnvironmentBackupActivity::class.java
            "updates" -> AppUpdatesActivity::class.java
            "about" -> AppInfoActivity::class.java
            "diagnostics" -> DiagnosticsActivity::class.java
            "usb" -> UsbDevicesActivity::class.java
            "palette" -> PaletteEditorActivity::class.java
            else -> diagnosticsDestination(target.screen) ?: if (UserspaceLinuxSettingsActivity.owns(target.screen)) UserspaceLinuxSettingsActivity::class.java
                else SettingsDetailActivity::class.java
        }
        startActivity(Intent(this, activity)
            .putExtra(SettingsDetailActivity.EXTRA_CATEGORY, target.screen)
            .putExtra(SettingsDetailActivity.EXTRA_SECTION_INDEX, target.sectionIndex))
    }
}
