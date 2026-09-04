package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import dev.terminal.app.databinding.ActivityDiagnosticsBinding
import dev.terminal.app.ui.TerminalActivity
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import org.json.JSONArray

/** Read-only operational view; it helps distinguish image, AVF, guest and forwarding failures. */
class DiagnosticsActivity : TerminalActivity() {
    private lateinit var binding: ActivityDiagnosticsBinding
    private var runtime: IRuntimeService? = null
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            runtime = IRuntimeService.Stub.asInterface(service)
            bound = true
            render()
        }
        override fun onServiceDisconnected(name: ComponentName) { runtime = null; bound = false; render() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        installRootInsets(binding.root)
        binding.toolbar.setNavigationOnClickListener { finishWithMotion() }
        binding.toolbar.setAction(R.drawable.ic_refresh, "새로고침") { render() }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        if (bound) unbindService(connection)
        bound = false
        runtime = null
        super.onStop()
    }

    private fun render() {
        val service = runtime
        if (service == null) {
            binding.diagnosticsText.text = "런타임 서비스에 연결하는 중…"
            return
        }
        binding.diagnosticsText.text = buildString {
            appendLine("런타임 상태: ${service.state}")
            appendLine("상세: ${service.detail}")
            appendLine("이미지: ${service.imageStatus}")
            appendLine("절전 정책: ${service.wakePolicy}")
            appendLine()
            appendLine("세션: ${JSONArray(service.sessionsJson).length()}개")
            appendLine("포트 전달:")
            val ports = runCatching { JSONArray(service.forwardedPortsJson) }.getOrElse { JSONArray() }
            if (ports.length() == 0) appendLine("  활성 포트 없음")
            repeat(ports.length()) { index ->
                val port = ports.getJSONObject(index)
                appendLine("  ${port.getString("hostAddress")}:${port.getInt("hostPort")} → 게스트 ${port.getInt("guestPort")}")
            }
            appendLine()
            append("AVF 권한 또는 게스트 vsock 오류는 위 상태/상세 항목에서 확인하세요.")
        }
    }
}
