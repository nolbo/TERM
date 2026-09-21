package dev.terminal.runtime.session

import java.io.File

/** Storage identities are host-selected identifiers, never caller-supplied paths. */
object SessionStorage {
    fun validate(id: String) {
        require(id == "legacy" || id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) {
            "Invalid storage ID"
        }
    }

    fun root(files: File, runtime: SessionRuntime, id: String): File {
        validate(id)
        if (id != "legacy") return File(files, "profile-storage/${runtime.wireName}/$id")
        return File(files, when (runtime) {
            SessionRuntime.NATIVE -> "native"
            SessionRuntime.ROOTLESS -> "rootless/debian"
            SessionRuntime.ROOTLESS_UBUNTU -> "rootless/ubuntu"
            SessionRuntime.ROOTLESS_ARCH -> "rootless/archlinux"
            SessionRuntime.AVF -> "vm/default"
        })
    }
}
