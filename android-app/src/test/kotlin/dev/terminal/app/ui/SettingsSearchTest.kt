package dev.terminal.app.ui

import dev.terminal.app.R
import org.junit.Assert.*
import org.junit.Test

class SettingsSearchTest {
    @Test fun avfCategoriesAndNestedSearchResultsRequirePrivileges() {
        val unprivileged = visibleSettingsSearchEntries(false)
        assertFalse(unprivileged.any { it.target.screen == "avf" })
        assertEquals(listOf("environments", "profiles", "workspaces", "shared-folders", "language", "terminal", "rootless", "diagnostics", "updates", "about"),
            unprivileged.filter { it.path.isEmpty() }.map { it.target.screen })
        assertEquals(settingsSearchEntries, visibleSettingsSearchEntries(true))
        assertTrue(visibleSettingsSearchEntries(true).any { it.target.screen == "avf" && it.path.isNotEmpty() })
        assertTrue(unprivileged.any { it.title == R.string.wake_policy })
        assertTrue(unprivileged.any { it.title == R.string.battery_optimization })
        assertTrue(unprivileged.any { it.title == R.string.avf_image_section && it.target.screen == "environments" })
    }

    @Test fun matchesTitlesDescriptionsAndAncestorPaths() {
        val entry = settingsSearchEntries.first { it.title == R.string.font_size }
        val korean = LocalizedSettingsEntry(entry, "글꼴 크기", "터미널 글자 크기를 조정합니다", "터미널 설정 › 터미널 모양 › 텍스트")
        assertTrue(korean.matches("  모양   글꼴  "))
        assertTrue(korean.matches("조정"))
        assertFalse(korean.matches("글꼴 USB"))
        val english = LocalizedSettingsEntry(entry, "Font size", "Adjust terminal text size", "Terminal › Appearance")
        assertTrue(english.matches(" APPEARANCE\tfont "))
        assertFalse(english.matches("missing"))
    }

    @Test fun nestedResultsOpenTheirOwningScreensAndSections() {
        fun target(title: Int) = settingsSearchEntries.single { it.title == title }.target
        assertEquals(SettingsSearchTarget("appearance", 2), target(R.string.font_size))
        assertEquals(SettingsSearchTarget("terminal", 2), target(R.string.follow_tui_background))
        assertEquals(SettingsSearchTarget("terminal", 4), target(R.string.scrollback))
        assertEquals(SettingsSearchTarget("terminal", 6), target(R.string.shared_storage_access))
        assertEquals(SettingsSearchTarget("terminal", 7), target(R.string.wake_policy))
        assertEquals(SettingsSearchTarget("terminal", 7), target(R.string.battery_optimization))
        assertEquals(SettingsSearchTarget("terminal", 8), target(R.string.android_permissions))
        assertEquals(SettingsSearchTarget("hotkeys"), target(R.string.hotkey_columns))
        assertEquals(SettingsSearchTarget("toolbar-sessions"), target(R.string.session_skip_close_confirmation))
        assertEquals(SettingsSearchTarget("palette"), target(R.string.palette_cursor))
        assertEquals(SettingsSearchTarget("usb"), target(R.string.usb_section_storage))
        assertEquals(SettingsSearchTarget("profiles"), target(R.string.profile_directory))
        assertEquals(SettingsSearchTarget("profiles"), target(R.string.profile_list))
        assertEquals(SettingsSearchTarget("environments"), target(R.string.storage_title))
        assertEquals(SettingsSearchTarget("avf", 2), target(R.string.local_services_title))
        assertEquals(SettingsSearchTarget("backups"), target(R.string.backup_title))
        assertEquals(SettingsSearchTarget("backups"), target(R.string.backup_import))
        assertEquals(SettingsSearchTarget("environments"), target(R.string.backup_create))
        assertFalse(visibleSettingsSearchEntries(false).any { it.title == R.string.local_services_title })
        assertEquals(SettingsSearchTarget("rootless-dns"), target(R.string.userspace_dns))
        assertEquals(SettingsSearchTarget("diagnostics-userspace"), target(R.string.userspace_proot))
        assertFalse(settingsSearchEntries.any { it.target.screen in setOf("rootless-info", "toolbar-memory") })
        assertEquals(SettingsSearchTarget("crash-reports"), target(R.string.crash_reports_title))
        assertEquals(SettingsSearchTarget("diagnostics-memory"), target(R.string.memory_diagnostics_title))
        assertEquals(SettingsSearchTarget("diagnostics-avf"), target(R.string.avf_diagnostics_title))
        assertTrue(visibleSettingsSearchEntries(false).any { it.target.screen == "diagnostics-avf" })
    }

    @Test fun homeIncludesEnvironmentsAndResultKeysAreUnique() {
        assertEquals(listOf("environments", "profiles", "workspaces", "shared-folders", "language", "terminal", "rootless", "avf", "diagnostics", "updates", "about"),
            settingsSearchEntries.filter { it.path.isEmpty() }.map { it.target.screen })
        val keys = settingsSearchEntries.map { it.target.screen to it.title }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(settingsSearchEntries.filter { it.path.isNotEmpty() }.all { it.target.sectionIndex >= 0 })
        assertFalse(settingsSearchEntries.any { it.target.screen == "native" })
    }

    @Test fun diagnosticsAndPortsHaveOnlyTheirAssignedSettingsDestinations() {
        assertEquals("diagnostics", settingsSearchEntries.single { it.title == R.string.settings_diagnostics_title }.target.screen)
        assertEquals("crash-reports", settingsSearchEntries.single { it.title == R.string.crash_reports_title }.target.screen)
        val services = settingsSearchEntries.single { it.title == R.string.local_services_title }
        assertEquals(SettingsSearchTarget("avf", 2), services.target)
        assertEquals(listOf(R.string.settings_avf_title, R.string.section_execution), services.path)
        assertFalse(settingsSearchEntries.any { it.target.screen == "local-services" })
        assertFalse(visibleSettingsSearchEntries(false).any { it.title == R.string.local_services_title })
    }

    @Test fun backupsAreNestedUnderEnvironmentsAndImportOpensASeparateFileFlow() {
        val backups = settingsSearchEntries.filter { it.title in listOf(R.string.backup_title, R.string.backup_import, R.string.backup_create) }
        assertTrue(backups.all { it.path.first() == R.string.storage_title })
        val imported = backups.single { it.title == R.string.backup_import }
        assertEquals(listOf(R.string.storage_title, R.string.backup_title), imported.path)
        assertEquals(SettingsSearchTarget("backups"), imported.target)
        assertEquals(R.string.backup_import_summary, imported.summary)
    }
}
