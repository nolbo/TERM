package dev.terminal.runtime.forwarding

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Host-side source of truth for guest ports reported by droid-port-agent. */
class PortForwardRegistry {
    private val mutablePorts = MutableStateFlow<List<ForwardedPort>>(emptyList())
    val ports: StateFlow<List<ForwardedPort>> = mutablePorts.asStateFlow()

    @Synchronized
    fun reportListening(guestPort: Int, label: String? = null): Result<ForwardedPort> = runCatching {
        require(guestPort in 1..65_535) { "Invalid guest port" }
        val safeLabel = label?.takeIf { it.length <= MAX_LABEL_LENGTH }
        val entry = ForwardedPort(guestPort, hostPort = guestPort, label = safeLabel)
        mutablePorts.value = (mutablePorts.value.filterNot { it.guestPort == guestPort } + entry)
            .sortedBy { it.guestPort }
        entry
    }

    @Synchronized
    fun remove(guestPort: Int) {
        mutablePorts.value = mutablePorts.value.filterNot { it.guestPort == guestPort }
    }

    @Synchronized
    fun clear() { mutablePorts.value = emptyList() }

    data class ForwardedPort(
        val guestPort: Int,
        /** Initial policy preserves the port; a bind conflict must be reported, never remapped silently. */
        val hostPort: Int,
        val label: String?,
        val hostAddress: String = "127.0.0.1",
    )

    private companion object { const val MAX_LABEL_LENGTH = 128 }
}
