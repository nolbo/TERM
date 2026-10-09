package dev.terminal.runtime.forwarding

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Service-owned observations only. Socket ownership belongs to LocalServiceManager. */
class PortForwardRegistry {
    private val mutableServices = MutableStateFlow<List<LocalService>>(emptyList())
    val services = mutableServices.asStateFlow()

    @Synchronized fun get(id: String): LocalService? = mutableServices.value.firstOrNull { it.id == id }
    @Synchronized fun put(service: LocalService) {
        val previous = mutableServices.value.filterNot { it.id == service.id }
        check(previous.size < MAX_SERVICES) { "SERVICE_LIMIT" }
        mutableServices.value = (previous + service).sortedWith(compareBy({ it.runtime }, { it.environmentId }, { it.port }, { it.id }))
    }
    @Synchronized fun remove(id: String) { mutableServices.value = mutableServices.value.filterNot { it.id == id } }
    @Synchronized fun updateEnvironment(id: String, transform: (LocalService) -> LocalService) {
        mutableServices.value = mutableServices.value.map { if (it.environmentId == id && it.source != ServiceSource.MANUAL) transform(it) else it }
    }
    @Synchronized fun clear() { mutableServices.value = emptyList() }
    companion object { const val MAX_SERVICES = 512; const val MAX_PER_ENVIRONMENT = 128; const val MAX_MANUAL = 128 }
}
