package dev.terminal.runtime.session

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** Metadata and filesystem operations only; SessionStorage remains the path resolver.
 * The atomic manifest and process-shared lock serialize profile reference changes with deletion.
 * Call filesystem operations from an IO dispatcher, never from a composable.
 */
class StorageRegistry(files: File, private val migrateProfiles: () -> Map<String, String> = { emptyMap() }) {
    private val files = files.canonicalFile
    data class Key(val runtime: SessionRuntime, val storageId: String) {
        init { SessionStorage.validate(storageId) }
        fun json() = JSONObject().put("runtime", runtime.wireName).put("storageId", storageId)
    }
    private val manifest = File(files, "storage-registry.json")
    private val lockFile = File(files, "storage-registry.lock")
    private data class Size(val bytes: Long, val measuredAt: Long)
    private val sizes = ConcurrentHashMap<Key, Size>()

    // File locks exclude other processes; this monitor also excludes overlapping JVM locks.
    fun <T> transaction(block: Transaction.() -> T): T = synchronized(locks.computeIfAbsent(lockFile.absolutePath) { Any() }) {
        files.mkdirs()
        RandomAccessFile(lockFile, "rw").use { file -> file.channel.lock().use {
            val state = if (manifest.exists()) JSONObject(manifest.readText()) else JSONObject()
                .put("profiles", JSONObject(migrateProfiles())).put("storages", JSONArray()).put("deleted", JSONArray())
            Transaction(state).block()
        } }
    }
    inner class Transaction internal constructor(private val state: JSONObject) {
        fun name(key: Key): String? = state.optJSONObject("storageNames")?.optString("${key.runtime.wireName}:${key.storageId}")?.takeIf { it.isNotBlank() }
        fun rename(key: Key, value: String, defaults: List<SessionProfile> = emptyList()) {
            check(key in keys() + profiles(defaults).map { Key(it.runtime, it.storageId) }) { "Storage not found" }
            check(key !in decodeKeys(state.getJSONArray("deleted"))) { "Storage has been deleted" }
            val name = value.trim()
            require(name.length <= MAX_NAME_LENGTH && name.none { it.isISOControl() }) { "Invalid storage name" }
            val names = state.optJSONObject("storageNames") ?: JSONObject().also { state.put("storageNames", it) }
            val id = "${key.runtime.wireName}:${key.storageId}"
            if (name.isEmpty()) names.remove(id) else names.put(id, name)
            state.put("storages", JSONArray((keys() + key).map { it.json() }))
            persist()
        }
        fun overrides(): Map<String, String> = state.getJSONObject("profiles").let { profiles ->
            profiles.keys().asSequence().associateWith { profiles.getString(it) }
        }
        fun profiles(defaults: List<SessionProfile>): List<SessionProfile> {
            val overrides = overrides()
            return (defaults.map { it.id } + overrides.keys).distinct().mapNotNull { id ->
                if (id in overrides) overrides.getValue(id).takeIf { it.isNotEmpty() }?.let { SessionLaunchSpec.fromJson(it).toProfile() }
                else defaults.firstOrNull { it.id == id }
            }
        }
        fun keys(): Set<Key> = buildSet {
            addAll(decodeKeys(state.getJSONArray("storages")))
            overrides().values.filter { it.isNotEmpty() }.forEach { encoded ->
                val spec = SessionLaunchSpec.fromJson(encoded); add(Key(spec.runtime, spec.storageId))
            }
            SessionRuntime.entries.forEach { runtime ->
                val legacy = Key(runtime, "legacy")
                if (Files.exists(root(legacy).toPath(), NOFOLLOW_LINKS)) add(legacy)
                File(files, "profile-storage/${runtime.wireName}").listFiles()?.forEach { child ->
                    if (runCatching { SessionStorage.validate(child.name) }.isSuccess && child.name != "legacy") add(Key(runtime, child.name))
                }
            }
        }
        fun validateConnection(key: Key) {
            check(key !in decodeKeys(state.getJSONArray("deleted"))) { "Storage has been deleted; choose a new environment" }
            if (key.storageId != "legacy") check(keys().none { it.storageId == key.storageId && it.runtime != key.runtime }) {
                "Storage runtime or Rootless distribution is incompatible"
            }
        }
        fun save(profile: SessionProfile) {
            val encoded = profile.resolve().toJson().toString()
            val key = Key(profile.runtime, profile.storageId)
            validateConnection(key)
            val allKeys = keys() + key
            state.getJSONObject("profiles").put(profile.id, encoded)
            state.put("storages", JSONArray(allKeys.map { it.json() }))
            persist()
        }
        fun deleteProfile(id: String, builtIn: Boolean) {
            // Remember even never-launched environments when their last reference disappears.
            state.put("storages", JSONArray(keys().map { it.json() }))
            if (builtIn) state.getJSONObject("profiles").put(id, "") else state.getJSONObject("profiles").remove(id)
            persist()
        }
        fun requireOrphan(key: Key, defaults: List<SessionProfile>) {
            check(key.storageId != "legacy") { "Legacy storage cannot be deleted" }
            check(profiles(defaults).none { it.runtime == key.runtime && it.storageId == key.storageId }) { "Storage is referenced by a profile" }
            check(key in keys()) { "Storage not found" }
        }
        /** Caller has excluded session creation and verified the controller is idle. */
        fun delete(key: Key) {
            check(key.storageId != "legacy")
            deleteTree(root(key).toPath())
            if (key.runtime == SessionRuntime.AVF) deleteTree(File(files, "shared-vms/${key.storageId}").toPath())
            state.put("storages", JSONArray(keys().filter { it != key }.map { it.json() }))
            state.put("deleted", JSONArray((decodeKeys(state.getJSONArray("deleted")) + key).map { it.json() }))
            state.optJSONObject("storageNames")?.remove("${key.runtime.wireName}:${key.storageId}")
            sizes.remove(key)
            persist()
        }
        private fun persist() {
            val temporary = File(files, "storage-registry.json.tmp")
            java.io.FileOutputStream(temporary).use { out -> out.write(state.toString().toByteArray()); out.fd.sync() }
            Files.move(temporary.toPath(), manifest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
    fun root(key: Key): File = SessionStorage.root(files, key.runtime, key.storageId)
    fun cachedSize(key: Key): Long? = sizes[key]?.takeIf { System.currentTimeMillis() - it.measuredAt < 60_000 }?.bytes
    /** Lazy approximate logical bytes; never follows symlinks outside a storage root. */
    fun measure(key: Key): Long {
        cachedSize(key)?.let { return it }
        var bytes = treeSize(root(key).toPath())
        if (key.runtime == SessionRuntime.AVF && key.storageId != "legacy") bytes += treeSize(File(files, "shared-vms/${key.storageId}").toPath())
        sizes[key] = Size(bytes, System.currentTimeMillis())
        return bytes
    }
    companion object {
        const val MAX_NAME_LENGTH = 120
        private val locks = ConcurrentHashMap<String, Any>()
        private fun decodeKeys(array: JSONArray) = List(array.length()) { index -> array.getJSONObject(index).let {
            Key(requireNotNull(SessionRuntime.fromWireName(it.getString("runtime"))), it.getString("storageId"))
        } }.toSet()
        private fun treeSize(path: java.nio.file.Path): Long {
            if (!Files.exists(path, NOFOLLOW_LINKS)) return 0
            var size = 0L
            Files.walkFileTree(path, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    size += attrs.size(); return java.nio.file.FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: java.nio.file.Path, failure: java.io.IOException): java.nio.file.FileVisitResult {
                    if (failure !is java.nio.file.NoSuchFileException) throw failure
                    return java.nio.file.FileVisitResult.CONTINUE // A running environment may remove temporary files.
                }
            })
            return size
        }
        private fun deleteTree(path: java.nio.file.Path) {
            if (!Files.exists(path, NOFOLLOW_LINKS)) return
            // Refuse a symlinked parent rather than traverse into an unrelated tree.
            check(path.parent.toFile().canonicalFile.toPath() == path.parent.toAbsolutePath().normalize()) { "Unsafe storage parent" }
            Files.walkFileTree(path, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    Files.delete(file); return java.nio.file.FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: java.nio.file.Path, failure: java.io.IOException?): java.nio.file.FileVisitResult {
                    if (failure != null) throw failure
                    Files.delete(dir); return java.nio.file.FileVisitResult.CONTINUE
                }
            })
        }
    }
}
