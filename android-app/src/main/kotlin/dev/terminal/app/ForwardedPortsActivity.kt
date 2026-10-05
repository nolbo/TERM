package dev.terminal.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.terminal.app.ui.ForwardedPortConnection
import dev.terminal.app.ui.ForwardedPortsScreen
import dev.terminal.app.ui.ForwardedPortsState

class ForwardedPortsActivity : RuntimeInformationActivity() {
    private var portsState by mutableStateOf<ForwardedPortsState>(ForwardedPortsState.Connecting)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SessionProfiles(this).hasAvfPrivileges()) { finish(); return }
        compose { ForwardedPortsScreen(portsState, { finish() }, ::refresh) { address ->
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText(getString(R.string.forwarded_ports_android_address), address))
        } }
    }

    override fun onResume() {
        super.onResume()
        if (!SessionProfiles(this).hasAvfPrivileges()) finish()
    }

    override fun runtimeInformationChanged() = refresh()

    private fun refresh() {
        val service = runtime
        portsState = if (service == null) ForwardedPortsState.Connecting else runCatching {
            ForwardedPortsState.Ready(ForwardedPortConnection.parse(service.forwardedPortsJson))
        }.getOrElse { ForwardedPortsState.Unavailable }
    }
}
