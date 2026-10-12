package dev.terminal.runtime.storage

import android.content.Context
import java.io.File

/**
 * Owns the only host directory eligible for future virtiofs export. It never
 * returns arbitrary application-private paths and keeps every VM in its own
 * sanitized directory.
 */
class SharedDirectoryManager(context: Context) {
    private val root = File(context.applicationContext.filesDir, "shared-vms")

    fun provision(vmInstanceId: String): Result<File> = runCatching {
        require(VM_ID.matches(vmInstanceId)) { "Invalid VM instance ID" }
        val directory = File(root, vmInstanceId).canonicalFile
        require(directory.toPath().startsWith(root.canonicalFile.toPath())) { "Shared directory escapes runtime root" }
        require(directory.mkdirs() || directory.isDirectory) { "Cannot create shared directory" }
        directory
    }

    fun directoryFor(vmInstanceId: String): File = provision(vmInstanceId).getOrThrow()

    private companion object {
        val VM_ID = Regex("[A-Za-z0-9._-]{1,64}")
    }
}
