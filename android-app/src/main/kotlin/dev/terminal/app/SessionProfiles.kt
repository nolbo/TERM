package dev.terminal.app

import android.content.Context
import dev.terminal.runtime.session.SessionProfile
import dev.terminal.runtime.session.SessionRuntime
import java.io.File
import java.util.UUID

/** Default profiles may be overridden or tombstoned without touching live sessions. */
class SessionProfiles(context: Context) {
    private val app = context.applicationContext
    fun hasAvfPrivileges(): Boolean =
        app.checkSelfPermission("android.permission.MANAGE_VIRTUAL_MACHINE") == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            app.checkSelfPermission("android.permission.USE_CUSTOM_VIRTUAL_MACHINE") == android.content.pm.PackageManager.PERMISSION_GRANTED
    private val registry = dev.terminal.runtime.session.StorageRegistry(app.filesDir) {
        app.getSharedPreferences("session-profiles", Context.MODE_PRIVATE).all.mapValues { it.value as String }
    }
    private val runtimePreferences = context.getSharedPreferences(SettingsDetailActivity.RUNTIME_PREFERENCES, Context.MODE_PRIVATE)
    fun all(): List<SessionProfile> {
        val defaults = builtIns()
        return registry.transaction { profiles(defaults) }.filter {
            it.id != SessionProfile.builtInId(SessionRuntime.AVF) || hasAvfPrivileges()
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
    fun save(profile: SessionProfile, newStorageName: String? = null) {
        registry.transaction { save(profile, newStorageName) }
    }
    fun delete(id: String) {
        registry.transaction { deleteProfile(id, builtIns().any { it.id == id }) }
    }
}
