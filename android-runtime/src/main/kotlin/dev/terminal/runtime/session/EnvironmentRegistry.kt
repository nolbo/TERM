package dev.terminal.runtime.session

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Environment identity and profile references, on StorageRegistry's atomic manifest and lock.
 * Keeping one transaction boundary prevents migration/profile saves racing filesystem deletion.
 * StorageRegistry still owns path adoption, tombstones, enumeration and safe filesystem walks.
 */
class EnvironmentRegistry(val storage: StorageRegistry) {
    constructor(files: File, migrateProfiles: () -> Map<String, String> = { emptyMap() }) :
        this(StorageRegistry(files, migrateProfiles))

    fun <T> transaction(defaults: List<SessionProfile> = emptyList(), block: Transaction.() -> T): T =
        storage.transaction { Transaction(this, defaults).apply { migrate() }.block() }

    inner class Transaction internal constructor(private val persistence: StorageRegistry.Transaction,
        private val defaults: List<SessionProfile>) {
        private val state get() = persistence.state
        private val records get() = state.optJSONObject("environments") ?: JSONObject().also { state.put("environments", it) }

        fun all(): List<Environment> = records.keys().asSequence().map { Environment.fromJson(records.getJSONObject(it)) }.toList()
        fun find(id: EnvironmentId): Environment? = records.optJSONObject(id.value)?.let(Environment::fromJson)
        fun requireEnvironment(id: EnvironmentId): Environment = requireNotNull(find(id)) { "Environment has been deleted or is unavailable" }
        fun forStorage(key: StorageRegistry.Key): Environment = all().singleOrNull { it.storageKey == persistence.canonicalKey(key) }
            ?: error("Environment not found")

        /** Idempotent metadata-only migration. Exact backend selector includes Rootless distribution. */
        internal fun migrate() {
            val before = state.toString()
            // Also handles adoption performed by older code, without changing environment identity.
            all().forEach { environment ->
                val key = persistence.canonicalKey(environment.storageKey)
                if (persistence.isDeleted(key)) {
                    records.remove(environment.id.value)
                    tombstone(environment.id)
                } else if (key != environment.storageKey) put(environment.copy(storage = EnvironmentStorage(key.storageId)))
            }
            val profiles = persistence.profiles(defaults)
            val byStorage = all().associateBy { it.storageKey }.toMutableMap()
            val names = byStorage.values.map { it.name }.toMutableList()
            (persistence.keys() + profiles.filter { it.environmentId == null }.map { StorageRegistry.Key(it.runtime, it.storageId) })
                .map(persistence::canonicalKey).distinct().filterNot(persistence::isDeleted).forEach { key ->
                    if (key !in byStorage) {
                        val originalKey = if (key.storageId == persistence.migratedLegacyId(key.runtime))
                            StorageRegistry.Key(key.runtime, "legacy") else key
                        val linked = profiles.firstOrNull { it.runtime == key.runtime && it.storageId == key.storageId }
                        val id = Environment.migrationId(originalKey)
                        check(!isDeleted(id)) { "Environment has been deleted" }
                        val name = persistence.name(key) ?: defaultEnvironmentName(linked?.name ?: key.runtime.wireName, names)
                        val environment = Environment.create(key.runtime, name, key.storageId, id,
                            vmInstanceId = if (key.runtime == SessionRuntime.AVF && originalKey.storageId == "legacy") "default" else key.storageId,
                            internalStorageAccess = true)
                        put(environment)
                        byStorage[key] = environment
                        names += name
                    }
                }
            val saved = state.getJSONObject("profiles")
            profiles.forEach { profile ->
                val environment = profile.environmentId?.let(::requireEnvironment)
                    ?: requireNotNull(byStorage[persistence.canonicalKey(StorageRegistry.Key(profile.runtime, profile.storageId))])
                if (saved.has(profile.id) && saved.getString(profile.id).isNotEmpty())
                    saved.put(profile.id, profile.resolve(environment).toJson().toString())
            }
            state.put("storages", JSONArray((persistence.keys() + byStorage.keys).map { it.json() }))
            state.put("environmentModelVersion", 1)
            if (state.toString() != before) persistence.persist()
        }

        private fun environmentFor(profile: SessionProfile): Environment = profile.environmentId?.let(::requireEnvironment)
            ?: forStorage(StorageRegistry.Key(profile.runtime, profile.storageId))

        fun profiles(): List<SessionProfile> = persistence.profiles(defaults).map { profile ->
            val environment = environmentFor(profile)
            profile.copy(environmentId = environment.id, runtime = environment.sessionRuntime, storageId = environment.storage.id)
        }
        fun resolve(profile: SessionProfile): SessionLaunchSpec = profile.resolve(environmentFor(profile))

        /** v1 requests/checkpoints adopt metadata only; v2 requests retain their immutable snapshot.
         * Validation checks identity, never re-resolves through the now-editable profile.
         */
        fun resolveLaunch(spec: SessionLaunchSpec): SessionLaunchSpec {
            val environment = if (spec.environmentId != null) requireEnvironment(spec.environmentId) else {
                val key = persistence.canonicalKey(StorageRegistry.Key(spec.runtime, spec.storageId))
                persistence.validateConnection(key)
                all().singleOrNull { it.storageKey == key } ?: Environment.create(key.runtime,
                    defaultEnvironmentName(spec.profileName, all().map { it.name }), key.storageId,
                    vmInstanceId = if (key.storageId == "legacy" || key.storageId == persistence.migratedLegacyId(key.runtime)) "default" else key.storageId).also {
                    put(it)
                    state.put("storages", JSONArray((persistence.keys() + key).map { storage -> storage.json() }))
                    persistence.persist()
                }
            }
            val snapshot = spec.executionEnvironment ?: return SessionLaunchSpec(spec.toProfile(), environment, spec.tabTitle)
            check(snapshot.id == environment.id && snapshot.runtime == environment.runtime && snapshot.distribution == environment.distribution &&
                persistence.canonicalKey(snapshot.storageKey) == environment.storageKey && snapshot.runtimeMetadata == environment.runtimeMetadata) {
                "Environment snapshot is incompatible"
            }
            // Mutable host permissions are never authorized by a caller's launch snapshot.
            return if (snapshot.storage == environment.storage && snapshot.internalStorageAccess == environment.internalStorageAccess) spec
                else SessionLaunchSpec(spec.toProfile(), snapshot.copy(storage = environment.storage,
                    internalStorageAccess = environment.internalStorageAccess), spec.tabTitle)
        }

        /** Fresh environment + profile commit together; no rootfs, home or disk files are copied. */
        fun save(profile: SessionProfile, newEnvironment: Environment? = null): SessionProfile {
            val environment = when {
                newEnvironment != null -> {
                    check(find(newEnvironment.id) == null && !isDeleted(newEnvironment.id)) { "Environment ID already exists or was deleted" }
                    check(newEnvironment.storage.id != "legacy" && all().none { it.storageKey == newEnvironment.storageKey } &&
                        newEnvironment.storageKey !in persistence.keys()) { "New environment requires independent storage" }
                    persistence.validateConnection(newEnvironment.storageKey)
                    if (newEnvironment.runtime == EnvironmentRuntime.AVF)
                        check(newEnvironment.runtimeMetadata.vmInstanceId == newEnvironment.storage.id) { "New AVF environment requires an independent VM identity" }
                    newEnvironment.copy(name = StorageRegistry.uniqueName(newEnvironment.name, all().map { it.name }))
                }
                profile.environmentId != null -> requireEnvironment(profile.environmentId)
                else -> {
                    // Transitional API for old clients. New UI supplies an explicit Environment.
                    val key = persistence.canonicalKey(StorageRegistry.Key(profile.runtime, profile.storageId))
                    persistence.validateConnection(key)
                    all().singleOrNull { it.storageKey == key } ?: Environment.create(key.runtime,
                        defaultEnvironmentName(profile.name, all().map { it.name }), key.storageId,
                        vmInstanceId = if (key.storageId == "legacy") "default" else key.storageId)
                }
            }
            check(!persistence.isDeleted(environment.storageKey)) { "Environment storage has been deleted" }
            val resolved = profile.copy(environmentId = environment.id, runtime = environment.sessionRuntime, storageId = environment.storage.id)
            val encoded = resolved.resolve(environment).toJson().toString() // validate before mutating either record
            val keys = persistence.keys() + environment.storageKey
            put(environment)
            state.getJSONObject("profiles").put(profile.id, encoded)
            state.put("storages", JSONArray(keys.map { it.json() }))
            persistence.persist()
            return resolved
        }

        fun duplicate(profile: SessionProfile, shareEnvironment: Boolean = false): SessionProfile {
            val environment = environmentFor(profile)
            return profile.copy(id = UUID.randomUUID().toString(), name = "${profile.name} copy",
                runtime = environment.sessionRuntime, storageId = if (shareEnvironment) environment.storage.id else UUID.randomUUID().toString(),
                environmentId = if (shareEnvironment) environment.id else null)
        }

        fun reserveRestore(operationId: String, environment: Environment) {
            check(find(environment.id) == null && !isDeleted(environment.id))
            persistence.reserveRestore(operationId, environment)
        }

        /** Caller supplies an already verified private staging tree, never a backup-selected path.
         * Reservation survives a crash between filesystem rename and the atomic metadata commit.
         */
        fun activateRestore(operationId: String, importedProfiles: List<SessionProfile>, activate: (Environment) -> Unit): Environment {
            val reserved = requireNotNull(persistence.pendingRestores()[operationId]) { "Restore reservation is missing" }
            check(find(reserved.id) == null && !isDeleted(reserved.id))
            val environment = reserved.copy(name = StorageRegistry.uniqueName(reserved.name, all().map { it.name }))
            val saved = state.getJSONObject("profiles")
            val encoded = importedProfiles.associate { profile ->
                check(!profile.builtIn && !saved.has(profile.id) && defaults.none { it.id == profile.id }) { "Restore cannot replace profiles" }
                check(profile.environmentId == environment.id && profile.storageId == environment.storage.id && profile.runtime == environment.sessionRuntime)
                profile.id to profile.resolve(environment).toJson().toString()
            }
            check(encoded.size == importedProfiles.size)
            val keys = persistence.keys() + environment.storageKey
            activate(environment)
            put(environment)
            encoded.forEach { (id, profile) -> saved.put(id, profile) }
            state.put("storages", JSONArray(keys.map { it.json() }))
            val receipts = state.optJSONObject("environmentRestoreReceipts") ?: JSONObject().also { state.put("environmentRestoreReceipts", it) }
            receipts.put(operationId, environment.id.value)
            persistence.finishRestore(operationId)
            persistence.persist()
            return environment
        }

        fun completedRestore(operationId: String): Environment? = state.optJSONObject("environmentRestoreReceipts")
            ?.optString(operationId)?.takeIf(String::isNotEmpty)?.let { find(EnvironmentId(it)) }

        fun deleteProfile(id: String, builtIn: Boolean) = persistence.deleteProfile(id, builtIn)
        fun rename(id: EnvironmentId, name: String) {
            val environment = requireEnvironment(id)
            put(environment.copy(name = name.trim()))
            persistence.persist()
        }

        fun setInternalStorageAccess(id: EnvironmentId, enabled: Boolean) {
            val environment = requireEnvironment(id)
            require(environment.runtime != EnvironmentRuntime.NATIVE) { "Native 내부저장소 접근은 Android 공통 권한에서 변경하세요." }
            put(environment.copy(internalStorageAccess = enabled))
            persistence.persist()
        }

        fun requireDeletable(id: EnvironmentId, activeEnvironmentIds: Set<EnvironmentId> = emptySet(), runtimeKnown: Boolean = true): Environment {
            check(runtimeKnown) { "Cannot verify environment runtime state" }
            check(id !in activeEnvironmentIds) { "Environment has active sessions" }
            val environment = requireEnvironment(id)
            check(all().none { it.id != id && it.storageKey == environment.storageKey }) { "Storage is owned by another environment" }
            persistence.requireOrphan(environment.storageKey, defaults)
            return environment
        }
        /** The service has excluded creation and proved the actual runtime owner idle. */
        fun removeSoftware(id: EnvironmentId, removeHome: Boolean = false,
            activeEnvironmentIds: Set<EnvironmentId> = emptySet(), runtimeKnown: Boolean = true,
            beforeRemove: (Environment) -> Unit = {}) {
            check(runtimeKnown) { "Cannot verify environment runtime state" }
            check(id !in activeEnvironmentIds) { "Environment has active sessions" }
            val environment = requireEnvironment(id)
            check(environment.runtime != EnvironmentRuntime.NATIVE) { "Native has no removable distribution image" }
            check(all().none { it.id != id && it.storageKey == environment.storageKey }) { "Storage is owned by another environment" }
            persistence.validateConnection(environment.storageKey)
            beforeRemove(environment) // Must verify/stop the actual owner before touching files.
            persistence.removeSoftware(environment.storageKey, removeHome)
            // Environment identity, storage handle and connected profiles remain available for reinstall.
        }

        /** The service has excluded creation and proved the actual runtime owner idle. */
        fun delete(id: EnvironmentId, beforeRemove: (Environment) -> Unit = {}) {
            val environment = requireDeletable(id)
            // A workspace link can depend on rootfs/home. Fail before either is removed.
            persistence.validateLegacyWorkspaceDeletion(environment.storageKey)
            beforeRemove(environment)
            if (environment.runtime != EnvironmentRuntime.NATIVE) {
                persistence.removeSoftware(environment.storageKey, removeHome = environment.runtime == EnvironmentRuntime.ROOTLESS)
            }
            persistence.delete(environment.storageKey) {
                records.remove(id.value)
                tombstone(id)
            }
        }

        fun migrateLegacy(runtime: SessionRuntime): StorageRegistry.Key {
            val key = persistence.migrateLegacy(runtime, defaults)
            migrate()
            return key
        }
        private fun put(environment: Environment) { records.put(environment.id.value, environment.toJson()) }
        private fun isDeleted(id: EnvironmentId): Boolean = state.optJSONArray("deletedEnvironments")?.let { array ->
            (0 until array.length()).any { array.getString(it) == id.value }
        } ?: false
        private fun tombstone(id: EnvironmentId) {
            if (!isDeleted(id)) (state.optJSONArray("deletedEnvironments") ?: JSONArray().also { state.put("deletedEnvironments", it) }).put(id.value)
        }
    }
    companion object {
        fun defaultEnvironmentName(profileName: String, existingNames: Collection<String> = emptyList()): String =
            defaultName("env-${profileName.trim()}", existingNames)

        fun defaultName(profileName: String, existingNames: Collection<String> = emptyList()): String {
            val name = profileName.trim().filterNot { it.isISOControl() }.take(StorageRegistry.MAX_NAME_LENGTH)
                .let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }.ifBlank { "Environment" }
            return StorageRegistry.uniqueName(name, existingNames)
        }
    }
}
