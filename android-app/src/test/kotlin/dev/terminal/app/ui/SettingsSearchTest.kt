package dev.terminal.app.ui

import dev.terminal.app.R
import org.junit.Assert.*
import org.junit.Test

class SettingsSearchTest {
    @Test fun avfCategoriesAndNestedSearchResultsRequirePrivileges() {
        val unprivileged = visibleSettingsSearchEntries(false)
        assertFalse(unprivileged.any { it.target.screen == "avf" })
        assertEquals(listOf("environments", "profiles", "workspaces", "shared-folders", "local-services", "backups", "language", "terminal", "rootless", "native", "diagnostics", "updates", "about"),
            unprivileged.filter { it.path.isEmpty() }.map { it.target.screen })
        assertEquals(settingsSearchEntries, visibleSettingsSearchEntries(true))
        assertTrue(visibleSettingsSearchEntries(true).any { it.target.screen == "avf" && it.path.isNotEmpty() })
        assertTrue(unprivileged.any { it.title == R.string.wake_policy })
        assertTrue(unprivileged.any { it.title == R.string.battery_optimization })
        assertTrue(unprivileged.any { it.title == R.string.debian_image && it.target.screen == "environments" })
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
        assertEquals(SettingsSearchTarget("local-services"), target(R.string.local_services_title))
        assertEquals(SettingsSearchTarget("backups"), target(R.string.backup_title))
        assertEquals(SettingsSearchTarget("backups"), target(R.string.backup_import))
        assertEquals(SettingsSearchTarget("environments"), target(R.string.backup_create))
        assertTrue(visibleSettingsSearchEntries(false).any { it.target.screen == "local-services" })
        assertEquals(SettingsSearchTarget("rootless-dns"), target(R.string.userspace_dns))
        assertEquals(SettingsSearchTarget("rootless", 2), target(R.string.userspace_proot))
        assertEquals(SettingsSearchTarget("rootless-info"), target(R.string.userspace_runtime_info))
        assertFalse(settingsSearchEntries.any { it.title == R.string.rootless_limitations || it.target.screen == "rootless-limitations" })
        assertEquals(listOf(R.string.settings_userspace_title, R.string.userspace_information),
            settingsSearchEntries.single { it.title == R.string.userspace_runtime_info }.path)
        assertEquals(SettingsSearchTarget("diagnostics", 1), target(R.string.diagnostics_title))
        assertEquals(SettingsSearchTarget("diagnostics", 2), target(R.string.crash_reports_title))
        assertEquals(SettingsSearchTarget("avf", 2), target(R.string.forwarded_ports_title))
    }

    @Test fun homeIncludesEnvironmentsAndResultKeysAreUnique() {
        assertEquals(listOf("environments", "profiles", "workspaces", "shared-folders", "local-services", "backups", "language", "terminal", "rootless", "avf", "native", "diagnostics", "updates", "about"),
            settingsSearchEntries.filter { it.path.isEmpty() }.map { it.target.screen })
        val keys = settingsSearchEntries.map { it.target.screen to it.title }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(settingsSearchEntries.filter { it.path.isNotEmpty() }.all { it.target.sectionIndex >= 0 })
    }

    @Test fun diagnosticsAndPortsHaveOnlyTheirAssignedSettingsDestinations() {
        assertTrue(settingsSearchEntries.filter { it.title in listOf(R.string.settings_diagnostics_title,
            R.string.diagnostics_title, R.string.crash_reports_title) }.all { it.target.screen == "diagnostics" })
        assertTrue(settingsSearchEntries.filter { it.title == R.string.forwarded_ports_title }.all { it.target.screen == "avf" })
        assertFalse(visibleSettingsSearchEntries(false).any { it.title == R.string.forwarded_ports_title })
    }
}
