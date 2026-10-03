package dev.terminal.app.ui

import dev.terminal.app.R

/** A search result opens a screen, never executes a setting's action. */
internal data class SettingsSearchTarget(val screen: String, val sectionIndex: Int = 0)

internal fun visibleSettingsSearchEntries(hasAvfPrivileges: Boolean): List<SettingsSearchEntry> =
    settingsSearchEntries.filter { hasAvfPrivileges || it.target.screen != "avf" }

internal data class SettingsSearchEntry(
    val title: Int,
    val summary: Int?,
    val path: List<Int>,
    val target: SettingsSearchTarget,
    val icon: Int,
)

internal data class LocalizedSettingsEntry(
    val entry: SettingsSearchEntry,
    val title: String,
    val summary: String,
    val path: String,
) {
    fun matches(query: String): Boolean = query.trim().split(Regex("\\s+"))
        .all { word -> listOf(title, summary, path).any { it.contains(word, ignoreCase = true) } }
}

/**
 * Resource IDs keep the entire index in the currently selected app language.
 * Section indices follow SettingsDetailActivity's LazyColumn (description is item 0).
 * Keep these destinations in sync when moving sections or introducing settings.
 */
internal val settingsSearchEntries: List<SettingsSearchEntry> = buildList {
    fun entry(screen: String, title: Int, summary: Int? = null, path: List<Int> = emptyList(),
              section: Int = 0, icon: Int = R.drawable.ic_material_symbol_settings_24) {
        add(SettingsSearchEntry(title, summary, path, SettingsSearchTarget(screen, section), icon))
    }
    entry("storage", R.string.storage_title, R.string.storage_summary, icon = R.drawable.ic_material_symbol_storage_24)
    entry("profiles", R.string.profiles, R.string.profiles_summary, icon = R.drawable.ic_material_symbol_add_24)
    entry("language", R.string.settings_language_title, R.string.settings_language_summary, icon = R.drawable.ic_material_symbol_text_format_24)
    entry("terminal", R.string.settings_terminal_title, R.string.settings_terminal_summary, icon = R.drawable.ic_material_symbol_settings_24)
    entry("rootless", R.string.runtime_rootless_title, R.string.settings_rootless_summary, icon = R.drawable.ic_runtime_rootless_group)
    entry("avf", R.string.settings_avf_title, R.string.settings_avf_summary, icon = R.drawable.ic_material_symbol_memory_24)
    entry("native", R.string.settings_native_title, R.string.settings_native_summary, icon = R.drawable.ic_runtime_native)
    entry("updates", R.string.app_updates, R.string.update_settings_summary, icon = R.drawable.ic_material_symbol_download_24)
    entry("about", R.string.app_info, R.string.app_info_summary, icon = R.drawable.ic_app_info_24)
    listOf(R.string.app_version, R.string.app_package_name, R.string.app_build_type, R.string.app_build_number,
        R.string.update_channel_label, R.string.app_build_revision, R.string.open_source_licenses).forEach {
        entry("about", it, path = listOf(R.string.app_info), icon = R.drawable.ic_app_info_24)
    }

    val terminal = listOf(R.string.settings_terminal_title)
    val appearance = terminal + R.string.settings_appearance_title
    val hotkeys = terminal + R.string.toolbar_settings + R.string.settings_hotkeys_title
    val palette = appearance + R.string.customize_palette
    entry("appearance", R.string.settings_appearance_title, R.string.settings_appearance_summary, terminal)
    entry("toolbar-memory", R.string.toolbar_memory, null, terminal + R.string.toolbar_settings)
    entry("toolbar-commands", R.string.toolbar_commands, null, terminal + R.string.toolbar_settings)
    entry("toolbar", R.string.toolbar_settings, R.string.toolbar_settings_summary, terminal)
    entry("hotkeys", R.string.settings_hotkeys_title, R.string.settings_hotkeys_summary, terminal + R.string.toolbar_settings)
    entry("usb", R.string.usb_title, R.string.usb_summary, terminal)
    entry("palette", R.string.customize_palette, R.string.customize_palette_support, appearance)
    entry("language", R.string.settings_language, R.string.settings_language_support, listOf(R.string.settings_language_title), section = 1)
    entry("terminal", R.string.terminal_type, R.string.terminal_type_support, terminal + R.string.section_shell, section = 3)
    entry("terminal", R.string.login_shell, R.string.login_shell_support, terminal + R.string.section_shell, section = 3)
    entry("terminal", R.string.terminal_open_links, R.string.terminal_open_links_support, terminal + R.string.section_shell, section = 3)
    entry("terminal", R.string.session_task_notifications, R.string.session_task_notifications_support, terminal + R.string.section_shell, section = 3)
    entry("terminal", R.string.session_task_system_settings, null, terminal + R.string.section_shell, section = 3)
    entry("terminal", R.string.kitty_remote_control, R.string.kitty_remote_control_support, terminal + R.string.section_shell, section = 3)
    entry("terminal", R.string.kitty_graphics_compatibility, R.string.kitty_graphics_compatibility_support, terminal + R.string.section_shell, section = 3)
    entry("terminal", R.string.smooth_scrolling, R.string.smooth_scrolling_support, terminal + R.string.section_history, section = 4)
    entry("terminal", R.string.scrollback, R.string.scrollback_support, terminal + R.string.section_history, section = 4)
    entry("terminal", R.string.haptic_feedback, R.string.haptic_feedback_support, terminal + R.string.section_haptics, section = 5)
    entry("terminal", R.string.haptic_density, R.string.haptic_density_support, terminal + R.string.section_haptics, section = 5)
    entry("terminal", R.string.haptic_scroll, R.string.haptic_scroll_support, terminal + R.string.section_haptics, section = 5)
    entry("terminal", R.string.shared_storage_access, null, terminal + R.string.section_shared_storage, section = 6)
    entry("terminal", R.string.android_permissions, R.string.android_permissions_support, terminal, section = 7)
    entry("appearance", R.string.terminal_padding, R.string.terminal_padding_support, appearance + R.string.section_terminal_layout, section = 1)
    entry("appearance", R.string.font_size, R.string.font_size_support, appearance + R.string.section_text, section = 2)
    entry("appearance", R.string.add_custom_font, R.string.add_custom_font_support, appearance + R.string.section_custom_fonts, section = 3)
    entry("appearance", R.string.add_italic_font, R.string.add_italic_font_support, appearance + R.string.section_custom_fonts, section = 3)
    entry("appearance", R.string.color_palette, R.string.color_palette_support, appearance + R.string.section_palette, section = 4)
    entry("terminal", R.string.follow_tui_background, R.string.follow_tui_background_support, terminal + R.string.section_terminal_customization, section = 2)
    entry("native", R.string.default_shell, R.string.default_shell_support, listOf(R.string.settings_native_title) + R.string.section_shell, section = 1)
    entry("avf", R.string.memory, R.string.memory_support, listOf(R.string.settings_avf_title) + R.string.section_resources, section = 1)
    entry("avf", R.string.virtual_cpu, R.string.virtual_cpu_support, listOf(R.string.settings_avf_title) + R.string.section_resources, section = 1)
    entry("avf", R.string.protected_vm, R.string.protected_vm_support, listOf(R.string.settings_avf_title) + R.string.section_resources, section = 1)
    entry("avf", R.string.guest_network, R.string.guest_network_support, listOf(R.string.settings_avf_title) + R.string.section_resources, section = 1)
    entry("avf", R.string.wake_policy, R.string.wake_policy_support, listOf(R.string.settings_avf_title) + R.string.section_execution, section = 2)
    entry("avf", R.string.debian_image, R.string.debian_image_support, listOf(R.string.settings_avf_title) + R.string.section_execution, section = 2)
    entry("avf", R.string.diagnostics_title, R.string.diagnostics_support, listOf(R.string.settings_avf_title) + R.string.section_execution, section = 2)
    entry("rootless", R.string.shared_workspace, R.string.shared_workspace_support, listOf(R.string.runtime_rootless_title) + R.string.section_environment, section = 2)
    entry("rootless", R.string.rootless_limitations, R.string.rootless_limitations_support, listOf(R.string.runtime_rootless_title) + R.string.section_environment, section = 2)
    entry("toolbar", R.string.toolbar_enabled, R.string.toolbar_enabled_summary, terminal + R.string.toolbar_settings)
    entry("hotkeys", R.string.hotkey_columns, null, hotkeys)
    entry("hotkeys", R.string.hotkey_font_size, null, hotkeys)
    entry("hotkeys", R.string.hotkey_add, null, hotkeys)
    entry("hotkeys", R.string.hotkey_name, R.string.hotkey_name_support, hotkeys)
    entry("hotkeys", R.string.hotkey_shortcut, R.string.hotkey_shortcut_support, hotkeys)
    entry("hotkeys", R.string.hotkey_span, null, hotkeys)
    entry("hotkeys", R.string.hotkey_reset, R.string.hotkey_reset_support, hotkeys)
    entry("palette", R.string.palette_foreground, null, palette, section = 0)
    entry("palette", R.string.palette_background, null, palette, section = 0)
    entry("palette", R.string.palette_cursor, null, palette, section = 0)
    entry("palette", R.string.palette_standard_colors, null, palette, section = 0)
    entry("palette", R.string.palette_bright_colors, null, palette, section = 0)
    entry("palette", R.string.palette_hue, null, palette, section = 0)
    entry("palette", R.string.palette_saturation, null, palette, section = 0)
    entry("palette", R.string.palette_brightness, null, palette, section = 0)
    entry("palette", R.string.palette_hex_label, R.string.palette_hex_support, palette, section = 0)
    entry("usb", R.string.usb_section_devices, null, terminal + R.string.usb_title, section = 0)
    entry("usb", R.string.usb_section_requests, null, terminal + R.string.usb_title, section = 0)
    entry("usb", R.string.usb_section_grants, null, terminal + R.string.usb_title, section = 0)
    entry("usb", R.string.usb_section_storage, null, terminal + R.string.usb_title, section = 0)
    entry("usb", R.string.usb_section_tools, null, terminal + R.string.usb_title, section = 0)
    entry("usb", R.string.usb_section_help, null, terminal + R.string.usb_title, section = 0)
    entry("profiles", R.string.profile_name, path = listOf(R.string.profiles, R.string.profile_settings))
    entry("profiles", R.string.profile_icon, path = listOf(R.string.profiles, R.string.profile_settings))
    entry("profiles", R.string.profile_runtime, path = listOf(R.string.profiles, R.string.profile_settings))
    entry("profiles", R.string.profile_distribution, path = listOf(R.string.profiles, R.string.profile_settings))
    entry("profiles", R.string.profile_storage, path = listOf(R.string.profiles, R.string.profile_settings))
    entry("profiles", R.string.profile_shell, path = listOf(R.string.profiles, R.string.profile_settings))
    entry("profiles", R.string.profile_arguments, path = listOf(R.string.profiles, R.string.profile_settings))
    entry("profiles", R.string.profile_directory, path = listOf(R.string.profiles, R.string.profile_settings))
    entry("profiles", R.string.profile_startup, path = listOf(R.string.profiles, R.string.profile_settings))
    entry("profiles", R.string.profile_environment, path = listOf(R.string.profiles, R.string.profile_settings))
    entry("updates", R.string.update_channel_label, path = listOf(R.string.app_updates))
    entry("updates", R.string.update_check, path = listOf(R.string.app_updates))
    listOf(R.string.runtime_debian_title, R.string.runtime_ubuntu_title, R.string.runtime_arch_title).forEach {
        entry("rootless", it, path = listOf(R.string.runtime_rootless_title, R.string.section_status), section = 1)
    }
}
