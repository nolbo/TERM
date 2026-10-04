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
    data class Usage(val bytes: Long, val incomplete: Boolean = false) {
        fun json(): JSONObject = JSONObject().put("bytes", bytes).put("incomplete", incomplete)
        companion object {
            fun parse(encoded: String): Usage = JSONObject(encoded).let { Usage(it.getLong("bytes"), it.getBoolean("incomplete")) }
        }
    }
    private data class Size(val usage: Usage, val measuredAt: Long)
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
    inner class Transaction internal constructor(internal val state: JSONObject) {
        // One snapshot per locked transaction; mutations update it before persisting.
        private val deletedKeys = decodeKeys(state.getJSONArray("deleted")).toMutableSet()
        init { migrateAvfHomeDirectory() }

        /** Only saved defaults are upgraded; live launch snapshots and guest files stay untouched. */
        private fun migrateAvfHomeDirectory() {
            if (state.optBoolean("avfHomeDirectoryMigrated", false)) return
            val saved = state.getJSONObject("profiles")
            saved.keys().asSequence().forEach { id ->
                val encoded = saved.getString(id)
                if (encoded.isNotEmpty()) {
                    val profile = SessionLaunchSpec.fromJson(encoded).toProfile()
                    if (profile.runtime == SessionRuntime.AVF && profile.workingDirectory == "/home/user")
                        saved.put(id, profile.copy(workingDirectory = SessionProfile.AVF_HOME_DIRECTORY).resolve().toJson().toString())
                }
            }
            // Persist even an empty upgrade so a later explicit /home/user choice is respected.
            state.put("avfHomeDirectoryMigrated", true)
            persist()
        }

        fun migratedLegacyId(runtime: SessionRuntime): String? = state.optJSONObject("legacyStorageIds")
            ?.optString(runtime.wireName)?.takeIf { it.isNotEmpty() }?.also { SessionStorage.validate(it); check(it != "legacy") }
        fun canonicalKey(key: Key): Key = if (key.storageId == "legacy")
            migratedLegacyId(key.runtime)?.let { Key(key.runtime, it) } ?: key else key
        fun isDeleted(key: Key): Boolean = canonicalKey(key) in deletedKeys
        fun resolveProfile(profile: SessionProfile): SessionProfile =
            profile.copy(storageId = canonicalKey(Key(profile.runtime, profile.storageId)).storageId)

        /** Service calls this only after excluding creation and proving the old controller idle.
         * One atomic manifest commit adopts the fixed old paths, names and every profile reference.
         * No copy/rename journal is needed: until commit, the old environment remains untouched.
         */
        fun migrateLegacy(runtime: SessionRuntime, defaults: List<SessionProfile>): Key {
            migratedLegacyId(runtime)?.let { return Key(runtime, it) }
            val old = Key(runtime, "legacy")
            val linked = profiles(defaults).filter { it.runtime == runtime && it.storageId == "legacy" }
            val existing = keys()
            check(old in existing || linked.isNotEmpty()) { "No legacy environment to migrate" }
            val key = Key(runtime, java.util.UUID.randomUUID().toString())
            val names = state.optJSONObject("storageNames")
            val oldName = name(old)
            val occupied = names?.keys()?.asSequence()?.map { names.getString(it) }?.toList().orEmpty()
            putName(key, oldName ?: defaultName(linked.firstOrNull()?.name ?: runtime.wireName, occupied))
            names?.remove("${runtime.wireName}:legacy")
            val ids = state.optJSONObject("legacyStorageIds") ?: JSONObject().also { state.put("legacyStorageIds", it) }
            ids.put(runtime.wireName, key.storageId)
            val saved = state.getJSONObject("profiles")
            saved.keys().asSequence().toList().forEach { id ->
                val encoded = saved.getString(id)
                if (encoded.isNotEmpty()) {
                    val profile = SessionLaunchSpec.fromJson(encoded).toProfile()
                    if (profile.runtime == runtime && profile.storageId == "legacy")
                        saved.put(id, profile.copy(storageId = key.storageId).resolve().toJson().toString())
                }
            }
            state.put("storages", JSONArray(((existing - old) + key).map { it.json() }))
            sizes.remove(old)
            persist()
            return key
        }
        fun name(key: Key): String? = state.optJSONObject("storageNames")?.optString("${key.runtime.wireName}:${key.storageId}")?.takeIf { it.isNotBlank() }
        fun rename(key: Key, value: String, defaults: List<SessionProfile> = emptyList()) {
            check(key in keys() + profiles(defaults).map { Key(it.runtime, it.storageId) }) { "Storage not found" }
            check(key !in deletedKeys) { "Storage has been deleted" }
            putName(key, value)
            state.put("storages", JSONArray((keys() + key).map { it.json() }))
            persist()
        }
        private fun putName(key: Key, value: String) {
            val name = value.trim()
            require(name.length <= MAX_NAME_LENGTH && name.none { it.isISOControl() }) { "Invalid storage name" }
            val names = state.optJSONObject("storageNames") ?: JSONObject().also { state.put("storageNames", it) }
            val id = "${key.runtime.wireName}:${key.storageId}"
            if (name.isEmpty()) names.remove(id) else names.put(id, name)
        }
        fun overrides(): Map<String, String> = state.getJSONObject("profiles").let { profiles ->
            profiles.keys().asSequence().associateWith { profiles.getString(it) }
        }
        fun profiles(defaults: List<SessionProfile>): List<SessionProfile> {
            val overrides = overrides()
            return (defaults.map { it.id } + overrides.keys.sorted()).distinct().mapNotNull { id ->
                if (id in overrides) overrides.getValue(id).takeIf { it.isNotEmpty() }?.let { SessionLaunchSpec.fromJson(it).toProfile() }
                else defaults.firstOrNull { it.id == id }
            }.map(::resolveProfile)
        }
        fun keys(): Set<Key> = buildSet {
            addAll(decodeKeys(state.getJSONArray("storages")))
            overrides().values.filter { it.isNotEmpty() }.forEach { encoded ->
                val spec = SessionLaunchSpec.fromJson(encoded); add(Key(spec.runtime, spec.storageId))
            }
            SessionRuntime.entries.forEach { runtime ->
                val legacy = Key(runtime, "legacy")
                if (migratedLegacyId(runtime) == null && Files.exists(root(legacy).toPath(), NOFOLLOW_LINKS)) add(legacy)
                File(files, "profile-storage/${runtime.wireName}").listFiles()?.forEach { child ->
                    if (runCatching { SessionStorage.validate(child.name) }.isSuccess && child.name != "legacy") add(Key(runtime, child.name))
                }
            }
        }.filterTo(mutableSetOf()) { it !in deletedKeys }
        fun validateConnection(key: Key) {
            val resolved = canonicalKey(key)
            check(!isDeleted(resolved)) { "Storage has been deleted; choose a new environment" }
            if (resolved.storageId != "legacy") check((keys() + SessionRuntime.entries.mapNotNull { runtime -> migratedLegacyId(runtime)?.let { Key(runtime, it) } })
                .none { it.storageId == resolved.storageId && it.runtime != resolved.runtime }) {
                "Storage runtime or Rootless distribution is incompatible"
            }
        }
        fun save(profile: SessionProfile, newStorageName: String? = null) {
            val resolved = resolveProfile(profile)
            val encoded = resolved.resolve().toJson().toString()
            val key = Key(resolved.runtime, resolved.storageId)
            validateConnection(key)
            val existingKeys = keys()
            val fresh = key.storageId != "legacy" && key !in existingKeys
            if (newStorageName != null) check(fresh) { "A new storage name cannot rename an existing environment" }
            if (fresh) {
                val storedNames = state.optJSONObject("storageNames")
                val names = storedNames?.keys()?.asSequence()?.map { storedNames.getString(it) }?.toList().orEmpty()
                putName(key, if (newStorageName.isNullOrBlank()) defaultName(profile.name, names) else uniqueName(newStorageName, names))
            }
            state.getJSONObject("profiles").put(profile.id, encoded)
            state.put("storages", JSONArray((existingKeys + key).map { it.json() }))
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
        /** Persisted activation token; old worker requests default to generation zero. */
        fun imageGeneration(key: Key): Long = state.optJSONObject("avfImageGenerations")
            ?.optLong(canonicalKey(key).storageId, 0L)?.also { check(it >= 0) } ?: 0L
        fun validateImageGeneration(key: Key, generation: Long) {
            validateConnection(key)
            check(generation == imageGeneration(key)) { "Image installation was superseded by removal" }
        }

        /** Caller has excluded session creation and verified the controller is idle. */
        fun removeSoftware(key: Key, removeHome: Boolean = false) {
            validateConnection(key)
            check(key.runtime != SessionRuntime.NATIVE) { "Native has no removable distribution image" }
            val directory = root(key).toPath()
            if (key.runtime == SessionRuntime.AVF) {
                // Even a cancelled worker can still be waiting to commit. Invalidate its
                // activation token before deletion, including when filesystem removal fails.
                val next = Math.incrementExact(imageGeneration(key))
                val generations = state.optJSONObject("avfImageGenerations") ?: JSONObject().also { state.put("avfImageGenerations", it) }
                generations.put(canonicalKey(key).storageId, next)
                persist()
                deleteTree(directory)
            } else {
                check(directory.toFile().canonicalFile.toPath() == directory.toAbsolutePath().normalize()) { "Unsafe environment root" }
                dev.terminal.runtime.rootless.removeRootlessImage(directory.resolve("rootfs"),
                    home = directory.resolve("home"), removeHome = removeHome)
            }
            sizes.remove(canonicalKey(key))
        }

        /** Caller has excluded session creation and verified the controller is idle. */
        fun delete(key: Key, updateMetadata: () -> Unit = {}) {
            check(key.storageId != "legacy")
            deleteTree(root(key).toPath())
            if (key.runtime == SessionRuntime.AVF) deleteTree(File(files, "shared-vms/${SessionStorage.avfInstanceId(files, key.storageId)}").toPath())
            state.put("storages", JSONArray(keys().filter { it != key }.map { it.json() }))
            deletedKeys.add(key)
            state.put("deleted", JSONArray(deletedKeys.map { it.json() }))
            state.optJSONObject("storageNames")?.remove("${key.runtime.wireName}:${key.storageId}")
            sizes.remove(key)
            updateMetadata()
            persist()
        }
        internal fun persist() {
            val temporary = File(files, "storage-registry.json.tmp")
            java.io.FileOutputStream(temporary).use { out -> out.write(state.toString().toByteArray()); out.fd.sync() }
            Files.move(temporary.toPath(), manifest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
    fun root(key: Key): File = SessionStorage.root(files, key.runtime, key.storageId)
    fun cachedUsage(key: Key): Usage? = sizes[key]?.takeIf { System.currentTimeMillis() - it.measuredAt < 60_000 }?.usage
    fun cachedSize(key: Key): Long? = cachedUsage(key)?.bytes
    /** Lazy approximate logical bytes; never follows symlinks outside a storage root. */
    fun measure(key: Key): Long = measureUsage(key).bytes
    fun measureUsage(key: Key): Usage {
        cachedUsage(key)?.let { return it }
        val primary = treeSize(root(key).toPath())
        val shared = if (key.runtime == SessionRuntime.AVF)
            treeSize(File(files, "shared-vms/${SessionStorage.avfInstanceId(files, key.storageId)}").toPath()) else Usage(0)
        val usage = Usage(primary.bytes + shared.bytes, primary.incomplete || shared.incomplete)
        // A not-yet-installed environment must be measured again as soon as files appear.
        if (usage.bytes > 0) sizes[key] = Size(usage, System.currentTimeMillis()) else sizes.remove(key)
        return usage
    }
    companion object {
        const val MAX_NAME_LENGTH = 120
        fun defaultName(profileName: String, existingNames: Collection<String> = emptyList()): String =
            uniqueName(boundedName("Storage for " + profileName.trim().filterNot { it.isISOControl() }, MAX_NAME_LENGTH).trimEnd(), existingNames)

        /** The caller reserves this name while holding the manifest transaction lock. */
        fun uniqueName(requested: String, existingNames: Collection<String>): String {
            val base = requested.trim()
            require(base.isNotEmpty() && base.length <= MAX_NAME_LENGTH && base.none { it.isISOControl() }) { "Invalid storage name" }
            val occupied = existingNames.toHashSet()
            var candidate = base
            var number = 2
            while (candidate in occupied) {
                val suffix = " (${number++})"
                candidate = boundedName(base, MAX_NAME_LENGTH - suffix.length).trimEnd() + suffix
            }
            return candidate
        }
        private fun boundedName(value: String, length: Int): String = value.take(length).let {
            if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it
        }
        private val locks = ConcurrentHashMap<String, Any>()
        private fun decodeKeys(array: JSONArray) = List(array.length()) { index -> array.getJSONObject(index).let {
            Key(requireNotNull(SessionRuntime.fromWireName(it.getString("runtime"))), it.getString("storageId"))
        } }.toSet()
        private fun treeSize(path: java.nio.file.Path): Usage {
            // exists() also returns false when access is denied. Only absence means zero.
            val attributes = try {
                Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            } catch (_: java.nio.file.NoSuchFileException) {
                return Usage(0)
            }
            // Require readable, searchable storage roots before reporting any byte count.
            if (attributes.isDirectory && (!Files.isReadable(path) || !Files.isExecutable(path)))
                throw java.nio.file.AccessDeniedException(path.toString())
            var size = 0L
            var incomplete = false
            Files.walkFileTree(path, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun preVisitDirectory(dir: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    if (!Files.isReadable(dir) || !Files.isExecutable(dir)) {
                        if (dir == path) throw java.nio.file.AccessDeniedException(dir.toString())
                        incomplete = true
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE
                    }
                    return java.nio.file.FileVisitResult.CONTINUE
                }
                override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    size += attrs.size(); return java.nio.file.FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: java.nio.file.Path, failure: java.io.IOException): java.nio.file.FileVisitResult {
                    if (failure is java.nio.file.AccessDeniedException && file != path) {
                        // PRoot bind placeholders and guest-only directories may have mode 000.
                        incomplete = true
                    } else if (failure !is java.nio.file.NoSuchFileException) throw failure
                    return java.nio.file.FileVisitResult.CONTINUE // A running environment may remove temporary files.
                }
            })
            return Usage(size, incomplete)
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
