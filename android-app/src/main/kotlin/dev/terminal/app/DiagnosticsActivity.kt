package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.terminal.app.ui.DiagnosticsScreen
import dev.terminal.app.ui.TerminalActivity
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import org.json.JSONArray

/** Read-only operational view; it helps distinguish image, AVF, guest and forwarding failures. */
class DiagnosticsActivity : TerminalActivity() {
    private var diagnosticText by mutableStateOf("")
    private var runtime: IRuntimeService? = null
    private var bound = false
    private var bindingRequested = false
    private var activityStarted = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (!activityStarted) {
                runCatching { unbindService(this) }
                bindingRequested = false
                return
            }
            runtime = IRuntimeService.Stub.asInterface(service)
            bound = true
            render()
        }
        override fun onServiceDisconnected(name: ComponentName) { runtime = null; bound = false; render() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        diagnosticText = getString(R.string.diagnostics_connecting)
        compose { DiagnosticsScreen({ finish() }, diagnosticText, ::render) }
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        bindingRequested = runCatching {
            bindService(Intent(this, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
    }

    override fun onStop() {
        activityStarted = false
        if (bindingRequested) runCatching { unbindService(connection) }
        bindingRequested = false
        bound = false
        runtime = null
        super.onStop()
    }

    private fun render() {
        val service = runtime
        if (service == null) {
            diagnosticText = getString(R.string.diagnostics_connecting)
            return
        }
        diagnosticText = buildString {
            appendLine(getString(R.string.diagnostics_runtime_state, service.state))
            appendLine(getString(R.string.diagnostics_detail, service.detail))
            appendLine(getString(R.string.diagnostics_image, service.imageStatus))
            appendLine(getString(R.string.diagnostics_wake_policy, service.wakePolicy))
            appendLine()
            appendLine(getString(R.string.diagnostics_session_count, JSONArray(service.sessionsJson).length()))
            appendLine(getString(R.string.diagnostics_ports))
            val ports = runCatching { JSONArray(service.forwardedPortsJson) }.getOrElse { JSONArray() }
            if (ports.length() == 0) appendLine(getString(R.string.diagnostics_no_ports))
            repeat(ports.length()) { index ->
                val port = ports.getJSONObject(index)
                appendLine(getString(R.string.diagnostics_port_item, port.getString("hostAddress"), port.getInt("hostPort"), port.getInt("guestPort")))
            }
            appendLine()
            append(getString(R.string.diagnostics_hint))
        }
    }
}
