package dev.terminal.runtime.session

import org.json.JSONObject
import java.util.UUID

/** Domain identity, independent of a profile, session, display name or filesystem path. */
@JvmInline
value class EnvironmentId(val value: String) {
    init {
        SessionStorage.validate(value)
        require(value != "legacy") { "Environment ID must be a UUID" }
    }
    override fun toString(): String = value
    companion object { fun create() = EnvironmentId(UUID.randomUUID().toString()) }
}

enum class EnvironmentRuntime { NATIVE, ROOTLESS, AVF }
enum class EnvironmentDistribution { DEBIAN, UBUNTU, ARCH }

/** Persistence handle only. It never supplies an arbitrary host path. */
data class EnvironmentStorage(val id: String) {
    init { SessionStorage.validate(id) }
}

/** Fixed layout/VM identities; mutable runtime state belongs to the service owner. */
data class EnvironmentRuntimeMetadata(val rootfsLayout: String? = null, val vmInstanceId: String? = null) {
    fun toJson() = JSONObject().apply {
        rootfsLayout?.let { put("rootfsLayout", it) }
        vmInstanceId?.let { put("vmInstanceId", it) }
    }
}

/** Where execution happens. All fields are immutable, including runtime-specific metadata. */
data class Environment(
    val id: EnvironmentId,
    val name: String,
    val runtime: EnvironmentRuntime,
    val distribution: EnvironmentDistribution?,
    val storage: EnvironmentStorage,
    val runtimeMetadata: EnvironmentRuntimeMetadata,
    val createdAt: Long,
) {
    init {
        require(name.isNotBlank() && name.length <= StorageRegistry.MAX_NAME_LENGTH && name.none { it.isISOControl() }) { "Invalid environment name" }
        require(createdAt >= 0)
        when (runtime) {
            EnvironmentRuntime.NATIVE -> require(distribution == null && runtimeMetadata == EnvironmentRuntimeMetadata())
            EnvironmentRuntime.ROOTLESS -> require(distribution != null && runtimeMetadata == EnvironmentRuntimeMetadata(rootfsLayout = "rootfs"))
            EnvironmentRuntime.AVF -> require(distribution == EnvironmentDistribution.DEBIAN && runtimeMetadata.rootfsLayout == null &&
                runtimeMetadata.vmInstanceId in setOf("default", storage.id)) { "Invalid AVF environment metadata" }
        }
    }

    /** Transitional backend selector: Rootless distribution was historically encoded in runtime. */
    val sessionRuntime: SessionRuntime get() = when (runtime) {
        EnvironmentRuntime.NATIVE -> SessionRuntime.NATIVE
        EnvironmentRuntime.AVF -> SessionRuntime.AVF
        EnvironmentRuntime.ROOTLESS -> when (requireNotNull(distribution)) {
            EnvironmentDistribution.DEBIAN -> SessionRuntime.ROOTLESS
            EnvironmentDistribution.UBUNTU -> SessionRuntime.ROOTLESS_UBUNTU
            EnvironmentDistribution.ARCH -> SessionRuntime.ROOTLESS_ARCH
        }
    }
    val storageKey get() = StorageRegistry.Key(sessionRuntime, storage.id)

    fun toJson() = JSONObject().put("id", id.value).put("name", name).put("runtime", runtime.name)
        .put("distribution", distribution?.name ?: JSONObject.NULL).put("storageId", storage.id)
        .put("runtimeMetadata", runtimeMetadata.toJson()).put("createdAt", createdAt)

    companion object {
        fun fromJson(json: JSONObject): Environment {
            val metadata = json.getJSONObject("runtimeMetadata")
            return Environment(EnvironmentId(json.getString("id")), json.getString("name"),
                EnvironmentRuntime.valueOf(json.getString("runtime")),
                json.optString("distribution").takeIf { !json.isNull("distribution") }?.let(EnvironmentDistribution::valueOf),
                EnvironmentStorage(json.getString("storageId")),
                EnvironmentRuntimeMetadata(metadata.optString("rootfsLayout").takeIf(String::isNotEmpty),
                    metadata.optString("vmInstanceId").takeIf(String::isNotEmpty)), json.getLong("createdAt"))
        }

        fun create(runtime: SessionRuntime, name: String, storageId: String = UUID.randomUUID().toString(),
            id: EnvironmentId = EnvironmentId.create(), vmInstanceId: String = storageId,
            createdAt: Long = System.currentTimeMillis()): Environment = Environment(id, name,
            when (runtime) {
                SessionRuntime.NATIVE -> EnvironmentRuntime.NATIVE
                SessionRuntime.AVF -> EnvironmentRuntime.AVF
                else -> EnvironmentRuntime.ROOTLESS
            }, when (runtime) {
                SessionRuntime.NATIVE -> null
                SessionRuntime.ROOTLESS_UBUNTU -> EnvironmentDistribution.UBUNTU
                SessionRuntime.ROOTLESS_ARCH -> EnvironmentDistribution.ARCH
                else -> EnvironmentDistribution.DEBIAN
            }, EnvironmentStorage(storageId), when (runtime) {
                SessionRuntime.NATIVE -> EnvironmentRuntimeMetadata()
                SessionRuntime.AVF -> EnvironmentRuntimeMetadata(vmInstanceId = vmInstanceId)
                else -> EnvironmentRuntimeMetadata(rootfsLayout = "rootfs")
            }, createdAt)

        internal fun migrationId(key: StorageRegistry.Key) = EnvironmentId(UUID.nameUUIDFromBytes(
            "terminal:environment:${key.runtime.wireName}:${key.storageId}".toByteArray(Charsets.UTF_8)).toString())
    }
}
