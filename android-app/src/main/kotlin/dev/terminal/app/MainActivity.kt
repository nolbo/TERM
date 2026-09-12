@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import dev.terminal.app.ui.ExtraKeys
import dev.terminal.app.ui.NewSessionSheet
import dev.terminal.app.ui.RootlessSetupDialog
import dev.terminal.app.ui.RootlessProgressUi
import dev.terminal.app.ui.RenameSessionDialog
import dev.terminal.app.ui.RuntimeChoiceUi
import dev.terminal.app.ui.RuntimeSelectionAction
import dev.terminal.app.ui.RuntimeSessionAttachment
import dev.terminal.app.ui.RuntimeUnavailableDialog
import dev.terminal.app.ui.SessionUi
import dev.terminal.app.ui.TerminalActivity
import dev.terminal.app.ui.TerminalChrome
import dev.terminal.app.ui.TerminalEmptyState
import dev.terminal.app.ui.TerminalHapticPreferences
import dev.terminal.app.ui.TerminalToolbarKey
import dev.terminal.app.ui.rootlessRuntimeIds
import dev.terminal.app.ui.runtimeTitleResource
import dev.terminal.app.ui.parseRuntimeChoices
import dev.terminal.app.ui.parseSessions
import dev.terminal.app.ui.sessionWithDisplayTitles
import dev.terminal.app.ui.failSessionTermination
import dev.terminal.app.ui.reconcileSessionTabs
import dev.terminal.app.ui.requestSessionTermination
import dev.terminal.app.ui.runtimeSelectionAction
import dev.terminal.app.ui.validatedSessionTitle
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
    private var newSessionSheetVisible by mutableStateOf(false)
    private var unavailableChoice by mutableStateOf<RuntimeChoiceUi?>(null)
    private var pendingRename by mutableStateOf<SessionUi?>(null)
    private var pendingTermination by mutableStateOf<SessionUi?>(null)
    private var terminatingSessionIds by mutableStateOf(emptySet<String>())
    private var rootlessVisible by mutableStateOf(false)
    private var rootlessRunning by mutableStateOf(false)
    private var rootlessStatus by mutableStateOf("")
    private var rootlessProgress by mutableStateOf<RootlessProgressUi?>(null)
    private var rootlessDetail by mutableStateOf("")
    private var rootlessError by mutableStateOf<String?>(null)
    private var hotkeyLayout by mutableStateOf(HotkeyLayout())
    private var controlPressed by mutableStateOf(false)
    private var altPressed by mutableStateOf(false)
    private var terminalBackgroundColor by mutableStateOf(Color.Black)
    private var terminalPaddingDp by mutableStateOf(TerminalAppearance.DEFAULT_PADDING_DP)
    private var applicationTerminalTitles by mutableStateOf(emptyMap<String, String>())
    private var terminalAppBarContentVisible by mutableStateOf(true)

    private var runtime: IRuntimeService? = null
    private var bound = false
    private var bindingRequested = false
    private var activityStarted = false
    private var toolbarInputActive by mutableStateOf(false)
    private var rootlessInstallRuntime by mutableStateOf("ROOTLESS")
    private var rootlessInstallInFlight = false
    private var rootlessSessionsBeforeInstall = emptySet<String>()
    private var displayedSessionId: String? = null
    private var terminalView: TerminalView? = null
    private var keyboardRequested = false
    private var bindingGeneration = 0L
    private var lastSessionRevision = -1L
    private var listener: IRuntimeStateListener? = null
    private val runtimeAttachment = RuntimeSessionAttachment()
    private val mainHandler by lazy { Handler(mainLooper) }
    private val terminalBackends = ConcurrentHashMap<String, RemoteTerminalBackend>()
    private val terminalSessions = mutableMapOf<String, TerminalSession>()
    private val initializedRuntimeTerminals = mutableSetOf<String>()
    private val sessionExitCodes = mutableMapOf<String, Int>()
    private lateinit var terminalInputCallbacks: TerminalInputCallbacks

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) feedbackMessage = "세션 알림을 보려면 앱 설정에서 알림을 허용해 주세요."
    }

    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { importImage(DocumentFile.fromTreeUri(this, it) ?: return@let) }
    }

    private fun createRuntimeListener(generation: Long) = object : IRuntimeStateListener.Stub() {
        override fun onRuntimeStateChanged(state: String, detail: String) = runOnUiThread {
            if (generation == bindingGeneration) {
                renderRuntimeState(state, detail)
                runCatching { runtime?.runtimeStatusJson?.let(::updateRuntimeChoices) }
            }
        }

        override fun onSessionFocusRequested(sessionId: String) = runOnUiThread {
            if (generation == bindingGeneration) attach(sessionId)
        }
        override fun onSessionsChanged(revision: Long, sessionsJson: String) = runOnUiThread {
            if (generation == bindingGeneration) renderSessions(revision, sessionsJson)
        }

        override fun onTerminalOutput(sessionId: String, bytes: ByteArray, replay: Boolean) {
            if (generation == bindingGeneration) terminalBackends[sessionId]?.output(bytes, replay)
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

        override fun onRootlessProgress(runtimeType: String, phase: String, detail: String) = runOnUiThread {
            if (generation != bindingGeneration) return@runOnUiThread
            if (runtimeType != rootlessInstallRuntime) return@runOnUiThread
            runCatching { runtime?.runtimeStatusJson?.let(::updateRuntimeChoices) }.onFailure {
                rootlessStatus = phase
                rootlessDetail = detail
            }
        }

        override fun onOperationError(operation: String, message: String) = runOnUiThread {
            if (generation != bindingGeneration) return@runOnUiThread
            val feedback = getString(R.string.operation_failed, operation.substringBefore(':'), message)
            runtimeMessage = getString(R.string.runtime_error_prompt, feedback)
            feedbackMessage = feedback
            if ((rootlessInstallInFlight || rootlessVisible) && operation.substringAfter(':', "") == rootlessInstallRuntime) {
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
        terminalBackgroundColor = Color(TerminalAppearance.backgroundColor(terminalPreferences()))
        terminalPaddingDp = TerminalAppearance.paddingDp(terminalPreferences())
        terminalInputCallbacks = TerminalInputCallbacks(
            onModifierStateChanged = ::syncModifierState,
            onShowKeyboard = ::requestTerminalKeyboard,
            onToggleTerminalChrome = ::toggleTerminalChrome,
            onPinchScale = ::scaleTerminalFont,
            onWebLink = ::openTerminalWebLink,
        )
        compose { TerminalScreen() }
        val notificationPreferences = getSharedPreferences("notification-permission", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !notificationPreferences.getBoolean("requested", false)
        ) {
            notificationPreferences.edit().putBoolean("requested", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
        toolbarInputActive = true
        val preferences = terminalPreferences()
        hotkeyLayout = readHotkeyLayout(preferences)
        terminalBackgroundColor = Color(TerminalAppearance.backgroundColor(preferences))
        terminalPaddingDp = TerminalAppearance.paddingDp(preferences)
        terminalView?.let {
            TerminalAppearance.apply(preferences, it)
            applySessionFontSize(it)
            applyScrollHapticSettings(it, preferences)
        }
    }

    override fun onPause() {
        toolbarInputActive = false
        super.onPause()
    }

    override fun onStop() {
        activityStarted = false
        bindingGeneration++
        keyboardRequested = false
        terminalView?.removeCallbacks(showTerminalKeyboard)
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
        applicationTerminalTitles = emptyMap()
        initializedRuntimeTerminals.clear()
        displayedSessionId = null
        bindingRequested = false
        bound = false
        runtime = null
        listener = null
        super.onStop()
    }

    @Composable
    private fun TerminalScreen() {
        val visibleSessions = sessions.map { session ->
            sessionWithDisplayTitles(session, applicationTerminalTitles[session.id])
        }
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
                sessions = visibleSessions,
                activeSessionId = activeSessionId,
                appBarContentVisible = activeSessionId == null || terminalAppBarContentVisible,
                terminatingSessionIds = terminatingSessionIds,
                runtimeChoices = runtimeChoices,
                newSessionMenuExpanded = newSessionMenuVisible,
                onSelect = ::attach,
                onRename = { pendingRename = it },
                onTerminate = { pendingTermination = it },
                onNew = ::showNewSessionMenu,
                onDismissNewSession = { newSessionMenuVisible = false },
                onSelectRuntime = ::selectRuntime,
                onSettings = { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) },
            )
            if (activeSessionId == null) {
                TerminalEmptyState(runtimeMessage, ::showNewSessionSheet)
            } else {
                AndroidView(
                    factory = { context ->
                        TerminalView(context, null).apply {
                            isFocusable = true
                            isFocusableInTouchMode = true
                            terminalView = this
                            setTerminalViewClient(terminalInputCallbacks)
                            applyScrollHapticSettings(this, terminalPreferences())
                            TerminalAppearance.apply(terminalPreferences(), this)
                        }
                    },
                    update = { view -> attachRenderer(view) },
                    onRelease = { view ->
                        view.removeCallbacks(showTerminalKeyboard)
                        if (terminalView === view) terminalView = null
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .background(terminalBackgroundColor)
                        .padding(terminalPaddingDp.dp),
                )
                val toolbarSessionId = activeSessionId
                androidx.compose.runtime.key(toolbarSessionId) { ExtraKeys(
                    layout = hotkeyLayout,
                    control = controlPressed,
                    alt = altPressed,
                    terminalBackgroundColor = terminalBackgroundColor,
                    onKey = { key ->
                        // A state change can precede the frame which disposes a held button.
                        if (activeSessionId == toolbarSessionId) sendToolbarKey(key)
                    },
                    enabled = toolbarInputActive,
                ) }
            }
        } }

        if (newSessionSheetVisible) {
            NewSessionSheet(
                runtimeChoices = runtimeChoices,
                onSelectRuntime = ::selectRuntime,
                onDismiss = { newSessionSheetVisible = false },
            )
        }
        if (rootlessVisible) {
            RootlessSetupDialog(rootlessInstallRuntime, rootlessStatus, rootlessDetail, rootlessRunning, rootlessError, ::beginRootlessInstall, rootlessProgress) {
                rootlessVisible = false
            }
        }
        unavailableChoice?.let { choice ->
            RuntimeUnavailableDialog(
                choice = choice,
                onInstall = {
                    unavailableChoice = null
                    rootlessInstallRuntime = choice.id
                    rootlessVisible = true
                    rootlessStatus = getString(R.string.rootless_setup_required)
                    rootlessDetail = choice.detail.ifBlank { getString(R.string.rootless_setup_initial) }
                    beginRootlessInstall()
                },
                onOpenSettings = {
                    unavailableChoice = null
                    openRuntimeSettings(choice.id)
                },
                onDismiss = { unavailableChoice = null },
            )
        }
        pendingTermination?.let { session ->
            val title = session.title.ifBlank { stringResource(R.string.shell_default_title) }
            AlertDialog(
                onDismissRequest = { pendingTermination = null },
                title = { Text(stringResource(R.string.terminate_session_title)) },
                text = { Text(stringResource(R.string.terminate_session_message, title)) },
                confirmButton = {
                    Button(onClick = { pendingTermination = null; terminate(session.id) }, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.terminate))
                    }
                },
                dismissButton = { TextButton(onClick = { pendingTermination = null }, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.cancel)) } },
            )
        }
        pendingRename?.let { session ->
            RenameSessionDialog(
                session = session,
                onRename = { title ->
                    pendingRename = null
                    renameSession(session.id, title)
                },
                onDismiss = { pendingRename = null },
            )
        }
    }

    private fun sendToolbarKey(key: TerminalToolbarKey) {
        if (!toolbarInputActive) return
        when (key) {
            is TerminalToolbarKey.Shortcut -> sendHotkeyShortcut(key.value)
            TerminalToolbarKey.Control -> {
                terminalInputCallbacks.controlPressed = !terminalInputCallbacks.controlPressed
                syncModifierState()
            }
            TerminalToolbarKey.Alt -> {
                terminalInputCallbacks.altPressed = !terminalInputCallbacks.altPressed
                syncModifierState()
            }
            is TerminalToolbarKey.CodePoint -> terminalView?.inputCodePoint(key.value, false, false)
            is TerminalToolbarKey.KeyCode -> terminalView?.let { view ->
                val now = SystemClock.uptimeMillis()
                view.onKeyDown(key.value, KeyEvent(now, now, KeyEvent.ACTION_DOWN, key.value, 0))
                terminalInputCallbacks.consumeLatchedModifiers()
            }
        }
    }

    private fun sendHotkeyShortcut(value: String) {
        terminalView?.let { dispatchHotkeyShortcut(it, terminalInputCallbacks, value) }
        syncModifierState()
    }

    private fun renderRuntimeState(state: String, detail: String) {
        runtimeMessage = when (state) {
            // A ready runtime is the normal idle state. Showing it in the empty
            // terminal screen adds redundant status copy beneath the call to action.
            "READY" -> ""
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
        pendingRename = pendingRename?.let { pending -> updated.firstOrNull { it.id == pending.id } }
        sessionExitCodes.keys.retainAll(ids)
        updated.forEach { it.exitCode?.let { code -> sessionExitCodes[it.id] = code } }

        if (rootlessInstallInFlight) {
            updated.firstOrNull { it.runtime == rootlessInstallRuntime && it.id !in rootlessSessionsBeforeInstall }?.let {
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
            applicationTerminalTitles = applicationTerminalTitles - id
            initializedRuntimeTerminals.remove(id)
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
                TerminalCallbacks(
                    redraw = {
                        terminalView?.takeIf { it.currentSession === terminalSessions[id] }?.onScreenUpdated()
                    },
                    titleChanged = { changed -> updateApplicationTerminalTitle(id, changed.getTitle()) },
                ),
                RemoteTerminalBackend(
                    write = { data -> safeRuntimeCall { it.writeSessionInput(id, data) } },
                    resize = { columns, rows -> safeRuntimeCall { it.resizeSession(id, rows, columns) } },
                    // A first layout may happen after attach() returns. Request the transcript
                    // only once the emulator has installed its output callback.
                    onStarted = { attachRuntimeWhenReady(id) },
                ).also { terminalBackends[id] = it },
            )
        }
        updateApplicationTerminalTitle(id, session.title)
        sessionExitCodes[id]?.let { terminalBackends[id]?.exit(it) }
        displayedSessionId = id
        terminalView?.let(::attachRenderer)
        attachRuntimeWhenReady(id)
    }

    private fun updateApplicationTerminalTitle(sessionId: String, title: String?) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post { updateApplicationTerminalTitle(sessionId, title) }
            return
        }
        val normalized = title.orEmpty()
        if (applicationTerminalTitles[sessionId] == normalized) return
        applicationTerminalTitles = applicationTerminalTitles + (sessionId to normalized)
    }

    private fun attachRuntimeWhenReady(id: String) {
        if (activeSessionId != id || terminalSessions[id]?.emulator == null) return
        val firstAttachment = initializedRuntimeTerminals.add(id)
        runtimeAttachment.select(id, replayTranscript = firstAttachment)?.let { selectedId ->
            safeRuntimeCall { it.attachSession(selectedId) }
        }
    }

    private fun attachRenderer(view: TerminalView) {
        val id = activeSessionId ?: return
        val session = terminalSessions[id] ?: return
        if (view.currentSession === session) return
        view.attachSession(session)
        applySessionFontSize(view)
        // A different shell must get a fresh IME connection/composition buffer.
        getSystemService(InputMethodManager::class.java).restartInput(view)
        requestTerminalKeyboard()
    }

    private fun requestTerminalKeyboard() {
        if (!activityStarted || activeSessionId == null) return
        keyboardRequested = true
        terminalView?.let { view ->
            view.removeCallbacks(showTerminalKeyboard)
            view.post(showTerminalKeyboard)
        }
    }

    private fun sessionFontSize(): Int {
        val defaults = terminalPreferences().getInt(
            SettingsDetailActivity.KEY_FONT_SIZE,
            TerminalAppearance.DEFAULT_FONT_SIZE_SP,
        )
        return sessionFontSizes.get(
            activeSessionId ?: return defaults.coerceIn(
                SessionFontSizes.MIN_FONT_SIZE_SP,
                SessionFontSizes.MAX_FONT_SIZE_SP,
            ),
            defaults,
        )
    }

    private val sessionFontSizes by lazy {
        SessionFontSizes(getSharedPreferences("session-font-sizes", MODE_PRIVATE))
    }

    private fun applySessionFontSize(view: TerminalView) {
        view.setTextSize(fontSizeSpToPx(sessionFontSize()) { value ->
            android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
                value, view.resources.displayMetrics)
        })
    }

    private fun applyScrollHapticSettings(view: TerminalView, preferences: android.content.SharedPreferences) {
        view.setScrollHapticFeedbackRows(TerminalHapticPreferences.read(preferences).scrollRowsPerTick())
    }

    private fun scaleTerminalFont(scale: Float): Float {
        val id = activeSessionId ?: return 1f
        val view = terminalView ?: return 1f
        return sessionFontSizes.scale(id, sessionFontSize(), scale) { applySessionFontSize(view) }
    }

    private fun toggleTerminalChrome() {
        if (activeSessionId != null) terminalAppBarContentVisible = !terminalAppBarContentVisible
    }

    private fun openTerminalWebLink(url: String): Boolean {
        if (!terminalPreferences().getBoolean(SettingsDetailActivity.KEY_OPEN_LINKS, false)) return false
        val uri = android.net.Uri.parse(url)
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return false
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: android.content.ActivityNotFoundException) {
            feedbackMessage = getString(R.string.terminal_link_no_browser)
        } catch (_: SecurityException) {
            feedbackMessage = getString(R.string.terminal_link_no_browser)
        }
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && keyboardRequested) requestTerminalKeyboard()
    }

    private val showTerminalKeyboard = Runnable {
        val view = terminalView
        if (keyboardRequested && activityStarted && view != null &&
            view.isAttachedToWindow && view.hasWindowFocus() && view.requestFocus()) {
            keyboardRequested = false
            WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.ime())
        }
    }

    private fun releaseTerminalFocus() {
        keyboardRequested = false
        terminalView?.let { view ->
            view.removeCallbacks(showTerminalKeyboard)
            view.clearFocus()
            WindowCompat.getInsetsController(window, view).hide(WindowInsetsCompat.Type.ime())
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

    private fun renameSession(id: String, title: String) {
        val validated = validatedSessionTitle(title) ?: return
        val service = runtime
        if (service == null) {
            feedbackMessage = getString(R.string.runtime_connecting_prompt)
            bindRuntime()
            return
        }
        runCatching { service.renameSession(id, validated) }
            .onFailure { handleRuntimeDisconnect() }
    }

    private fun refreshRuntimeChoicesForNewSession(): Boolean {
        val service = runtime
        if (service == null) {
            feedbackMessage = getString(R.string.runtime_connecting_prompt)
            bindRuntime()
            return false
        }
        updateRuntimeChoices(service.runtimeStatusJson)
        return true
    }

    private fun showNewSessionMenu() {
        if (!refreshRuntimeChoicesForNewSession()) return
        newSessionSheetVisible = false
        newSessionMenuVisible = true
    }

    private fun showNewSessionSheet() {
        if (!refreshRuntimeChoicesForNewSession()) return
        newSessionMenuVisible = false
        newSessionSheetVisible = true
    }

    private fun selectRuntime(choice: RuntimeChoiceUi) {
        newSessionMenuVisible = false
        newSessionSheetVisible = false
        if (choice.id in rootlessRuntimeIds && choice.state == "CHECKING") {
            rootlessInstallRuntime = choice.id
            showRootlessProgress(choice)
            rootlessVisible = true
            return
        }
        if (rootlessInstallInFlight && runtimeSelectionAction(choice) == RuntimeSelectionAction.INSTALL) {
            rootlessVisible = true
            return
        }
        when (runtimeSelectionAction(choice)) {
            RuntimeSelectionAction.CREATE -> createSession(choice.id)
            RuntimeSelectionAction.INSTALL,
            RuntimeSelectionAction.SETTINGS -> unavailableChoice = choice
        }
    }

    private fun openRuntimeSettings(runtimeType: String) {
        val category = when (runtimeType) {
            "AVF" -> SettingsDetailActivity.AVF
            in rootlessRuntimeIds -> SettingsDetailActivity.ROOTLESS
            else -> SettingsDetailActivity.NATIVE
        }
        startActivity(
            Intent(this, SettingsDetailActivity::class.java)
                .putExtra(SettingsDetailActivity.EXTRA_CATEGORY, category)
                .putExtra("rootless_runtime", runtimeType),
        )
    }

    private fun beginRootlessInstall() {
        if (rootlessInstallInFlight) return
        rootlessInstallInFlight = true
        rootlessRunning = true
        rootlessProgress = null
        rootlessSessionsBeforeInstall = sessions.mapTo(mutableSetOf()) { it.id }
        rootlessStatus = getString(R.string.rootless_installing, getString(runtimeTitleResource(rootlessInstallRuntime)))
        rootlessDetail = getString(R.string.rootless_install_detail)
        rootlessError = null
        createSession(rootlessInstallRuntime)
    }

    private fun createSession(runtimeType: String) = safeRuntimeCall { service ->
        val shell = sessionShell(runtimeType, defaultShell())
        val cwd = sessionInitialDirectory(runtimeType)
        service.createSession(runtimeType, shell, cwd, 24, 80)
    }

    private fun syncModifierState() {
        controlPressed = terminalInputCallbacks.controlPressed
        altPressed = terminalInputCallbacks.altPressed
    }

    private fun updateRuntimeChoices(json: String) {
        runtimeChoices = parseRuntimeChoices(json).map { choice ->
            if (choice.detail.isNotBlank()) choice else choice.copy(detail = defaultRuntimeDetail(choice.id))
        }
        runtimeChoices.firstOrNull { it.id == rootlessInstallRuntime }?.let(::showRootlessProgress)
    }

    private fun showRootlessProgress(choice: RuntimeChoiceUi) {
        rootlessProgress = choice.progress
        rootlessDetail = choice.detail
        rootlessRunning = choice.state == "CHECKING"
        choice.progress?.let {
            rootlessStatus = it.label
            rootlessError = choice.detail.takeIf { choice.state == "ERROR" }
        }
    }

    private fun defaultRuntimeChoices() = parseRuntimeChoices("").map {
        it.copy(detail = defaultRuntimeDetail(it.id))
    }

    private fun defaultRuntimeDetail(runtimeType: String) = when (runtimeType) {
        "ROOTLESS_ARCH" -> getString(R.string.runtime_arch_description)
        "ROOTLESS", "ROOTLESS_UBUNTU" -> getString(R.string.runtime_rootless_description)
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
        if (rootlessRunning || rootlessInstallInFlight) {
            rootlessRunning = false
            rootlessInstallInFlight = false
            rootlessError = getString(R.string.rootless_progress_disconnected)
        }
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

internal fun sessionShell(runtimeType: String, nativeShell: String): String = when (runtimeType) {
    "ROOTLESS", "ROOTLESS_UBUNTU", "ROOTLESS_ARCH", "AVF" -> "/bin/bash"
    else -> nativeShell
}

internal fun sessionInitialDirectory(runtimeType: String): String = when (runtimeType) {
    // An unspecified Native cwd is resolved to its app-private home by the runtime.
    "NATIVE" -> ""
    "ROOTLESS", "ROOTLESS_UBUNTU", "ROOTLESS_ARCH" -> "/root"
    "AVF" -> "/home/user"
    else -> "/"
}
