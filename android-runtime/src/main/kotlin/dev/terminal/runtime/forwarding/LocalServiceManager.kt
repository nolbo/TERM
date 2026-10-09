package dev.terminal.runtime.forwarding

import dev.terminal.protocol.PortEvent
import dev.terminal.runtime.session.Environment
import dev.terminal.runtime.session.EnvironmentRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.net.BindException
import java.util.UUID

/** Authoritative :vmhost state. UI detachment never closes these resources. */
class LocalServiceManager(
    val registry: PortForwardRegistry = PortForwardRegistry(),
    private val read: () -> String? = { null },
    private val write: (String) -> Unit = {},
) : AutoCloseable {
    private data class Preference(val port: Int, val protocol: ApplicationProtocol)
    private val preferences = mutableMapOf<String, Preference>()
    private val drivers = mutableMapOf<String, PortForwardDriver>()
    private val snapshots = mutableMapOf<String, MutableSet<String>>()
    private val extended = mutableSetOf<String>()
    private val links = mutableMapOf<String, String>()
    var storageError: String? = null
        private set

    init {
        runCatching {
            val encoded = read() ?: "{}"
            require(encoded.length <= 262_144)
            val root = JSONObject(encoded)
            require(root.optInt("version", 1) == 1)
            val manual = root.optJSONArray("manual") ?: JSONArray()
            require(manual.length() <= PortForwardRegistry.MAX_MANUAL)
            for (i in 0 until manual.length()) runCatching {
                val service = LocalService.fromJson(manual.getJSONObject(i))
                requireManualId(service.id)
                require(service.source == ServiceSource.MANUAL && service.runtime in setOf("NATIVE", "ROOTLESS", "SHARED_ANDROID"))
                service.environmentId?.let { dev.terminal.runtime.session.EnvironmentId(it) }
                require(service.name != null)
                registry.put(service.copy(observation = ServiceObservation.REGISTERED_UNVERIFIED, forwarding = ForwardingState.NONE,
                    sourceAddress = "127.0.0.1", hostPort = service.port, error = null, attemptedHostPort = null, sessionId = null))
            }.onFailure { storageError = "STORAGE_CORRUPT" }
            val mappings = root.optJSONObject("mappings") ?: JSONObject()
            require(mappings.length() <= PortForwardRegistry.MAX_SERVICES)
            mappings.keys().forEach { id -> runCatching {
                require(id.startsWith("avf:")); UUID.fromString(id.removePrefix("avf:"))
                val row = mappings.getJSONObject(id)
                val port = strictPort(row, "port", 1024)
                preferences[id] = Preference(port, ApplicationProtocol.valueOf(row.getString("protocol")))
            }.onFailure { storageError = "STORAGE_CORRUPT" } }
        }.onFailure { storageError = "STORAGE_CORRUPT" }
    }

    @Synchronized fun saveManual(encoded: String, environments: List<Environment>): String = result {
        require(encoded.length <= 1024) { "INVALID_INPUT" }
        val row = JSONObject(encoded)
        require(row.keys().asSequence().all { it in setOf("id", "name", "port", "environmentId", "applicationProtocol") }) { "INVALID_INPUT" }
        val id = row.nullableString("id") ?: "manual:${UUID.randomUUID()}"
        requireManualId(id)
        val existing = registry.get(id)
        check(existing == null || existing.source == ServiceSource.MANUAL) { "INVALID_INPUT" }
        val name = row.getString("name").trim()
        LocalService.validateName(name)
        val port = strictPort(row, "port")
        val environmentId = row.nullableString("environmentId")
        val environment = environmentId?.let { idValue -> environments.singleOrNull { it.id.value == idValue }
            ?: error("ENVIRONMENT_UNAVAILABLE") }
        require(environment?.runtime != EnvironmentRuntime.AVF) { "INVALID_ENVIRONMENT" }
        val protocol = ApplicationProtocol.valueOf(row.getString("applicationProtocol"))
        val service = LocalService(id, environmentId, environment?.runtime?.name ?: "SHARED_ANDROID", name, port,
            "127.0.0.1", ServiceSource.MANUAL, ServiceObservation.REGISTERED_UNVERIFIED, hostPort = port, applicationProtocol = protocol)
        val manual = registry.services.value.filter { it.source == ServiceSource.MANUAL && it.id != id } + service
        check(manual.size <= PortForwardRegistry.MAX_MANUAL && (existing != null || registry.services.value.size < PortForwardRegistry.MAX_SERVICES)) { "SERVICE_LIMIT" }
        persist(manual, preferences)
        registry.put(service)
    }

    @Synchronized fun deleteManual(id: String): String = result {
        requireManualId(id)
        check(registry.get(id)?.source == ServiceSource.MANUAL) { "SERVICE_UNAVAILABLE" }
        persist(registry.services.value.filter { it.source == ServiceSource.MANUAL && it.id != id }, preferences)
        registry.remove(id)
    }

    @Synchronized fun attach(environmentId: String, driver: PortForwardDriver) {
        drivers.remove(environmentId)?.close()
        drivers[environmentId] = driver
    }
    @Synchronized fun detach(environmentId: String) {
        disconnected(environmentId, true)
        drivers.remove(environmentId)?.close()
    }
    @Synchronized fun link(environmentId: String, state: String) { links[environmentId] = state }
    @Synchronized fun linkSnapshot(): JSONObject = JSONObject(links.toMap())
    @Synchronized fun snapshot(environments: List<Environment>): String = JSONObject()
        .put("services", JSONArray(registry.services.value.map { it.toJson() }))
        .put("environments", JSONArray(environments.map { it.toJson() }))
        .put("links", linkSnapshot()).put("storageError", storageError ?: JSONObject.NULL).toString()

    @Synchronized fun event(environmentId: String, event: PortEvent) {
        when (event) {
            PortEvent.SnapshotBegin -> {
                extended.add(environmentId)
                snapshots[environmentId] = mutableSetOf()
                registry.services.value.filter { it.environmentId == environmentId && it.source == ServiceSource.LEGACY_AVF_AGENT }
                    .forEach { registry.remove(it.id) }
                registry.updateEnvironment(environmentId) { service ->
                    drivers[environmentId]?.stop(service.id)
                    service.copy(observation = ServiceObservation.UPDATING, forwarding = ForwardingState.NONE, error = null)
                }
            }
            PortEvent.SnapshotEnd -> snapshots.remove(environmentId)?.let { seen ->
                registry.updateEnvironment(environmentId) { if (it.id in seen) it else closed(it) }
            }
            is PortEvent.Listening -> if (environmentId !in extended) listening(environmentId, null, event.port, event.label)
            is PortEvent.Closed -> if (environmentId !in extended) closeEndpoint(environmentId, null, event.port)
            is PortEvent.EndpointListening -> if (environmentId in extended) listening(environmentId, event.address, event.port, event.label)
            is PortEvent.EndpointClosed -> if (environmentId in extended) closeEndpoint(environmentId, event.address, event.port)
        }
    }

    private fun listening(environment: String, address: String?, port: Int, label: String?) {
        if (port !in 1..65_535) return
        val id = LocalService.avfId(environment, address, port)
        val previous = registry.get(id)
        if (previous == null) {
            // Bound retained history and each untrusted VM's contribution independently.
            val owned = registry.services.value.filter { it.environmentId == environment && it.source != ServiceSource.MANUAL }
            if (owned.size >= PortForwardRegistry.MAX_PER_ENVIRONMENT || registry.services.value.size >= PortForwardRegistry.MAX_SERVICES) {
                owned.firstOrNull { it.observation in setOf(ServiceObservation.CLOSED, ServiceObservation.DISCONNECTED) }
                    ?.let { registry.remove(it.id) } ?: return
            }
        }
        val pref = preferences[id]
        val name = label?.takeIf { it.isNotBlank() && it.length <= 128 && it.none(Char::isISOControl) }
        var service = LocalService(id, environment, "AVF", name, port, address,
            if (address == null) ServiceSource.LEGACY_AVF_AGENT else ServiceSource.AVF_AGENT,
            ServiceObservation.LISTENING, hostPort = pref?.port ?: port,
            applicationProtocol = pref?.protocol ?: ApplicationProtocol.UNKNOWN)
        snapshots[environment]?.add(id)
        if (previous?.observation == ServiceObservation.LISTENING && previous.forwarding == ForwardingState.ACTIVE) {
            registry.put(previous.copy(name = name)); return
        }
        service = if (address == null) service.copy(forwarding = ForwardingState.UNAVAILABLE, error = "PROXY_UNAVAILABLE")
            else bind(service, requireNotNull(service.hostPort))
        registry.put(service)
    }

    private fun closeEndpoint(environment: String, address: String?, port: Int) {
        val id = LocalService.avfId(environment, address, port)
        snapshots[environment]?.remove(id)
        registry.get(id)?.let { registry.put(closed(it)) }
    }
    private fun closed(service: LocalService): LocalService {
        service.environmentId?.let { drivers[it]?.stop(service.id) }
        return service.copy(observation = ServiceObservation.CLOSED, forwarding = ForwardingState.NONE, error = null, attemptedHostPort = null)
    }

    /** Failed changes keep the active old hostPort and report the attempted port separately. */
    @Synchronized fun updateForwarding(id: String, hostPort: Int, protocol: String): String = result {
        val service = registry.get(id) ?: error("SERVICE_UNAVAILABLE")
        require(service.source == ServiceSource.AVF_AGENT && service.observation == ServiceObservation.LISTENING) { "SERVICE_UNAVAILABLE" }
        require(hostPort in 1024..65_535) { "INVALID_PORT" }
        val appProtocol = ApplicationProtocol.valueOf(protocol)
        check(id in preferences || preferences.size < PortForwardRegistry.MAX_SERVICES) { "SERVICE_LIMIT" }
        val next = preferences + (id to Preference(hostPort, appProtocol))
        val changed = bind(service, hostPort) { persist(registry.services.value.filter { it.source == ServiceSource.MANUAL }, next) }
        if (changed.error != null) { registry.put(changed); error(requireNotNull(changed.error)) }
        preferences.clear(); preferences.putAll(next)
        registry.put(changed.copy(applicationProtocol = appProtocol))
    }

    private fun bind(service: LocalService, hostPort: Int, commit: () -> Unit = {}): LocalService {
        val result = drivers[service.environmentId]?.replace(service.id, service.port, requireNotNull(service.sourceAddress), hostPort, commit)
            ?: Result.failure(IllegalStateException("DISCONNECTED"))
        return if (result.isSuccess) service.copy(hostPort = hostPort, forwarding = ForwardingState.ACTIVE, error = null, attemptedHostPort = null)
        else {
            val failure = result.exceptionOrNull()
            val conflict = failure is BindException && (failure.message.orEmpty().contains("in use", ignoreCase = true) ||
                failure.message.orEmpty().contains("EADDRINUSE"))
            service.copy(forwarding = if (service.forwarding == ForwardingState.ACTIVE) ForwardingState.ACTIVE
                else if (conflict) ForwardingState.CONFLICT else ForwardingState.FAILED,
                error = if (conflict) "PORT_CONFLICT" else if (result.exceptionOrNull()?.message == "STORAGE_FAILED") "STORAGE_FAILED"
                    else if (result.exceptionOrNull()?.message == "SERVICE_LIMIT") "SERVICE_LIMIT"
                    else "FORWARD_FAILED", attemptedHostPort = hostPort)
        }
    }

    @Synchronized fun forwardingFailure(environment: String, id: String, hostPort: Int, listenerFailed: Boolean) {
        registry.get(id)?.takeIf { it.environmentId == environment && it.hostPort == hostPort && it.forwarding == ForwardingState.ACTIVE }?.let {
            registry.put(it.copy(forwarding = if (listenerFailed) ForwardingState.FAILED else it.forwarding,
                error = if (listenerFailed) "FORWARD_FAILED" else "GUEST_CONNECT_FAILED"))
        }
    }

    @Synchronized fun disconnected(environment: String, stopped: Boolean = false) {
        drivers[environment]?.let { driver -> registry.services.value.filter { it.environmentId == environment }.forEach { driver.stop(it.id) } }
        snapshots.remove(environment); extended.remove(environment)
        links[environment] = if (stopped) "STOPPED" else "DISCONNECTED"
        registry.updateEnvironment(environment) { it.copy(
            observation = if (it.observation == ServiceObservation.CLOSED) it.observation else ServiceObservation.DISCONNECTED,
            forwarding = ForwardingState.NONE, error = null, attemptedHostPort = null) }
    }
    @Synchronized override fun close() {
        drivers.keys.toList().forEach { disconnected(it, true) }
        drivers.values.forEach { it.close() }; drivers.clear()
    }

    private fun persist(manual: List<LocalService>, mappings: Map<String, Preference>) {
        val value = JSONObject().put("version", 1).put("manual", JSONArray(manual.map { it.toJson() }))
            .put("mappings", JSONObject().apply { mappings.forEach { (id, pref) ->
                put(id, JSONObject().put("port", pref.port).put("protocol", pref.protocol.name))
            } }).toString()
        try { write(value) } catch (_: Exception) { error("STORAGE_FAILED") }
        storageError = null
    }
    private fun requireManualId(id: String) { require(id.startsWith("manual:")) { "INVALID_INPUT" }; UUID.fromString(id.removePrefix("manual:")) }
    private fun strictPort(row: JSONObject, key: String, minimum: Int = 1): Int {
        val raw = row.get(key)
        require(raw is Number && raw.toString().matches(Regex("[0-9]{1,5}"))) { "INVALID_PORT" }
        return raw.toString().toInt().also { require(it in minimum..65_535) { "INVALID_PORT" } }
    }
    private inline fun result(action: () -> Unit): String = try { action(); "" } catch (failure: Exception) {
        failure.message?.takeIf { it.matches(Regex("[A-Z_]{1,80}")) } ?: "INVALID_INPUT"
    }
}
