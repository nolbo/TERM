package dev.terminal.runtime.storage

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import java.util.UUID

/** Host-only boot snapshots survive owner death and connection edits. Guests never see this directory. */
class AvfSharedDirectoryLeases(files: File) {
    private val files = files.canonicalFile
    fun record(instanceId: String, folderIds: List<String>, artifact: String? = null, androidStorage: Boolean = false) = synchronized(LOCK) {
        validateInstance(instanceId)
        require(folderIds.size <= 128 && (folderIds.isNotEmpty() || androidStorage) && folderIds.distinct().size == folderIds.size)
        folderIds.forEach { require(UUID.fromString(it).toString() == it) }
        require(artifact == null || ARTIFACT.matches(artifact))
        val root = root(create = true)!!
        val target = File(root, key(instanceId))
        require(!Files.isSymbolicLink(target.toPath())) { "Unsafe AVF share lease" }
        val temporary = File(root, ".${UUID.randomUUID()}")
        try {
            Files.write(temporary.toPath(), JSONObject().put("instanceId", instanceId)
                .put("folders", JSONArray(folderIds)).put("artifact", artifact).put("androidStorage", androidStorage)
                .toString().toByteArray(), CREATE_NEW)
            Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary.toPath()) }
    }

    fun owners(folderId: String): List<String> = synchronized(LOCK) {
        require(UUID.fromString(folderId).toString() == folderId)
        leases().filter { folderId in it.folders }.map { it.instanceId }
    }

    fun androidStorageOwners(): List<String> = synchronized(LOCK) {
        leases().filter { it.androidStorage }.map { it.instanceId }
    }

    private data class Lease(val instanceId: String, val folders: List<String>, val artifact: String?, val androidStorage: Boolean)

    private fun leases(): List<Lease> {
        val root = root(create = false) ?: return emptyList()
        val entries = checkNotNull(root.listFiles()) { "Cannot read AVF share leases" }
        require(entries.size <= 2048) { "AVF share lease limit exceeded" }
        return entries.filter { !it.name.startsWith('.') }.map(::readLease)
    }

    private fun readLease(file: File): Lease {
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && Files.size(file.toPath()) <= 16 * 1024) { "Unsafe AVF share lease" }
        val json = JSONObject(file.readText())
        val instanceId = json.getString("instanceId")
        validateInstance(instanceId)
        require(file.name == key(instanceId))
        val storage = if (json.has("androidStorage")) json.get("androidStorage").also { require(it is Boolean) } as Boolean else false
        val ids = json.getJSONArray("folders")
        require(ids.length() <= 128 && (ids.length() > 0 || storage))
        val folders = List(ids.length()) { ids.getString(it).also { id -> require(UUID.fromString(id).toString() == id) } }
        require(folders.distinct().size == folders.size)
        val artifact = if (json.has("artifact")) json.getString("artifact").also { require(ARTIFACT.matches(it)) } else null
        return Lease(instanceId, folders, artifact, storage)
    }

    /** Only call after the platform proves this VM stopped or absent. */
    fun release(instanceId: String, cleanup: (String?) -> Unit = {}): String? = synchronized(LOCK) {
        validateInstance(instanceId)
        val root = root(create = false) ?: return@synchronized null
        val file = File(root, key(instanceId))
        if (Files.notExists(file.toPath(), NOFOLLOW_LINKS)) return@synchronized null
        val lease = readLease(file)
        require(lease.instanceId == instanceId)
        cleanup(lease.artifact) // Keep the record if revoked storage is temporarily inaccessible.
        Files.delete(file.toPath())
        lease.artifact
    }

    private fun root(create: Boolean): File? {
        val root = File(files, "avf-share-leases")
        if (Files.notExists(root.toPath(), NOFOLLOW_LINKS)) {
            if (!create) return null
            Files.createDirectory(root.toPath())
        }
        require(Files.isDirectory(root.toPath(), NOFOLLOW_LINKS) && root.canonicalFile == root.absoluteFile) {
            "Unsafe AVF share lease root"
        }
        return root
    }

    private fun key(instanceId: String) = MessageDigest.getInstance("SHA-256").digest(instanceId.toByteArray())
        .joinToString("") { "%02x".format(it) } + ".json"
    private fun validateInstance(value: String) = require(Regex("[A-Za-z0-9._-]{1,64}").matches(value))
    private companion object {
        val LOCK = Any()
        val ARTIFACT = Regex("v-[A-Za-z0-9_-]{1,64}")
    }
}
