package dev.terminal.app

import android.content.Context
import dev.terminal.runtime.session.SessionProfile
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.session.Environment
import dev.terminal.runtime.session.EnvironmentRegistry
import dev.terminal.runtime.session.SessionLaunchSpec
import org.json.JSONArray
import java.util.UUID

/** Built-in profiles may be overridden or tombstoned without touching live sessions. */
class SessionProfiles(context: Context) {
    private val app = context.applicationContext
    fun hasAvfPrivileges(): Boolean =
        app.checkSelfPermission("android.permission.MANAGE_VIRTUAL_MACHINE") == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            app.checkSelfPermission("android.permission.USE_CUSTOM_VIRTUAL_MACHINE") == android.content.pm.PackageManager.PERMISSION_GRANTED
    private val registry = EnvironmentRegistry(app.filesDir) {
        app.getSharedPreferences("session-profiles", Context.MODE_PRIVATE).all.mapValues { it.value as String }
    }
    private val runtimePreferences = context.getSharedPreferences(SettingsDetailActivity.RUNTIME_PREFERENCES, Context.MODE_PRIVATE)
    private val orderPreferences = app.getSharedPreferences(PROFILE_ORDER_PREFERENCES, Context.MODE_PRIVATE)
    private fun orderedProfiles(): List<SessionProfile> {
        val profiles = registry.transaction(builtIns()) { profiles() }.associateBy { it.id }
        return resolveProfileOrder(profiles.keys.toList(), parseProfileOrder(orderPreferences.getString(KEY_PROFILE_ORDER, null)))
            .map(profiles::getValue)
    }
    fun all(): List<SessionProfile> {
        return orderedProfiles().filter {
            it.id != SessionProfile.builtInId(SessionRuntime.AVF) || hasAvfPrivileges()
        }
    }
    fun reorder(ids: List<String>) {
        val order = reorderProfileIds(orderedProfiles().map { it.id }, ids)
        orderPreferences.edit().putString(KEY_PROFILE_ORDER, JSONArray(order).toString()).apply()
    }
    fun find(id: String): SessionProfile? = all().firstOrNull { it.id == id }
    fun builtIns(): List<SessionProfile> {
        return SessionRuntime.entries.map { runtime ->
            SessionProfile(SessionProfile.builtInId(runtime), when (runtime) {
                SessionRuntime.NATIVE -> "Native"
                SessionRuntime.ROOTLESS -> "Debian"
                SessionRuntime.ROOTLESS_UBUNTU -> "Ubuntu"
                SessionRuntime.ROOTLESS_ARCH -> "Arch"
                SessionRuntime.AVF -> "AVF"
            }, runtime, sessionShell(runtime.wireName),
                if (runtime != SessionRuntime.AVF && runtimePreferences.getBoolean("native-login-shell", true)) listOf("-l") else emptyList(),
                sessionInitialDirectory(runtime.wireName),
                mapOf("TERM" to runtimePreferences.getString("native-term", "xterm-256color").orEmpty()))
        }
    }
    fun duplicate(profile: SessionProfile) = profile.copy(id = UUID.randomUUID().toString(), name = "${profile.name} copy",
        storageId = UUID.randomUUID().toString(), environmentId = null)
    fun environments(): List<Environment> = registry.transaction(builtIns()) { all() }
    fun resolve(profile: SessionProfile): SessionLaunchSpec = registry.transaction(builtIns()) { resolveLaunch(profile.resolve()) }
    fun save(profile: SessionProfile, newEnvironment: Environment): SessionProfile =
        registry.transaction(builtIns()) { save(profile, newEnvironment) }
    fun save(profile: SessionProfile, newStorageName: String? = null) {
        registry.transaction(builtIns()) {
            val fresh = profile.environmentId == null && profile.storageId != "legacy" &&
                all().none { it.sessionRuntime == profile.runtime && it.storage.id == profile.storageId }
            save(profile, if (fresh) Environment.create(profile.runtime,
                newStorageName?.takeIf(String::isNotBlank) ?: EnvironmentRegistry.defaultEnvironmentName(profile.name), profile.storageId) else null)
        }
    }
    fun delete(id: String) {
        registry.transaction(builtIns()) { deleteProfile(id, builtIns().any { it.id == id }) }
    }
}
