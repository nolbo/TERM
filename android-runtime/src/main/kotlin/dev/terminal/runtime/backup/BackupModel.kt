package dev.terminal.runtime.backup

import dev.terminal.runtime.session.*
import org.json.JSONArray
import org.json.JSONObject

enum class BackupKind { DATA, FULL }
enum class BackupWorkspace { NONE, ENVIRONMENT, LEGACY_SHARED }

/** Only domain values enter the archive; a backup never supplies a host path or destination ID. */
data class BackupManifest(
    val kind: BackupKind,
    val source: Environment,
    val architecture: String,
    val termVersion: String,
    val storageFormat: String,
    val workspace: BackupWorkspace,
    val profiles: List<SessionProfile>,
    val bytes: Long,
    val entries: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val sourceLegacyLayout: Boolean = source.storage.id == "legacy",
) {
    init {
        require(source.runtime != EnvironmentRuntime.AVF) { "AVF backups are not supported" }
        require(source.storage.id != "legacy" || sourceLegacyLayout)
        require(kind != BackupKind.FULL || source.runtime == EnvironmentRuntime.ROOTLESS)
        require(architecture.length in 1..64 && termVersion.length in 1..128)
        require(storageFormat in FORMATS)
        require((source.runtime == EnvironmentRuntime.NATIVE) == (storageFormat == "native-data-1"))
        require(bytes in 0..BackupLimits.MAX_BYTES && entries in 1..BackupLimits.MAX_ENTRIES && createdAt >= 0)
        require(profiles.size <= 256 && profiles.all {
            it.runtime == source.sessionRuntime && it.storageId == source.storage.id && it.environmentId == source.id
        }) { "Profile references do not match the backup environment" }
        profiles.forEach { it.resolve(source) }
    }
    fun toJson() = JSONObject().put("format", "TERM").put("version", 1).put("compression", "gzip")
        .put("kind", kind.name).put("source", source.toJson()).put("architecture", architecture)
        .put("termVersion", termVersion).put("storageFormat", storageFormat).put("workspace", workspace.name)
        .put("profiles", JSONArray(profiles.map { it.resolve(source).toJson() }))
        .put("bytes", bytes).put("entries", entries).put("createdAt", createdAt)
        .put("sourceLegacyLayout", sourceLegacyLayout)

    /** Data restores deliberately have no rootfs; the user explicitly installs that distribution later. */
    fun checkCompatibility(hostArchitecture: String) {
        require(architecture == hostArchitecture) { "Backup architecture $architecture is incompatible with $hostArchitecture" }
        require(source.runtime != EnvironmentRuntime.ROOTLESS || architecture == "arm64-v8a") { "Unsupported Rootless architecture" }
    }
    companion object {
        val FORMATS = setOf("native-data-1", "native-hardlinks-1", "emulated-hardlinks-1", "legacy-emulated-hardlinks")
        fun fromJson(json: JSONObject): BackupManifest {
            require(json.getString("format") == "TERM" && json.getInt("version") == 1 && json.getString("compression") == "gzip") {
                "Unsupported TERM backup format/version"
            }
            val profiles = json.getJSONArray("profiles")
            require(profiles.length() <= 256)
            return BackupManifest(BackupKind.valueOf(json.getString("kind")), Environment.fromJson(json.getJSONObject("source")),
                json.getString("architecture"), json.getString("termVersion"), json.getString("storageFormat"),
                BackupWorkspace.valueOf(json.getString("workspace")), List(profiles.length()) {
                    SessionLaunchSpec.fromJson(profiles.getJSONObject(it).toString()).toProfile()
                }, json.getLong("bytes"), json.getInt("entries"), json.getLong("createdAt"),
                json.optBoolean("sourceLegacyLayout", json.getJSONObject("source").getString("storageId") == "legacy"))
        }
    }
}

internal object BackupLimits {
    const val MAX_BYTES = 256L * 1024 * 1024 * 1024
    const val MAX_ENTRIES = 500_000
    const val MAX_METADATA = 64L * 1024 * 1024
    const val MAX_TEXT = 4096
    const val MAX_MANIFEST = 1024 * 1024
    const val BUFFER = 64 * 1024
    const val RESERVE = 32L * 1024 * 1024
}

data class BackupProgress(val phase: String, val bytes: Long = 0, val total: Long = 0, val entries: Int = 0)
