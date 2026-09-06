package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import dev.terminal.app.ui.ExtraKeys
import dev.terminal.app.ui.RootlessSetupDialog
import dev.terminal.app.ui.RuntimeChoiceUi
import dev.terminal.app.ui.RuntimeSessionAttachment
import dev.terminal.app.ui.SessionUi
import dev.terminal.app.ui.TerminalActivity
import dev.terminal.app.ui.TerminalChrome
import dev.terminal.app.ui.TerminalEmptyState
import dev.terminal.app.ui.parseRuntimeChoices
import dev.terminal.app.ui.parseSessions
import dev.terminal.app.ui.failSessionTermination
import dev.terminal.app.ui.reconcileSessionTabs
import dev.terminal.app.ui.requestSessionTermination
import dev.terminal.image.DebianImageInstaller
import dev.terminal.image.DebianImageLayout
import dev.terminal.image.ImageInstallResult
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.ipc.IRuntimeStateListener
import dev.terminal.runtime.lifecycle.VmRuntimeService
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Compose console projection. Runtime and shell ownership stay in :vmhost. */
class MainActivity : TerminalActivity() {
    private var sessions by mutableStateOf(emptyList<SessionUi>())
    private var runtimeChoices by mutableStateOf(emptyList<RuntimeChoiceUi>())
    private var activeSessionId by mutableStateOf<String?>(null)
    private var runtimeMessage by mutableStateOf("")
    private var feedbackMessage by mutableStateOf<String?>(null)
    private var newSessionMenuVisible by mutableStateOf(false)
    private var unavailableChoice by mutableStateOf<RuntimeChoiceUi?>(null)
    private var pendingTermination by mutableStateOf<SessionUi?>(null)
    private var terminatingSessionIds by mutableStateOf(emptySet<String>())
    private var rootlessVisible by mutableStateOf(false)
    private var rootlessRunning by mutableStateOf(false)
    private var rootlessStatus by mutableStateOf("")
    private var rootlessDetail by mutableStateOf("")
    private var rootlessError by mutableStateOf<String?>(null)
    private var controlPressed by mutableStateOf(false)
    private var altPressed by mutableStateOf(false)

