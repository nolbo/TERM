package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import dev.terminal.app.databinding.ActivityMainBinding
import dev.terminal.app.databinding.DialogNewSessionBinding
import dev.terminal.app.databinding.DialogRootlessSetupBinding
import dev.terminal.app.ui.TerminalMotion
import dev.terminal.app.ui.TerminalActivity
import dev.terminal.image.DebianImageInstaller
import dev.terminal.image.ImageInstallResult
import dev.terminal.image.DebianImageLayout
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.ipc.IRuntimeStateListener
import dev.terminal.runtime.lifecycle.VmRuntimeService
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import com.termux.terminal.TerminalSession

/** Console-only UI. Runtime and shell ownership stay in :vmhost. */
class MainActivity : TerminalActivity() {
    private lateinit var binding: ActivityMainBinding
    private var runtime: IRuntimeService? = null
    private var bound = false
    private var bindingRequested = false
    private var sessionIds = emptyList<String>()
    private var activeSessionId: String? = null
    private var displayedSessionId: String? = null
    private var automaticShellRequested = false
    private var activityStarted = false
    private var rootlessInstallInFlight = false
    private var rootlessSessionsBeforeInstall = emptySet<String>()
    private var rootlessSetupDialog: AlertDialog? = null
    private var rootlessSetupBinding: DialogRootlessSetupBinding? = null
    private val terminalBackends = ConcurrentHashMap<String, RemoteTerminalBackend>()
    private val terminalSessions = mutableMapOf<String, TerminalSession>()
    private val sessionExitCodes = mutableMapOf<String, Int>()
    private lateinit var terminalInputCallbacks: TerminalInputCallbacks
    private val listener = object : IRuntimeStateListener.Stub() {
        override fun onRuntimeStateChanged(state: String, detail: String) = runOnUiThread { renderState(state, detail) }
        override fun onSessionsChanged(sessionsJson: String) = runOnUiThread { renderSessions(sessionsJson) }
        override fun onTerminalOutput(sessionId: String, bytes: ByteArray) {
            // TerminalSession's queue is producer/consumer based. Feeding it on
            // the main thread can deadlock when a transcript exceeds 4 KiB.
            terminalBackends[sessionId]?.output(bytes)
        }
        override fun onSessionExited(sessionId: String, exitCode: Int) = runOnUiThread {
            sessionExitCodes[sessionId] = exitCode
            terminalBackends[sessionId]?.exit(exitCode)
        }
        override fun onOperationError(operation: String, message: String) = runOnUiThread {
            binding.terminalOutput.visibility = if (activeSessionId == null) android.view.View.VISIBLE else binding.terminalOutput.visibility
            binding.terminalOutput.text = "$ $operation 실패: $message"
            android.widget.Toast.makeText(this@MainActivity, "$operation 실패: $message", android.widget.Toast.LENGTH_LONG).show()
            automaticShellRequested = false
            if (operation.contains("ROOTLESS", ignoreCase = true) || rootlessInstallInFlight) showRootlessInstallFailure(message)
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (!activityStarted) {
                runCatching { unbindService(this) }
                bindingRequested = false
                return
            }
            runtime = IRuntimeService.Stub.asInterface(service); bound = true
            runCatching {
                runtime?.registerListener(listener)
                // Restore the service-owned registry before evaluating READY.
                // The opposite order created a duplicate shell on every Activity recreation.
                renderSessions(runtime?.sessionsJson.orEmpty())
                renderState(runtime?.state.orEmpty(), runtime?.detail.orEmpty())
                runtime?.startRuntime()
            }.onFailure { handleRuntimeDisconnect() }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            // Android keeps the binding and may reconnect it when the service process returns.
            bound = false
            runtime = null
            automaticShellRequested = false
            binding.terminalOutput.text = "$ 런타임 프로세스가 종료되었습니다. 시스템 재연결을 기다리는 중…"
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater); setContentView(binding.root)
        terminalInputCallbacks = TerminalInputCallbacks { updateModifierButtons() }
        binding.terminalView.setTerminalViewClient(terminalInputCallbacks)
        binding.terminalOutput.text = "$ 런타임에 연결하는 중…"
        TerminalAppearance.apply(getSharedPreferences("terminal-ui", Context.MODE_PRIVATE), binding.terminalView)
        installRootInsets(binding.consoleRoot, includeIme = true)
        installTopInset(binding.consoleChrome)
        binding.terminalOutput.textSize = getSharedPreferences("terminal-ui", Context.MODE_PRIVATE).getInt("font-size-sp", 16).toFloat()
        binding.hotkeyEscape.setOnClickListener { writeRaw("\u001b") }
        binding.hotkeySlash.setOnClickListener { writeRaw("/") }
        binding.hotkeyMinus.setOnClickListener { writeRaw("-") }
        binding.hotkeyHome.setOnClickListener { writeRaw("\u001b[H") }
        binding.hotkeyEnd.setOnClickListener { writeRaw("\u001b[F") }
        binding.hotkeyPageUp.setOnClickListener { writeRaw("\u001b[5~") }
        binding.hotkeyTab.setOnClickListener { writeRaw("\t") }
        binding.hotkeyCtrl.setOnClickListener { terminalInputCallbacks.controlPressed = !terminalInputCallbacks.controlPressed; updateModifierButtons() }
        binding.hotkeyAlt.setOnClickListener { terminalInputCallbacks.altPressed = !terminalInputCallbacks.altPressed; updateModifierButtons() }
        binding.hotkeyUp.setOnClickListener { writeRaw("\u001b[A") }
        binding.hotkeyDown.setOnClickListener { writeRaw("\u001b[B") }
        binding.hotkeyLeft.setOnClickListener { writeRaw("\u001b[D") }
        binding.hotkeyRight.setOnClickListener { writeRaw("\u001b[C") }
        binding.hotkeyPageDown.setOnClickListener { writeRaw("\u001b[6~") }
        binding.newSessionButton.setOnClickListener { showNewSessionDialog() }
        binding.settingsButton.setOnClickListener { startActivityWithMotion(Intent(this, SettingsActivity::class.java)) }
        runCatching { startForegroundService(Intent(this, VmRuntimeService::class.java)) }
            .onFailure { binding.terminalOutput.text = "$ 백그라운드 런타임 시작 실패: ${it.message}" }
        if (intent.getBooleanExtra(EXTRA_OPEN_IMAGE_PICKER, false)) openImagePicker()
    }
    override fun onStart() {
        super.onStart()
        activityStarted = true
        binding.root.removeCallbacks(reconnectRuntime)
        bindRuntime()
    }
    override fun onResume() {
        super.onResume()
        TerminalAppearance.apply(getSharedPreferences("terminal-ui", Context.MODE_PRIVATE), binding.terminalView)
    }
    override fun onStop() {
        activityStarted = false
        binding.root.removeCallbacks(reconnectRuntime)
        if (bindingRequested) {
            // Activity destruction detaches every renderer subscription; PTYs remain service-owned.
            if (bound) {
                sessionIds.forEach { sessionId -> runCatching { runtime?.detachSession(sessionId) } }
                runtime?.unregisterListener(listener)
            }
            runCatching { unbindService(connection) }
        }
        // Renderer state belongs to this Activity. Rebuild it from the service-owned
        // transcript on the next attach instead of retaining a detached callback.
        terminalBackends.values.forEach(RemoteTerminalBackend::close)
        terminalBackends.clear()
        terminalSessions.clear()
        displayedSessionId = null
        bindingRequested = false; bound = false; runtime = null; super.onStop()
    }
    private fun renderState(state: String, detail: String) {
        if (state == "FAILED") {
            binding.terminalOutput.visibility = android.view.View.VISIBLE
            binding.terminalOutput.text = "$ ${detail.ifBlank { "런타임 오류" }}"
        }
        if (state in setOf("READY", "ACTIVE", "IDLE") && sessionIds.isEmpty() && !automaticShellRequested) {
            automaticShellRequested = true
            createDefaultSession()
        }
    }
    private fun renderSessions(json: String) {
        TerminalMotion.fadeThrough(binding.sessionTabs)
        val values = runCatching { JSONArray(json.ifBlank { "[]" }) }.getOrElse { JSONArray() }
        sessionIds = List(values.length()) { values.getJSONObject(it).getString("id") }
        sessionExitCodes.keys.retainAll(sessionIds.toSet())
        repeat(values.length()) { i ->
            values.getJSONObject(i).takeIf { it.optString("state") == "EXITED" }?.let {
                sessionExitCodes[it.getString("id")] = it.optInt("exitCode", 0)
            }
        }
        if (sessionIds.isNotEmpty()) automaticShellRequested = false
        if (rootlessInstallInFlight) {
            val newRootless = (0 until values.length()).map { values.getJSONObject(it) }
                .firstOrNull { it.optString("runtime") == "ROOTLESS" && it.getString("id") !in rootlessSessionsBeforeInstall }
            newRootless?.let {
                rootlessInstallInFlight = false
                rootlessSetupDialog?.dismiss()
                attach(it.getString("id"))
            }
        }
        if (activeSessionId !in sessionIds) activeSessionId = sessionIds.firstOrNull()
        binding.sessionTabs.removeAllViews()
        repeat(values.length()) { i ->
            val item = values.getJSONObject(i); val id = item.getString("id")
            val tab = layoutInflater.inflate(R.layout.item_session_tab, binding.sessionTabs, false).apply {
                tag = id
                isSelected = id == activeSessionId
                setOnClickListener { attach(id) }
                val runtimeLabel = when (item.optString("runtime", "NATIVE")) {
                    "ROOTLESS" -> "LINUX"
                    "AVF" -> "AVF"
                    else -> "ANDROID"
                }
                findViewById<android.widget.TextView>(R.id.tab_title).text = "$runtimeLabel · ${item.optString("title", "셸")}" 
                findViewById<android.widget.ImageButton>(R.id.tab_close).apply {
                    contentDescription = "${item.optString("title", "셸")} 세션 종료"
                    setOnClickListener { closeSession(id) }
                }
            }
            binding.sessionTabs.addView(tab)
        }
        activeSessionId?.takeIf { it != displayedSessionId }?.let(::attach)
    }
    private fun attach(id: String) {
        activeSessionId = id
        repeat(binding.sessionTabs.childCount) { index ->
            binding.sessionTabs.getChildAt(index).isSelected = binding.sessionTabs.getChildAt(index).tag == id
        }
        val newlyAttached = id !in terminalSessions
        val session = terminalSessions.getOrPut(id) {
            TerminalSession(defaultShell(), DEFAULT_GUEST_HOME, emptyArray(), emptyArray(),
                getSharedPreferences(SettingsDetailActivity.TERMINAL_PREFERENCES, Context.MODE_PRIVATE).getInt(SettingsDetailActivity.KEY_SCROLLBACK, 10_000),
                TerminalCallbacks { binding.terminalView.onScreenUpdated() },
                RemoteTerminalBackend(
                    write = { data -> safeRuntimeCall { it.writeSessionInput(id, data) } },
                    resize = { columns, rows -> safeRuntimeCall { it.resizeSession(id, rows, columns) } },
                )
                    .also { terminalBackends[id] = it })
        }
        binding.terminalOutput.visibility = android.view.View.GONE
        binding.terminalView.attachSession(session)
        sessionExitCodes[id]?.let { terminalBackends[id]?.exit(it) }
        displayedSessionId = id
        binding.terminalView.requestFocus()
        binding.terminalView.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(binding.terminalView, InputMethodManager.SHOW_IMPLICIT)
        }
        if (newlyAttached) safeRuntimeCall { it.attachSession(id) }
    }
    private fun closeSession(id: String) {
        terminalBackends.remove(id)?.close()
        terminalSessions.remove(id)
        if (displayedSessionId == id) displayedSessionId = null
        if (activeSessionId == id) activeSessionId = null
        safeRuntimeCall { it.terminateSession(id) }
    }
    private fun writeRaw(text: String) { activeSessionId?.let { id -> safeRuntimeCall { it.writeSessionInput(id, text.encodeToByteArray()) } } }
    private fun createDefaultSession() = createSession("NATIVE")

    private fun createSession(runtimeType: String) = safeRuntimeCall { service ->
        val shell = when (runtimeType) {
            "ROOTLESS" -> "/bin/sh"
            "AVF" -> "/bin/bash"
            else -> defaultShell()
        }
        val cwd = when (runtimeType) {
            "ROOTLESS" -> "/root"
            "AVF" -> DEFAULT_GUEST_HOME
            else -> "/"
        }
        service.createSession(runtimeType, shell, cwd, 24, 80)
    }

    private fun showNewSessionDialog() {
        val service = runtime ?: run {
            binding.terminalOutput.visibility = android.view.View.VISIBLE
            binding.terminalOutput.text = "$ 런타임에 연결하는 중…"
            bindRuntime()
            return
        }
        val status = runCatching { org.json.JSONObject(service.runtimeStatusJson) }.getOrNull()
        val dialogBinding = DialogNewSessionBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this).setView(dialogBinding.root).create()
        fun select(runtimeType: String, label: String) {
            val runtimeStatus = status?.optJSONObject(runtimeType)
            when {
                runtimeType == "ROOTLESS" -> { dialog.dismiss(); showRootlessSetup() }
                runtimeStatus?.optBoolean("available", runtimeType == "NATIVE") == true -> { dialog.dismiss(); createSession(runtimeType) }
                else -> AlertDialog.Builder(this).setTitle("$label 준비 필요")
                    .setMessage(runtimeStatus?.optString("detail").orEmpty().ifBlank { "지원 기기와 특권 AVF 제품 빌드가 필요합니다." })
                    .setPositiveButton("확인", null).show()
            }
        }
        dialogBinding.runtimeNative.setOnClickListener { select("NATIVE", "Android") }
        dialogBinding.runtimeRootless.setOnClickListener { select("ROOTLESS", "Rootless Debian") }
        dialogBinding.runtimeAvf.setOnClickListener { select("AVF", "AVF Linux") }
        dialogBinding.cancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showRootlessSetup() {
        val dialogBinding = DialogRootlessSetupBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this).setView(dialogBinding.root).create()
        rootlessSetupDialog = dialog; rootlessSetupBinding = dialogBinding
        dialogBinding.setupClose.setOnClickListener { dialog.dismiss() }
        dialogBinding.setupRetry.setOnClickListener { beginRootlessInstall() }
        dialog.setOnDismissListener { if (rootlessSetupDialog === dialog) { rootlessSetupDialog = null; rootlessSetupBinding = null } }
        dialog.show()
        beginRootlessInstall()
    }

    private fun beginRootlessInstall() {
        if (rootlessInstallInFlight) return
        val setup = rootlessSetupBinding ?: return
        rootlessInstallInFlight = true; rootlessSessionsBeforeInstall = sessionIds.toSet()
        setup.setupStatus.text = "Debian 설치 중"
        setup.setupDetail.text = "이미지 다운로드, 무결성 검증 및 안전한 설치를 진행하고 있습니다."
        setup.setupProgress.visibility = android.view.View.VISIBLE; setup.setupRetry.visibility = android.view.View.GONE
        createSession("ROOTLESS")
    }

    private fun showRootlessInstallFailure(message: String) {
        rootlessInstallInFlight = false
        rootlessSetupBinding?.apply {
            setupStatus.text = "설치하지 못했습니다"
            setupDetail.text = message
            setupProgress.visibility = android.view.View.GONE; setupRetry.visibility = android.view.View.VISIBLE
        }
    }
    private fun defaultShell(): String = getSharedPreferences(SettingsDetailActivity.RUNTIME_PREFERENCES, Context.MODE_PRIVATE)
        .getString(SettingsDetailActivity.KEY_NATIVE_SHELL, "/system/bin/sh") ?: "/system/bin/sh"
    private fun updateModifierButtons() {
        binding.hotkeyCtrl.isChecked = terminalInputCallbacks.controlPressed
        binding.hotkeyAlt.isChecked = terminalInputCallbacks.altPressed
    }
    private fun safeRuntimeCall(action: (IRuntimeService) -> Unit) {
        val service = runtime
        if (service == null) {
            if (activityStarted) bindRuntime()
            return
        }
        runCatching { action(service) }.onFailure { handleRuntimeDisconnect() }
    }
    private fun handleRuntimeDisconnect() {
        if (bindingRequested) runCatching { unbindService(connection) }
        bindingRequested = false
        bound = false; runtime = null
        automaticShellRequested = false
        binding.terminalOutput.text = "$ 런타임 프로세스가 종료되었습니다. 다시 연결하는 중…"
        if (activityStarted) binding.root.postDelayed(reconnectRuntime, RECONNECT_DELAY_MS)
    }
    private val reconnectRuntime = Runnable { if (activityStarted && !bindingRequested) bindRuntime() }
    private fun bindRuntime() {
        if (bindingRequested) return
        val accepted = runCatching {
            bindService(Intent(this, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        bindingRequested = accepted
        if (!accepted && activityStarted) binding.root.postDelayed(reconnectRuntime, RECONNECT_DELAY_MS)
    }
    private fun openImagePicker() = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), IMAGE_REQUEST)
    @Deprecated("View spike") override fun onActivityResult(request: Int, result: Int, data: Intent?) { super.onActivityResult(request, result, data); if (request == IMAGE_REQUEST && result == RESULT_OK) data?.data?.let { importImage(DocumentFile.fromTreeUri(this, it) ?: return) } }
    private fun importImage(directory: DocumentFile) = Thread {
        val staging = cacheDir.toPath().resolve("image-${UUID.randomUUID()}")
        val message = runCatching {
            Files.createDirectories(staging)
            DebianImageLayout(staging).let { layout ->
                listOf(layout.manifest, layout.kernelImage, layout.initrdImage, layout.systemImage, layout.homeImage, layout.configImage)
                    .forEach { target ->
                        val source = directory.findFile(target.fileName.toString())
                            ?: error("필수 파일 누락: ${target.fileName}")
                        contentResolver.openInputStream(source.uri)?.use { input ->
                            Files.newOutputStream(target).use(input::copyTo)
                        } ?: error("파일을 열 수 없음: ${target.fileName}")
                    }
            }
            val destination = filesDir.toPath().resolve("vm/default")
            Files.createDirectories(destination.parent)
            when (val result = DebianImageInstaller.install(staging, destination)) {
                is ImageInstallResult.Installed -> "Debian 이미지를 가져왔습니다"
                is ImageInstallResult.AlreadyInstalled -> "Debian 이미지가 이미 설치되어 있습니다"
                is ImageInstallResult.Failed -> error(result.reason)
            }
        }.getOrElse { "이미지 가져오기 실패: ${it.message ?: it.javaClass.simpleName}" }
        runOnUiThread {
            binding.terminalOutput.visibility = android.view.View.VISIBLE
            binding.terminalOutput.text = "$ $message"
        }
        runCatching { Files.walk(staging).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }.start()
    companion object {
        const val EXTRA_OPEN_IMAGE_PICKER = "open_image_picker"
        const val EXTRA_START_RUNTIME = "start_runtime"
        private const val DEFAULT_GUEST_HOME = "/home/user"
        private const val IMAGE_REQUEST = 4001
        private const val MAX = 16_384
        private const val RECONNECT_DELAY_MS = 1_000L
    }
}
