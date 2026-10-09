package dev.terminal.runtime.forwarding

import dev.terminal.protocol.PortPayloadCodec
import org.json.JSONObject
import java.util.UUID

enum class ServiceObservation { REGISTERED_UNVERIFIED, LISTENING, UPDATING, DISCONNECTED, CLOSED }
enum class ForwardingState { NONE, ACTIVE, CONFLICT, FAILED, UNAVAILABLE }
enum class ServiceSource { MANUAL, AVF_AGENT, LEGACY_AVF_AGENT }
enum class ApplicationProtocol { UNKNOWN, HTTP, HTTPS }

/** Observation, logical association, forwarding and application health are separate facts. */
data class LocalService(
    val id: String,
    val environmentId: String?,
    val runtime: String,
    val name: String?,
    val port: Int,
    val sourceAddress: String?,
    val source: ServiceSource,
    val observation: ServiceObservation,
    val forwarding: ForwardingState = ForwardingState.NONE,
    val hostPort: Int? = null,
    val applicationProtocol: ApplicationProtocol = ApplicationProtocol.UNKNOWN,
    val error: String? = null,
    val attemptedHostPort: Int? = null,
    val sessionId: String? = null,
) {
    val androidAddress: String? get() = hostPort?.takeIf { source == ServiceSource.MANUAL || forwarding == ForwardingState.ACTIVE }
        ?.let { "127.0.0.1:$it" }
    val browserUrl: String? get() = androidAddress?.let { address -> when (applicationProtocol) {
        ApplicationProtocol.HTTP -> "http://$address/"
        ApplicationProtocol.HTTPS -> "https://$address/"
        ApplicationProtocol.UNKNOWN -> null
    } }
    fun toJson() = JSONObject().put("id", id).put("environmentId", environmentId ?: JSONObject.NULL)
        .put("runtime", runtime).put("name", name ?: JSONObject.NULL).put("protocol", "TCP")
        .put("port", port).put("sourceAddress", sourceAddress ?: JSONObject.NULL).put("source", source.name)
        .put("observation", observation.name).put("forwarding", forwarding.name)
        .put("hostPort", hostPort ?: JSONObject.NULL).put("applicationProtocol", applicationProtocol.name)
        .put("error", error ?: JSONObject.NULL).put("attemptedHostPort", attemptedHostPort ?: JSONObject.NULL)
        .put("sessionId", sessionId ?: JSONObject.NULL)

    companion object {
        fun avfId(environment: String, address: String?, port: Int) = "avf:" + UUID.nameUUIDFromBytes(
            "$environment|TCP|${address ?: "unknown"}|$port".toByteArray()).toString()
        fun fromJson(row: JSONObject) = LocalService(row.getString("id"), row.nullableString("environmentId"),
            row.getString("runtime"), row.nullableString("name"), row.getInt("port"), row.nullableString("sourceAddress"),
            ServiceSource.valueOf(row.getString("source")), ServiceObservation.valueOf(row.getString("observation")),
            ForwardingState.valueOf(row.getString("forwarding")), row.nullableInt("hostPort"),
            ApplicationProtocol.valueOf(row.getString("applicationProtocol")), row.nullableString("error"),
            row.nullableInt("attemptedHostPort"), row.nullableString("sessionId")).also {
                require(it.port in 1..65_535 && (it.hostPort == null || it.hostPort in 1..65_535))
                require(it.sourceAddress == null || PortPayloadCodec.validAddress(it.sourceAddress))
                validateName(it.name)
            }
        fun validateName(name: String?) {
            require(name == null || name.isNotBlank() && name.length <= 128 && name.none { it.isISOControl() }) { "INVALID_NAME" }
        }
    }
}

internal fun JSONObject.nullableString(key: String) = if (isNull(key)) null else optString(key).takeIf(String::isNotEmpty)
internal fun JSONObject.nullableInt(key: String) = if (isNull(key) || !has(key)) null else getInt(key)
