package dev.terminal.app

import android.content.Context
import dev.terminal.runtime.session.SessionProfile
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.session.SessionLaunchSpec
import java.io.File
import java.util.UUID

/** Default profiles may be overridden or tombstoned without touching live sessions. */
class SessionProfiles(context: Context) {
    private val app = context.applicationContext
    fun hasAvfPrivileges(): Boolean =
        app.checkSelfPermission("android.permission.MANAGE_VIRTUAL_MACHINE") == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            app.checkSelfPermission("android.permission.USE_CUSTOM_VIRTUAL_MACHINE") == android.content.pm.PackageManager.PERMISSION_GRANTED
    private val preferences = context.getSharedPreferences("session-profiles", Context.MODE_PRIVATE)
    private val runtimePreferences = context.getSharedPreferences(SettingsDetailActivity.RUNTIME_PREFERENCES, Context.MODE_PRIVATE)
    fun all(): List<SessionProfile> {
        val defaults = builtIns()
        val stored = preferences.all
        return (defaults.map { it.id } + stored.keys.sorted()).distinct().mapNotNull { id ->
            if (id == SessionProfile.builtInId(SessionRuntime.AVF) && !hasAvfPrivileges()) return@mapNotNull null
            if (stored.containsKey(id)) {
                (stored[id] as String).takeIf { it.isNotEmpty() }?.let {
                    SessionLaunchSpec.fromJson(it).toProfile()
                }
            } else defaults.firstOrNull { it.id == id }
        }
    }
    fun find(id: String): SessionProfile? = all().firstOrNull { it.id == id }
    fun builtIns(): List<SessionProfile> {
        val nativeShell = runtimePreferences.getString(SettingsDetailActivity.KEY_NATIVE_SHELL, "/system/bin/sh")
            ?.takeIf { File(it).canExecute() } ?: "/system/bin/sh"
        return SessionRuntime.entries.map { runtime ->
            SessionProfile(SessionProfile.builtInId(runtime), when (runtime) {
                SessionRuntime.NATIVE -> "Native"
                SessionRuntime.ROOTLESS -> "Debian"
                SessionRuntime.ROOTLESS_UBUNTU -> "Ubuntu"
                SessionRuntime.ROOTLESS_ARCH -> "Arch"
                SessionRuntime.AVF -> "AVF"
            }, runtime, sessionShell(runtime.wireName, nativeShell),
                if (runtime != SessionRuntime.AVF && runtimePreferences.getBoolean("native-login-shell", true)) listOf("-l") else emptyList(),
                sessionInitialDirectory(runtime.wireName),
                mapOf("TERM" to runtimePreferences.getString("native-term", "xterm-256color").orEmpty()))
        }
    }
    fun duplicate(profile: SessionProfile) = profile.copy(id = UUID.randomUUID().toString(), name = "${profile.name} copy",
        storageId = UUID.randomUUID().toString())
    fun save(profile: SessionProfile) {
        val encoded = profile.resolve().toJson().toString()
        check(preferences.edit().putString(profile.id, encoded).commit()) { "Could not save profile" }
    }
    fun delete(id: String) {
        val editor = preferences.edit()
        if (builtIns().any { it.id == id }) editor.putString(id, "") else editor.remove(id)
        check(editor.commit()) { "Could not delete profile" }
    }
}
