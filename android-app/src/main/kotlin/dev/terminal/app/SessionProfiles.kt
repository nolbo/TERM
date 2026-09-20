package dev.terminal.app

import android.content.Context
import dev.terminal.runtime.session.SessionProfile
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.session.SessionLaunchSpec
import java.io.File
import java.util.UUID

/** Built-ins are derived, never stored as user-editable records. */
class SessionProfiles(context: Context) {
    private val preferences = context.getSharedPreferences("session-profiles", Context.MODE_PRIVATE)
    private val runtimePreferences = context.getSharedPreferences(SettingsDetailActivity.RUNTIME_PREFERENCES, Context.MODE_PRIVATE)
    fun all(): List<SessionProfile> = builtIns() + preferences.all.keys.sorted().map { id ->
        SessionLaunchSpec.fromJson(requireNotNull(preferences.getString(id, null))).toProfile()
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
    fun duplicate(profile: SessionProfile) = profile.copy(id = UUID.randomUUID().toString(), name = "${profile.name} copy")
    fun save(profile: SessionProfile) {
        require(!profile.id.startsWith("builtin:")) { "Built-in profiles cannot be edited" }
        val encoded = profile.resolve().toJson().toString()
        check(preferences.edit().putString(profile.id, encoded).commit()) { "Could not save profile" }
    }
    fun delete(id: String) {
        require(!id.startsWith("builtin:")) { "Built-in profiles cannot be deleted" }
        check(preferences.edit().remove(id).commit()) { "Could not delete profile" }
    }
}
