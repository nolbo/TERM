package dev.terminal.app

import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.terminal.app.ui.RuntimeInformationScreen
import org.json.JSONArray

class ForwardedPortsActivity : RuntimeInformationActivity() {
    private var portText by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionProfiles(this).hasAvfPrivileges()) { finish(); return }
        portText = getString(R.string.diagnostics_connecting)
        compose { RuntimeInformationScreen(
            getString(R.string.forwarded_ports_title), getString(R.string.forwarded_ports_description),
            { finish() }, portText, ::refresh,
        ) }
    }

    override fun onResume() {
        super.onResume()
        if (!SessionProfiles(this).hasAvfPrivileges()) finish()
    }

    override fun runtimeInformationChanged() = refresh()

    private fun refresh() {
        val service = runtime
        portText = if (service == null) getString(R.string.diagnostics_connecting) else runCatching {
            val ports = JSONArray(service.forwardedPortsJson)
            if (ports.length() == 0) getString(R.string.diagnostics_no_ports).trim()
            else (0 until ports.length()).joinToString("\n") { index ->
                val port = ports.getJSONObject(index)
                getString(R.string.diagnostics_port_item, port.getString("hostAddress"),
                    port.getInt("hostPort"), port.getInt("guestPort")).trim()
            }
        }.getOrElse { getString(R.string.runtime_information_unavailable) }
    }
}