    private var runtime: IRuntimeService? = null
    private var bound = false
    private var bindingRequested = false
    private var activityStarted = false
    private var rootlessInstallInFlight = false
    private var rootlessSessionsBeforeInstall = emptySet<String>()
    private var displayedSessionId: String? = null
    private var terminalView: TerminalView? = null
    private var bindingGeneration = 0L
    private var lastSessionRevision = -1L
    private var listener: IRuntimeStateListener? = null
    private val runtimeAttachment = RuntimeSessionAttachment()
    private val mainHandler by lazy { Handler(mainLooper) }
    private val terminalBackends = ConcurrentHashMap<String, RemoteTerminalBackend>()
    private val terminalSessions = mutableMapOf<String, TerminalSession>()
    private val sessionExitCodes = mutableMapOf<String, Int>()
    private lateinit var terminalInputCallbacks: TerminalInputCallbacks

    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { importImage(DocumentFile.fromTreeUri(this, it) ?: return@let) }
    }

    private fun createRuntimeListener(generation: Long) = object : IRuntimeStateListener.Stub() {
        override fun onRuntimeStateChanged(state: String, detail: String) = runOnUiThread {
            if (generation == bindingGeneration) renderRuntimeState(state, detail)
        }

        override fun onSessionsChanged(revision: Long, sessionsJson: String) = runOnUiThread {
            if (generation == bindingGeneration) renderSessions(revision, sessionsJson)
        }

        override fun onTerminalOutput(sessionId: String, bytes: ByteArray) {
            if (generation == bindingGeneration) terminalBackends[sessionId]?.output(bytes)
        }

        override fun onSessionExited(sessionId: String, exitCode: Int) = runOnUiThread {
            if (generation == bindingGeneration) {
                sessionExitCodes[sessionId] = exitCode
                terminalBackends[sessionId]?.exit(exitCode)
            }
        }

        override fun onSessionTerminationFailed(sessionId: String, message: String) = runOnUiThread {
            if (generation == bindingGeneration) {
                terminatingSessionIds = failSessionTermination(terminatingSessionIds, sessionId)
                val feedback = getString(R.string.operation_failed, getString(R.string.terminate), message)
                runtimeMessage = getString(R.string.runtime_error_prompt, feedback)
                feedbackMessage = feedback
            }
        }

        override fun onRootlessProgress(phase: String, detail: String) = runOnUiThread {
            if (generation != bindingGeneration) return@runOnUiThread
            rootlessRunning = true
            rootlessStatus = phase
            rootlessDetail = detail
            rootlessError = null
        }

        override fun onOperationError(operation: String, message: String) = runOnUiThread {
            if (generation != bindingGeneration) return@runOnUiThread
            val feedback = getString(R.string.operation_failed, operation, message)
            runtimeMessage = getString(R.string.runtime_error_prompt, feedback)
            feedbackMessage = feedback
            if (operation.contains("ROOTLESS", ignoreCase = true) || rootlessInstallInFlight) {
                rootlessInstallInFlight = false
                rootlessRunning = false
                rootlessStatus = getString(R.string.rootless_install_failed)
                rootlessError = message
                rootlessVisible = true
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (!activityStarted) {
                runCatching { unbindService(this) }
                bindingRequested = false
                return
            }
            runtime = IRuntimeService.Stub.asInterface(service)
            bound = true
            runCatching {
                val connected = checkNotNull(runtime)
                val currentListener = checkNotNull(listener)
                connected.registerListener(currentListener)
                updateRuntimeChoices(connected.runtimeStatusJson)
                renderRuntimeState(connected.state, connected.detail)
                connected.startRuntime()
            }.onFailure { handleRuntimeDisconnect() }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            handleRuntimeDisconnect()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtimeMessage = getString(R.string.runtime_connecting_prompt)
        rootlessStatus = getString(R.string.rootless_setup_required)
        rootlessDetail = getString(R.string.rootless_setup_initial)
        runtimeChoices = defaultRuntimeChoices()
        terminalInputCallbacks = TerminalInputCallbacks(::syncModifierState)
        compose { TerminalScreen() }
        runCatching { startForegroundService(Intent(this, VmRuntimeService::class.java)) }
            .onFailure { feedbackMessage = getString(R.string.runtime_start_failed, it.message.orEmpty()) }
        handleLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        mainHandler.removeCallbacks(reconnectRuntime)
        bindRuntime()
    }

    override fun onResume() {
        super.onResume()
        terminalView?.let { TerminalAppearance.apply(terminalPreferences(), it) }
    }

    override fun onStop() {
        activityStarted = false
        bindingGeneration++
        mainHandler.removeCallbacks(reconnectRuntime)
        if (bindingRequested) {
            if (bound) {
                runtimeAttachment.detach()?.let { id -> runCatching { runtime?.detachSession(id) } }
                listener?.let { current -> runCatching { runtime?.unregisterListener(current) } }
            }
            runCatching { unbindService(connection) }
        }
        runtimeAttachment.reset()
        terminalBackends.values.forEach(RemoteTerminalBackend::close)
        terminalBackends.clear()
        terminalSessions.clear()
        displayedSessionId = null
        bindingRequested = false
        bound = false
        runtime = null
        listener = null
        super.onStop()
    }

    @Composable
    private fun TerminalScreen() {
        val snackbarHost = remember { SnackbarHostState() }
        LaunchedEffect(feedbackMessage) {
            feedbackMessage?.let {
                snackbarHost.showSnackbar(it)
                feedbackMessage = null
            }
        }
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHost) },
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        ) { scaffoldPadding -> Column(Modifier.fillMaxSize().padding(scaffoldPadding).imePadding()) {
            TerminalChrome(
                sessions = sessions,
                activeSessionId = activeSessionId,
                terminatingSessionIds = terminatingSessionIds,
                runtimeChoices = runtimeChoices,
                newSessionMenuExpanded = newSessionMenuVisible,
                onSelect = ::attach,
                onTerminate = { pendingTermination = it },
                onNew = ::showNewSession,
                onDismissNewSession = { newSessionMenuVisible = false },
                onSelectRuntime = ::selectRuntime,
                onSettings = { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) },
            )
            if (activeSessionId == null) {
                TerminalEmptyState(runtimeMessage, ::showNewSession)
            } else {
                AndroidView(
                    factory = { context ->
                        TerminalView(context, null).apply {
                            terminalView = this
                            setTerminalViewClient(terminalInputCallbacks)
                            TerminalAppearance.apply(terminalPreferences(), this)
                        }
                    },
                    update = { view -> attachRenderer(view) },
                    onRelease = { view -> if (terminalView === view) terminalView = null },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                ExtraKeys(
                    control = controlPressed,
                    alt = altPressed,
                    onKey = ::writeRaw,
                    onControl = {
                        terminalInputCallbacks.controlPressed = !terminalInputCallbacks.controlPressed
                        syncModifierState()
                    },
                    onAlt = {
                        terminalInputCallbacks.altPressed = !terminalInputCallbacks.altPressed
                        syncModifierState()
                    },
                )
            }
        } }

        if (rootlessVisible) {
            RootlessSetupDialog(rootlessStatus, rootlessDetail, rootlessRunning, rootlessError, ::beginRootlessInstall) {
                rootlessVisible = false
            }
        }
        unavailableChoice?.let { choice ->
            val title = when (choice.id) {
                "AVF" -> stringResource(R.string.runtime_avf_title)
                "ROOTLESS" -> stringResource(R.string.runtime_rootless_title)
                else -> stringResource(R.string.runtime_native_title)
            }
            AlertDialog(
                onDismissRequest = { unavailableChoice = null },
                title = { Text(stringResource(R.string.runtime_unavailable_title, title)) },
                text = { Text(choice.detail.ifBlank { stringResource(R.string.avf_unavailable_default) }) },
                confirmButton = { TextButton(onClick = { unavailableChoice = null }) { Text(stringResource(R.string.confirm)) } },
            )
        }
        pendingTermination?.let { session ->
            val title = session.title.ifBlank { stringResource(R.string.shell_default_title) }
            AlertDialog(
                onDismissRequest = { pendingTermination = null },
                title = { Text(stringResource(R.string.terminate_session_title)) },
                text = { Text(stringResource(R.string.terminate_session_message, title)) },
                confirmButton = {
                    Button(onClick = { pendingTermination = null; terminate(session.id) }) {
                        Text(stringResource(R.string.terminate))
                    }
                },
                dismissButton = { TextButton(onClick = { pendingTermination = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }

    private fun renderRuntimeState(state: String, detail: String) {
        runtimeMessage = when (state) {
            "FAILED" -> getString(R.string.runtime_error_prompt, detail.ifBlank { getString(R.string.runtime_disconnected) })
            else -> getString(R.string.runtime_state_prompt, detail.ifBlank { state })
        }
    }

    private fun renderSessions(revision: Long, json: String) {
        if (revision < lastSessionRevision) return
        lastSessionRevision = revision
        val updated = parseSessions(json)
        val tabUpdate = reconcileSessionTabs(sessions, activeSessionId, terminatingSessionIds, updated)
        val removedSessionIds = tabUpdate.removedSessionIds
        sessions = updated
        activeSessionId = tabUpdate.activeSessionId
        terminatingSessionIds = tabUpdate.pendingTerminationIds
        val ids = updated.mapTo(mutableSetOf()) { it.id }
        sessionExitCodes.keys.retainAll(ids)
        updated.forEach { it.exitCode?.let { code -> sessionExitCodes[it.id] = code } }

        if (rootlessInstallInFlight) {
            updated.firstOrNull { it.runtime == "ROOTLESS" && it.id !in rootlessSessionsBeforeInstall }?.let {
                rootlessInstallInFlight = false
                rootlessRunning = false
                rootlessVisible = false
                activeSessionId = it.id
            }
        }
        val selected = activeSessionId
        if (selected == null) {
            runtimeAttachment.reset()
            releaseTerminalFocus()
        } else if (selected != displayedSessionId || runtimeAttachment.sessionId != selected) {
            attach(selected)
        }
        removedSessionIds.forEach { id ->
            terminalBackends.remove(id)?.close()
            terminalSessions.remove(id)
            runtimeAttachment.forget(id)
            if (displayedSessionId == id) displayedSessionId = null
        }
        runtime?.let { service -> runCatching { updateRuntimeChoices(service.runtimeStatusJson) } }
    }

    private fun attach(id: String) {
        if (id !in sessions.map { it.id }) return
        activeSessionId = id
        val session = terminalSessions.getOrPut(id) {
            TerminalSession(
                defaultShell(),
                DEFAULT_GUEST_HOME,
                emptyArray(),
                emptyArray(),
                terminalPreferences().getInt(SettingsDetailActivity.KEY_SCROLLBACK, 10_000),
                TerminalCallbacks { terminalView?.onScreenUpdated() },
                RemoteTerminalBackend(
                    write = { data -> safeRuntimeCall { it.writeSessionInput(id, data) } },
                    resize = { columns, rows -> safeRuntimeCall { it.resizeSession(id, rows, columns) } },
                ).also { terminalBackends[id] = it },
            )
        }
        sessionExitCodes[id]?.let { terminalBackends[id]?.exit(it) }
        displayedSessionId = id
        terminalView?.let(::attachRenderer)
        runtimeAttachment.select(id)?.let { selectedId ->
            safeRuntimeCall { it.attachSession(selectedId) }
        }
    }

    private fun attachRenderer(view: TerminalView) {
        val id = activeSessionId ?: return
        val session = terminalSessions[id] ?: return
        if (view.currentSession !== session) view.attachSession(session)
        view.requestFocus()
        view.post {
            if (activeSessionId == id && terminalView === view) {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun releaseTerminalFocus() {
        terminalView?.let { view ->
            view.clearFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    private fun terminate(id: String) {
        if (id in terminatingSessionIds || sessions.none { it.id == id }) return
        val service = runtime
        if (service == null) {
            feedbackMessage = getString(R.string.runtime_connecting_prompt)
            bindRuntime()
            return
        }
        terminatingSessionIds = requestSessionTermination(sessions, terminatingSessionIds, id)
        runCatching { service.terminateSession(id) }
            .onFailure {
                terminatingSessionIds = failSessionTermination(terminatingSessionIds, id)
                handleRuntimeDisconnect()
            }
    }

    private fun showNewSession() {
        val service = runtime
        if (service == null) {
            feedbackMessage = getString(R.string.runtime_connecting_prompt)
            bindRuntime()
            return
        }
        updateRuntimeChoices(service.runtimeStatusJson)
        newSessionMenuVisible = true
    }

    private fun selectRuntime(choice: RuntimeChoiceUi) {
        newSessionMenuVisible = false
        when {
            choice.id == "ROOTLESS" -> {
                rootlessVisible = true
                rootlessStatus = if (choice.available) getString(R.string.rootless_available) else getString(R.string.rootless_setup_required)
                rootlessDetail = choice.detail.ifBlank { getString(R.string.rootless_setup_initial) }
                if (!rootlessInstallInFlight) beginRootlessInstall()
            }
            choice.available -> createSession(choice.id)
            else -> unavailableChoice = choice
        }
    }

    private fun beginRootlessInstall() {
        if (rootlessInstallInFlight) return
        rootlessInstallInFlight = true
        rootlessRunning = true
        rootlessSessionsBeforeInstall = sessions.mapTo(mutableSetOf()) { it.id }
        rootlessStatus = getString(R.string.rootless_installing)
        rootlessDetail = getString(R.string.rootless_install_detail)
        rootlessError = null
        createSession("ROOTLESS")
    }

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

    private fun writeRaw(text: String) {
        activeSessionId?.let { id -> safeRuntimeCall { it.writeSessionInput(id, text.encodeToByteArray()) } }
    }

    private fun syncModifierState() {
        controlPressed = terminalInputCallbacks.controlPressed
        altPressed = terminalInputCallbacks.altPressed
    }

    private fun updateRuntimeChoices(json: String) {
        runtimeChoices = parseRuntimeChoices(json).map { choice ->
            if (choice.detail.isNotBlank()) choice else choice.copy(detail = defaultRuntimeDetail(choice.id))
        }
        runtimeChoices.firstOrNull { it.id == "ROOTLESS" }?.let { rootlessDetail = it.detail }
    }

    private fun defaultRuntimeChoices() = parseRuntimeChoices("").map {
        it.copy(detail = defaultRuntimeDetail(it.id))
    }

    private fun defaultRuntimeDetail(runtimeType: String) = when (runtimeType) {
        "ROOTLESS" -> getString(R.string.runtime_rootless_description)
        "AVF" -> getString(R.string.avf_unavailable_default)
        else -> getString(R.string.runtime_native_description)
    }

    private fun defaultShell(): String {
        val stored = getSharedPreferences(SettingsDetailActivity.RUNTIME_PREFERENCES, MODE_PRIVATE)
            .getString(SettingsDetailActivity.KEY_NATIVE_SHELL, DEFAULT_NATIVE_SHELL) ?: DEFAULT_NATIVE_SHELL
        return stored.takeIf { path -> File(path).let { it.isFile && it.canExecute() } } ?: DEFAULT_NATIVE_SHELL
    }

    private fun terminalPreferences() = getSharedPreferences(SettingsDetailActivity.TERMINAL_PREFERENCES, MODE_PRIVATE)

    private fun handleLaunchIntent(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_OPEN_IMAGE_PICKER, false)) imagePicker.launch(null)
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
        bindingGeneration++
        bindingRequested = false
        bound = false
        runtime = null
        listener = null
        runtimeAttachment.reset()
        runtimeMessage = getString(R.string.runtime_reconnecting_prompt)
        if (activityStarted) mainHandler.postDelayed(reconnectRuntime, RECONNECT_DELAY_MS)
    }

    private val reconnectRuntime = Runnable {
        if (activityStarted && !bindingRequested) bindRuntime()
    }

    private fun bindRuntime() {
        if (bindingRequested) return
        val generation = ++bindingGeneration
        listener = createRuntimeListener(generation)
        lastSessionRevision = -1L
        bindingRequested = runCatching {
            bindService(Intent(this, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bindingRequested) {
            listener = null
            if (activityStarted) mainHandler.postDelayed(reconnectRuntime, RECONNECT_DELAY_MS)
        }
    }

    private fun importImage(directory: DocumentFile) = Thread {
        val staging = cacheDir.toPath().resolve("image-${UUID.randomUUID()}")
        val message = runCatching {
            Files.createDirectories(staging)
            val layout = DebianImageLayout(staging)
            listOf(layout.manifest, layout.kernelImage, layout.initrdImage, layout.systemImage, layout.homeImage, layout.configImage)
                .forEach { target ->
                    val source = directory.findFile(target.fileName.toString())
                        ?: error(getString(R.string.image_required_file_missing, target.fileName))
                    contentResolver.openInputStream(source.uri)?.use { input -> Files.newOutputStream(target).use(input::copyTo) }
                        ?: error(getString(R.string.image_open_failed, target.fileName))
                }
            val destination = filesDir.toPath().resolve("vm/default")
            Files.createDirectories(destination.parent)
            when (val result = DebianImageInstaller.install(staging, destination)) {
                is ImageInstallResult.Installed -> getString(R.string.image_imported)
                is ImageInstallResult.AlreadyInstalled -> getString(R.string.image_already_installed)
                is ImageInstallResult.Failed -> error(result.reason)
            }
        }.getOrElse { getString(R.string.image_import_failed, it.message ?: it.javaClass.simpleName) }
        runOnUiThread { feedbackMessage = message }
        runCatching { Files.walk(staging).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }.start()

    companion object {
        const val EXTRA_OPEN_IMAGE_PICKER = "open_image_picker"
        const val EXTRA_START_RUNTIME = "start_runtime"
        private const val DEFAULT_GUEST_HOME = "/home/user"
        private const val DEFAULT_NATIVE_SHELL = "/system/bin/sh"
        private const val RECONNECT_DELAY_MS = 1_000L
    }
}
