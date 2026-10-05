package dev.terminal.app.ui

import org.json.JSONArray

internal sealed interface ForwardedPortsState {
    data object Connecting : ForwardedPortsState
    data object Unavailable : ForwardedPortsState
    data class Ready(val ports: List<ForwardedPortConnection>) : ForwardedPortsState
}

internal data class ForwardedPortConnection(
    val hostAddress: String,
    val hostPort: Int,
    val guestPort: Int,
    val label: String? = null,
) {
    val androidAddress: String
        get() = if (':' in hostAddress) "[$hostAddress]:$hostPort" else "$hostAddress:$hostPort"

    companion object {
        fun parse(encoded: String): List<ForwardedPortConnection> = JSONArray(encoded).let { ports ->
            List(ports.length()) { index ->
                val port = ports.getJSONObject(index)
                ForwardedPortConnection(
                    hostAddress = port.getString("hostAddress"),
                    hostPort = port.getInt("hostPort"),
                    guestPort = port.getInt("guestPort"),
                    label = if (port.isNull("label")) null else port.getString("label").takeIf { it.isNotBlank() },
                ).also {
                    require(it.hostAddress.isNotBlank() && it.hostPort in 1..65_535 && it.guestPort in 1..65_535)
                }
            }
        }
    }
}
