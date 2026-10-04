package dev.terminal.runtime.session

import java.io.File
import org.json.JSONObject

/** Storage identities are host-selected identifiers, never caller-supplied paths. */
object SessionStorage {
    private val uuidPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    fun validate(id: String) {
        require(id == "legacy" || id.matches(uuidPattern)) {
            "Invalid storage ID"
        }
    }

    fun root(files: File, runtime: SessionRuntime, id: String): File {
        validate(id)
        if (id != "legacy" && id != migratedLegacyId(files, runtime)) return File(files, "profile-storage/${runtime.wireName}/$id")
        return legacyRoot(files, runtime)
    }

    /** Only fixed, runtime-specific locations can be adopted; the manifest never supplies paths. */
    fun legacyRoot(files: File, runtime: SessionRuntime): File =
        File(files, when (runtime) {
            SessionRuntime.NATIVE -> "native"
            SessionRuntime.ROOTLESS -> "rootless/debian"
            SessionRuntime.ROOTLESS_UBUNTU -> "rootless/ubuntu"
            SessionRuntime.ROOTLESS_ARCH -> "rootless/archlinux"
            SessionRuntime.AVF -> "vm/default"
        })

    fun migratedLegacyId(files: File, runtime: SessionRuntime): String? {
        val manifest = File(files, "storage-registry.json")
        if (!manifest.exists()) return null
        return JSONObject(manifest.readText()).optJSONObject("legacyStorageIds")
            ?.optString(runtime.wireName)?.takeIf { it.isNotEmpty() }?.also { validate(it); require(it != "legacy") }
    }

    fun isMigratedLegacy(files: File, runtime: SessionRuntime, id: String): Boolean =
        id != "legacy" && id == migratedLegacyId(files, runtime)

    fun avfInstanceId(files: File, storageId: String): String =
        if (storageId == "legacy" || isMigratedLegacy(files, SessionRuntime.AVF, storageId)) "default" else storageId
}
