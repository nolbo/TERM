package dev.terminal.app.ui

import dev.terminal.app.R
import org.junit.Assert.*
import org.junit.Test

class SettingsSearchTest {
    @Test fun avfCategoriesAndNestedSearchResultsRequirePrivileges() {
        val unprivileged = visibleSettingsSearchEntries(false)
        assertFalse(unprivileged.any { it.target.screen == "avf" })
        assertEquals(listOf("profiles", "language", "terminal", "rootless", "native", "updates"),
            unprivileged.filter { it.path.isEmpty() }.map { it.target.screen })
        assertEquals(settingsSearchEntries, visibleSettingsSearchEntries(true))
        assertTrue(visibleSettingsSearchEntries(true).any { it.target.screen == "avf" && it.path.isNotEmpty() })
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
        assertEquals(SettingsSearchTarget("terminal", 4), target(R.string.scrollback))
        assertEquals(SettingsSearchTarget("terminal", 6), target(R.string.shared_storage_access))
        assertEquals(SettingsSearchTarget("hotkeys"), target(R.string.hotkey_columns))
        assertEquals(SettingsSearchTarget("palette"), target(R.string.palette_cursor))
        assertEquals(SettingsSearchTarget("usb"), target(R.string.usb_section_storage))
        assertEquals(SettingsSearchTarget("profiles"), target(R.string.profile_directory))
    }

    @Test fun homeRemainsSevenCategoriesAndResultKeysAreUnique() {
        assertEquals(listOf("profiles", "language", "terminal", "rootless", "avf", "native", "updates"),
            settingsSearchEntries.filter { it.path.isEmpty() }.map { it.target.screen })
        val keys = settingsSearchEntries.map { it.target.screen to it.title }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(settingsSearchEntries.filter { it.path.isNotEmpty() }.all { it.target.sectionIndex >= 0 })
    }
}
