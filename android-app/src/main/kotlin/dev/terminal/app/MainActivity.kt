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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.termux.view.TerminalView
import dev.terminal.app.ui.TerminalToolbar
import dev.terminal.app.ui.ToolbarHotkeys
import dev.terminal.app.ui.ToolbarMemoryTool
import dev.terminal.app.ui.ToolbarCommandsTool
import androidx.compose.foundation.layout.BoxWithConstraints
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
import dev.terminal.app.ui.ToolbarSessionsTool
import dev.terminal.app.ui.TerminalEmptyState
import dev.terminal.app.ui.TerminalHapticPreferences
import dev.terminal.app.ui.TerminalToolbarKey
import dev.terminal.app.ui.rootlessRuntimeIds
import dev.terminal.app.ui.runtimeTitleResource
import dev.terminal.app.ui.parseRuntimeChoices
import dev.terminal.app.ui.KEY_SESSION_TAB_ORDER
import dev.terminal.app.ui.orderSessionTabs
import dev.terminal.app.ui.parseSessionTabOrder
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import dev.terminal.app.ui.ProfileInstallationState
import dev.terminal.app.ui.ProfileInstallationStatus
import dev.terminal.app.ui.ProfileInstallationDialog

/** Compose console projection. Runtime and shell ownership stay in :vmhost. */
class MainActivity : TerminalActivity() {
    private var sessions by mutableStateOf(emptyList<SessionUi>())
    private var runtimeSessionRows = emptyList<SessionUi>()
    private var unconfirmedAvfLaunches = emptyList<SessionUi>()
    private val submittedAvfLaunches = mutableSetOf<String>()
    private val installationApprovedAvfLaunches = mutableSetOf<String>()
    private val avfFocusHandoffs = mutableSetOf<String>()
    private var sessionTabOrder = emptyList<String>()
    private var profiles by mutableStateOf(emptyList<dev.terminal.runtime.session.SessionProfile>())
    private var profileEnvironmentNames by mutableStateOf(emptyMap<dev.terminal.runtime.session.EnvironmentId, String>())
    private var profileInstallationStatuses by mutableStateOf(emptyMap<dev.terminal.runtime.session.EnvironmentId, ProfileInstallationStatus>())
    private var profileLaunchToCheck by mutableStateOf<dev.terminal.runtime.session.SessionLaunchSpec?>(null)
    private var profileLaunchCheckRevision by mutableStateOf(0L)
    private var profileInstallToConfirm by mutableStateOf<dev.terminal.runtime.session.SessionLaunchSpec?>(null)
    private var profileInstallationToLaunch by mutableStateOf<dev.terminal.runtime.session.SessionLaunchSpec?>(null)
    private var rootlessLaunchSpec: dev.terminal.runtime.session.SessionLaunchSpec? = null
    private var selectedLaunchSpec: dev.terminal.runtime.session.SessionLaunchSpec? = null
    private fun addProfile() {
        sessionProfilePickerVisible = false
        newSessionSheetVisible = false
        startActivity(Intent(this, ProfileEditActivity::class.java))
    }
    private fun selectProfile(profile: dev.terminal.runtime.session.SessionProfile) {
        val spec = SessionProfiles(this).resolve(profile)
        selectedLaunchSpec = spec
        sessionProfilePickerVisible = false
        newSessionSheetVisible = false
        profileInstallToConfirm = null
        profileLaunchToCheck = null
        profileLaunchCheckRevision += 1
        if (spec.runtime == dev.terminal.runtime.session.SessionRuntime.NATIVE) createSession(spec)
        else if (rootlessInstallInFlight && rootlessLaunchSpec?.environmentId == spec.environmentId) rootlessVisible = true
        else profileLaunchToCheck = spec
    }

    private fun launchCheckedProfile(spec: dev.terminal.runtime.session.SessionLaunchSpec, status: ProfileInstallationStatus) {
        when (status.state) {
            ProfileInstallationState.MISSING -> profileInstallToConfirm = spec
            ProfileInstallationState.INSTALLED, ProfileInstallationState.INSTALLING -> {
                if (spec.runtime.wireName in rootlessRuntimeIds && !status.available) {
                    showRootlessProfilePreparation(spec, installationApproved = false)
                } else createSession(spec)
            }
            else -> feedbackMessage = getString(R.string.profile_installation_unknown)
        }
    }

    private fun approveProfileInstallation() {
        val spec = profileInstallToConfirm ?: return
        profileInstallToConfirm = null
        profileInstallationToLaunch = spec
    }

    private fun showRootlessProfilePreparation(spec: dev.terminal.runtime.session.SessionLaunchSpec, installationApproved: Boolean) {
        if (rootlessLaunchSpec?.environmentId != spec.environmentId) rootlessInstallInFlight = false
        rootlessLaunchSpec = spec
        rootlessInstallRuntime = spec.runtime.wireName
        rootlessVisible = true
        beginRootlessInstall(installationApproved)
    }
    private var runtimeChoices by mutableStateOf(emptyList<RuntimeChoiceUi>())
    private var notificationSessionId: String? = null
    private var activeSessionId by mutableStateOf<String?>(null)
    private var ignoreInitialRuntimeFocus = false
    private var runtimeMessage by mutableStateOf("")
    private var feedbackMessage by mutableStateOf<String?>(null)
    private var sessionProfilePickerVisible by mutableStateOf(false)
    private var newSessionSheetVisible by mutableStateOf(false)
    private var unavailableChoice by mutableStateOf<RuntimeChoiceUi?>(null)
    private var avfLaunchFailure by mutableStateOf<String?>(null)
    private var pendingRename by mutableStateOf<SessionUi?>(null)
    private var pendingTermination by mutableStateOf<SessionUi?>(null)
    private var terminatingSessionIds by mutableStateOf(emptySet<String>())
    private var rootlessVisible by mutableStateOf(false)
    private var rootlessRunning by mutableStateOf(false)
    private var rootlessCanCancel by mutableStateOf(false)
    private var rootlessCancelling by mutableStateOf(false)
    private var rootlessStatus by mutableStateOf("")
    private var rootlessProgress by mutableStateOf<RootlessProgressUi?>(null)
    private var avfImageInstallations by mutableStateOf(emptyList<dev.terminal.runtime.image.AvfImageInstallSnapshot>())
    private var rootlessDetail by mutableStateOf("")
    private var rootlessError by mutableStateOf<String?>(null)
    private var hotkeyLayout by mutableStateOf(HotkeyLayout())
    private var toolbarConfig by mutableStateOf(ToolbarConfig())
    private var toolbarCommands by mutableStateOf(emptyList<ToolbarCommand>())
    private var toolbarMemoryInterval by mutableStateOf(5)
    private var toolbarMemorySections by mutableStateOf(MemoryToolSections())
    private var controlPressed by mutableStateOf(false)
    private var altPressed by mutableStateOf(false)
    private var shiftPressed by mutableStateOf(false)
    private var terminalChromeColors by mutableStateOf(dev.terminal.app.ui.TerminalChromeColors(Color.Black))
    private var followTuiBackground = false
    private val paletteTransition = TerminalPaletteTransition(
        followTuiBackground = { followTuiBackground },
    ) { terminalChromeColors = it }
    private val updateTerminalChrome = object : Runnable {
        override fun run() {
            mainHandler.removeCallbacks(this)
            val view = terminalView ?: return
            val delay = view.currentSession?.emulator?.synchronizedOutputDelayMillis ?: return
            if (delay > 0) mainHandler.postDelayed(this, delay)
            else paletteTransition.update(view)
        }
    }
    private var paletteSessionId: String? = null
    private var paletteView: TerminalView? = null
    private var terminalPaddingDp by mutableStateOf(TerminalAppearance.DEFAULT_PADDING_DP)
    private var applicationTerminalTitles by mutableStateOf(emptyMap<String, String>())
    private var toolbarSheetMode by mutableStateOf(ToolbarSheetMode.Default)

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
    private val terminalStore by lazy { (application as TerminalApplication).terminalSessions }
    private val terminalSessions get() = terminalStore.sessions
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
            if (generation == bindingGeneration) {
                if (avfFocusHandoffs.remove(sessionId) && activeSessionId != sessionId) return@runOnUiThread
                if (ignoreInitialRuntimeFocus) ignoreInitialRuntimeFocus = false
                else attach(sessionId)
            }
        }
        override fun onSessionsChanged(revision: Long, sessionsJson: String) = runOnUiThread {
            if (generation == bindingGeneration) renderSessions(revision, sessionsJson)
        }

        override fun onTerminalOutput(sessionId: String, bytes: ByteArray, replay: Boolean) = Unit

        override fun onSessionExited(sessionId: String, exitCode: Int) = runOnUiThread {
            if (generation == bindingGeneration) {
                sessionExitCodes[sessionId] = exitCode
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
            val storageId = rootlessLaunchSpec?.storageId ?: "legacy"
            val progressKey = if (storageId == "legacy") rootlessInstallRuntime else "$rootlessInstallRuntime:$storageId"
            if (runtimeType != progressKey) return@runOnUiThread
            if (phase == dev.terminal.runtime.rootless.RootlessPhase.CANCELLED.displayName) {
                rootlessRunning = false
                rootlessCanCancel = false
                rootlessCancelling = false
                rootlessInstallInFlight = false
                rootlessStatus = getString(R.string.install_cancelled)
                rootlessDetail = detail
                rootlessError = null
                rootlessProgress = rootlessProgress?.copy(phase = "CANCELLED", label = rootlessStatus,
                    detail = detail, fraction = null, completedBytes = null, totalBytes = null,
                    finishedAtElapsedMs = SystemClock.elapsedRealtime())
                return@runOnUiThread
            }
            if (rootlessLaunchSpec?.storageId?.let { it != "legacy" } == true) {
                rootlessCanCancel = phase != dev.terminal.runtime.rootless.RootlessPhase.READY.displayName
                rootlessStatus = phase
                rootlessDetail = detail
                return@runOnUiThread
            }
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
            if (operation.substringBefore(':') == "세션 생성" &&
                operation.substringAfter(':', "").substringBefore(':') == "AVF") {
                avfLaunchFailure = message
            }
            if ((rootlessInstallInFlight || rootlessVisible) && operation.substringAfter(':', "").substringBefore(':') == rootlessInstallRuntime &&
                operation.substringAfter(':', "").substringAfter(':', "legacy") == (rootlessLaunchSpec?.storageId ?: "legacy")) {
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
            runtimeRetry.reset()
            runtime = IRuntimeService.Stub.asInterface(service)
            bound = true
            runCatching {
                val connected = checkNotNull(runtime)
                val currentListener = checkNotNull(listener)
                val notificationTarget = notificationSessionId
                ignoreInitialRuntimeFocus = notificationTarget == null && activeSessionId != null
                val connectedGeneration = bindingGeneration
                connected.registerListener(currentListener)
                if (ignoreInitialRuntimeFocus) mainHandler.post { ignoreInitialRuntimeFocus = false }
                // Registration also replays the service's last focused tab. An explicit
                // notification target wins after those initial callbacks have drained.
                if (notificationTarget != null) mainHandler.post {
                    if (connectedGeneration == bindingGeneration && sessions.any { it.id == notificationTarget }) {
                        notificationSessionId = null
                        attach(notificationTarget)
                    }
                }
                updateRuntimeChoices(connected.runtimeStatusJson)
                renderRuntimeState(connected.state, connected.detail)
                connected.startRuntime()
                submitPendingAvfLaunches()
            }.onFailure { handleRuntimeDisconnect() }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            handleRuntimeDisconnect()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("terminal-toolbar-mode", toolbarSheetMode.name)
        activeSessionId?.let { outState.putString("active-session", it) }
        selectedLaunchSpec?.let { outState.putString("pending-profile-launch", it.toJson().toString()) }
        rootlessLaunchSpec?.let { outState.putString("pending-rootless-profile-launch", it.toJson().toString()) }
        profileLaunchToCheck?.let { outState.putString("profile-installation-check", it.toJson().toString()) }
        profileInstallToConfirm?.let { outState.putString("profile-installation-confirm", it.toJson().toString()) }
        profileInstallationToLaunch?.let { outState.putString("profile-installation-approved", it.toJson().toString()) }
        notificationSessionId?.let { outState.putString("notification-target", it) }
        outState.putString("unconfirmed-avf-launches", org.json.JSONArray().apply {
            unconfirmedAvfLaunches.forEach { launch ->
                put(org.json.JSONObject().put("requestId", launch.launchRequestId).put("spec", launch.launchSpec?.toJson())
                    .put("error", launch.launchError ?: org.json.JSONObject.NULL))
            }
        }.toString())
        outState.putStringArrayList("submitted-avf-launches", ArrayList(submittedAvfLaunches))
        outState.putStringArrayList("installation-approved-avf-launches", ArrayList(installationApprovedAvfLaunches))
        super.onSaveInstanceState(outState)
    }

    private fun observeTerminalSessions() {
        terminalStore.observe(this) { id ->
            val session = terminalSessions[id]
            if (session != null) {
                updateApplicationTerminalTitle(id, session.title)
                terminalView?.takeIf { it.currentSession === session }?.let { view ->
                    terminalStore.restoreViewport(view)
                    view.onScreenUpdated()
                    mainHandler.removeCallbacks(updateTerminalChrome)
                    updateTerminalChrome.run()
                }
            } else if (id == activeSessionId) {
                displayedSessionId = null
                runtimeAttachment.reset()
                attach(id)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dev.terminal.runtime.image.AvfImageInstallMonitor(this).observe(this) { avfImageInstallations = it }
        sessionTabOrder = parseSessionTabOrder(
            getSharedPreferences("terminal-ui-state", MODE_PRIVATE).getString(KEY_SESSION_TAB_ORDER, null))
        toolbarSheetMode = savedInstanceState?.getString("terminal-toolbar-mode")?.let { name ->
            ToolbarSheetMode.entries.firstOrNull { it.name == name }
        } ?: if (savedInstanceState?.getBoolean("terminal-extra-keys-visible", true) == false)
            ToolbarSheetMode.Hidden else ToolbarSheetMode.Default
        notificationSessionId = savedInstanceState?.getString("notification-target")
        activeSessionId = savedInstanceState?.getString("active-session")
            ?: getSharedPreferences("terminal-ui-state", MODE_PRIVATE).getString("active-session", null)
        selectedLaunchSpec = savedInstanceState?.getString("pending-profile-launch")?.let {
            dev.terminal.runtime.session.SessionLaunchSpec.fromJson(it)
        }
        rootlessLaunchSpec = savedInstanceState?.getString("pending-rootless-profile-launch")?.let {
            dev.terminal.runtime.session.SessionLaunchSpec.fromJson(it)
        }
        profileLaunchToCheck = savedInstanceState?.getString("profile-installation-check")?.let {
            dev.terminal.runtime.session.SessionLaunchSpec.fromJson(it)
        }
        profileInstallToConfirm = savedInstanceState?.getString("profile-installation-confirm")?.let {
            dev.terminal.runtime.session.SessionLaunchSpec.fromJson(it)
        }
        profileInstallationToLaunch = savedInstanceState?.getString("profile-installation-approved")?.let {
            dev.terminal.runtime.session.SessionLaunchSpec.fromJson(it)
        }
        unconfirmedAvfLaunches = runCatching {
            val requests = org.json.JSONArray(savedInstanceState?.getString("unconfirmed-avf-launches") ?: "[]")
            (0 until requests.length()).map { index ->
                val request = requests.getJSONObject(index)
                val spec = dev.terminal.runtime.session.SessionLaunchSpec.fromJson(request.getJSONObject("spec").toString())
                val error = request.takeIf { !it.isNull("error") }?.getString("error")
                dev.terminal.app.ui.avfLaunchTab(request.getString("requestId"), spec)
                    .let { if (error == null) it else it.copy(state = "LAUNCH_FAILED", launchError = error) }
            }
        }.getOrDefault(emptyList())
        submittedAvfLaunches += savedInstanceState?.getStringArrayList("submitted-avf-launches").orEmpty()
        installationApprovedAvfLaunches += savedInstanceState?.getStringArrayList("installation-approved-avf-launches").orEmpty()
        sessions = orderSessionTabs(unconfirmedAvfLaunches, sessionTabOrder)
        runtimeMessage = getString(R.string.runtime_connecting_prompt)
        rootlessStatus = getString(R.string.rootless_setup_required)
        rootlessDetail = getString(R.string.rootless_setup_initial)
        runtimeChoices = defaultRuntimeChoices()
        terminalChromeColors = dev.terminal.app.ui.TerminalChromeColors(Color(TerminalAppearance.backgroundColor(terminalPreferences())))
        terminalPaddingDp = TerminalAppearance.paddingDp(terminalPreferences())
        terminalInputCallbacks = TerminalInputCallbacks(
            onModifierStateChanged = ::syncModifierState,
            onShowKeyboard = ::requestTerminalKeyboard,
            onPinchScale = ::scaleTerminalFont,
            onWebLink = ::openTerminalWebLink,
        )
        compose(systemBarBackground = { if (activeSessionId != null) terminalChromeColors.background else null }) { TerminalScreen() }
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
        observeTerminalSessions()
        activityStarted = true
        runtimeRetry.reset()
        mainHandler.removeCallbacks(reconnectRuntime)
        bindRuntime()
    }

    override fun onResume() {
        super.onResume()
        toolbarInputActive = true
        val preferences = terminalPreferences()
        followTuiBackground = preferences.getBoolean(TerminalAppearance.KEY_FOLLOW_TUI_BACKGROUND, false)
        hotkeyLayout = readHotkeyLayout(preferences)
        toolbarConfig = readToolbarConfig(preferences)
        toolbarCommands = readToolbarCommands(preferences)
        toolbarMemoryInterval = preferences.getInt("toolbar-memory-interval", 5)
        toolbarMemorySections = readMemoryToolSections(preferences)
        refreshProfiles()
        terminalPaddingDp = TerminalAppearance.paddingDp(preferences)
        terminalView?.let {
            paletteTransition.capture(it)
            TerminalAppearance.apply(preferences, it, sessionFontSize(), applyPalette = false)
            updateTerminalPalette(it)
            updateTerminalChrome.run()
            terminalSessions.filterKeys { id -> id != activeSessionId }.forEach { (id, session) ->
                val palette = sessionPalette(id).asProperties()
                session.emulator?.mColors?.setDefaultColors(palette)
                terminalStore.setPalette(id, palette)
            }
            applyScrollSettings(it, preferences)
            it.refresh()
        }
    }

    override fun onPause() {
        toolbarInputActive = false
        super.onPause()
    }

    override fun onStop() {
        activityStarted = false
        keyboardRequested = false
        terminalView?.removeCallbacks(showTerminalKeyboard)
        mainHandler.removeCallbacks(reconnectRuntime)
        terminalView?.saveSessionScrollPosition()
        terminalStore.unobserve(this)
        // The store keeps the mirror for a short grace period; runtime ownership is unchanged.
        if (bindingRequested) {
            listener?.let { current -> runCatching { runtime?.unregisterListener(current) } }
            runCatching { unbindService(connection) }
        }
        bindingGeneration++
        bindingRequested = false
        bound = false
        runtime = null
        listener = null
        runtimeAttachment.reset()
        super.onStop()
    }

    override fun onDestroy() {
        // Release only this screen's UI resources.
        terminalView?.saveSessionScrollPosition()
        terminalStore.unobserve(this)
        paletteTransition.cancel()
        mainHandler.removeCallbacks(updateTerminalChrome)
        paletteView = null
        bindingGeneration++
        terminalView?.removeCallbacks(showTerminalKeyboard)
        mainHandler.removeCallbacks(reconnectRuntime)
        if (bindingRequested) {
            if (bound) {
                listener?.let { current -> runCatching { runtime?.unregisterListener(current) } }
            }
            runCatching { unbindService(connection) }
        }
        runtimeAttachment.reset()
        applicationTerminalTitles = emptyMap()
        displayedSessionId = null
        bindingRequested = false
        bound = false
        runtime = null
        listener = null
        super.onDestroy()
    }

    @Composable
    private fun TerminalScreen() {
        val edgeToEdgeTerminal = activeSessionId != null
        val topInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top)
        val terminalTopInset = if (edgeToEdgeTerminal) topInsets.getTop(LocalDensity.current) else 0
        val screenBackgroundColor = if (activeSessionId == null) {
            MaterialTheme.colorScheme.background
        } else {
            terminalChromeColors.background
        }
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
        val profileMenuVisible = sessionProfilePickerVisible || newSessionSheetVisible
        LaunchedEffect(profileMenuVisible, profiles) {
            if (!profileMenuVisible) return@LaunchedEffect
            val environmentIds = profiles.filter { it.runtime != dev.terminal.runtime.session.SessionRuntime.NATIVE }
                .mapNotNull { it.environmentId }.distinct()
            while (isActive) {
                runtime?.takeIf { activityStarted }?.let { service ->
                    val statuses = withContext(Dispatchers.IO) {
                        environmentIds.associateWith { id ->
                            runCatching { ProfileInstallationStatus.fromJson(service.getEnvironmentSoftwareJson(id.value)) }
                                .getOrElse { ProfileInstallationStatus(ProfileInstallationState.UNKNOWN) }
                        }
                    }
                    profileInstallationStatuses = statuses
                }
                delay(2_000)
            }
        }
        LaunchedEffect(profileLaunchToCheck, profileLaunchCheckRevision) {
            val spec = profileLaunchToCheck ?: return@LaunchedEffect
            val revision = profileLaunchCheckRevision
            while (runtime == null) {
                if (activityStarted) bindRuntime()
                delay(200)
            }
            val service = checkNotNull(runtime)
            val status = withContext(Dispatchers.IO) {
                runCatching { ProfileInstallationStatus.fromJson(service.getEnvironmentSoftwareJson(checkNotNull(spec.environmentId).value)) }
                    .getOrElse { ProfileInstallationStatus(ProfileInstallationState.UNKNOWN) }
            }
            if (profileLaunchToCheck != spec || profileLaunchCheckRevision != revision) return@LaunchedEffect
            profileLaunchToCheck = null
            profileInstallationStatuses = profileInstallationStatuses + (checkNotNull(spec.environmentId) to status)
            launchCheckedProfile(spec, status)
        }
        LaunchedEffect(profileInstallationToLaunch) {
            val spec = profileInstallationToLaunch ?: return@LaunchedEffect
            while (runtime == null) {
                if (activityStarted) bindRuntime()
                delay(200)
            }
            profileInstallationToLaunch = null
            if (spec.runtime.wireName in rootlessRuntimeIds) showRootlessProfilePreparation(spec, installationApproved = true)
            else createSession(spec, installationApproved = true)
        }
        Scaffold(
            containerColor = screenBackgroundColor,
            snackbarHost = { SnackbarHost(snackbarHost) },
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        ) { scaffoldPadding -> Box(Modifier.fillMaxSize().padding(scaffoldPadding).imePadding()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val toolbarMaxHeight = (maxHeight * 0.6f).coerceAtMost(480.dp)
                Column(Modifier.fillMaxSize()) {
                    dev.terminal.app.ui.AvfImageInstallationUi(avfImageInstallations,
                        Modifier.windowInsetsPadding(topInsets))
                    if (activeSessionId == null) {
                        Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(topInsets)) {
                            TerminalEmptyState(runtimeMessage, ::showNewSessionSheet,
                                onSettings = { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) })
                        }
                    } else if (sessions.firstOrNull { it.id == activeSessionId }?.pendingLaunch == true) {
                        val loadingSession = sessions.first { it.id == activeSessionId }
                        dev.terminal.app.ui.AvfSessionLaunchPane(loadingSession,
                            Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(topInsets),
                            onDismiss = { terminate(loadingSession.id) })
                    } else {
                        AndroidView(
                            factory = { context ->
                                TerminalView(context, null).apply {
                                    setTerminalGridBottomAligned(true)
                                    isFocusable = true
                                    isFocusableInTouchMode = true
                                    terminalView = this
                                    setTerminalViewClient(terminalInputCallbacks)
                                    applyScrollSettings(this, terminalPreferences())
                                    TerminalAppearance.apply(terminalPreferences(), this, applyPalette = false)
                                }
                            },
                            update = { view ->
                                view.setScrollbackTopInset(terminalTopInset)
                                attachRenderer(view)
                            },
                            onRelease = { view ->
                                view.saveSessionScrollPosition()
                                view.removeCallbacks(showTerminalKeyboard)
                                if (terminalView === view) {
                                    paletteTransition.cancel()
                                    mainHandler.removeCallbacks(updateTerminalChrome)
                                    paletteView = null
                                    terminalView = null
                                }
                            },
                            modifier = Modifier.weight(1f).fillMaxWidth()
                                .clipToBounds()
                                .background(terminalChromeColors.background)
                                .padding(terminalPaddingDp.dp),
                        )
                    }
                    if (visibleSessions.isNotEmpty()) {
                        val toolbarSessionId = activeSessionId
                        TerminalToolbar(
                            config = toolbarConfig.visibleConfig(),
                            mode = toolbarSheetMode,
                            onModeChange = {
                                toolbarSheetMode = it
                                if (it == ToolbarSheetMode.Hidden) sessionProfilePickerVisible = false
                            },
                            maxHeight = toolbarMaxHeight,
                            colors = if (activeSessionId == null) dev.terminal.app.ui.TerminalChromeColors(screenBackgroundColor) else terminalChromeColors,
                            pageNavigationEnabled = !sessionProfilePickerVisible,
                        ) { tool, interactive ->
                            // Session navigation keeps its scroll position across tab switches.
                            androidx.compose.runtime.key(if (tool == SESSIONS_TOOL) SESSIONS_TOOL else toolbarSessionId, tool) {
                                val active = toolbarInputActive && interactive
                                val selected = visibleSessions.firstOrNull { it.id == toolbarSessionId }
                                val canSend = active && selected?.state == "RUNNING"
                                val sendText: (String, Boolean) -> Unit = { text, execute ->
                                    if (activeSessionId == toolbarSessionId && toolbarInputActive &&
                                        toolbarConfig.enabled && tool in toolbarConfig.tools &&
                                        toolbarSheetMode != ToolbarSheetMode.Hidden && text.length <= MAX_TOOLBAR_TEXT &&
                                        selected?.state == "RUNNING") {
                                        terminalView?.pasteText(text)
                                        if (execute) {
                                            terminalInputCallbacks.consumeLatchedModifiers()
                                            terminalView?.inputCodePoint(13, false, false)
                                        }
                                    }
                                }
                                when (tool) {
                                    SESSIONS_TOOL -> ToolbarSessionsTool(
                                        sessions = visibleSessions, activeSessionId = activeSessionId, active = active,
                                        terminatingSessionIds = terminatingSessionIds, profiles = profiles,
                                        onSelectProfile = { profile ->
                                            selectProfile(profile)
                                            toolbarSheetMode = ToolbarSheetMode.Hidden
                                        }, onAddProfile = {
                                            toolbarSheetMode = ToolbarSheetMode.Hidden
                                            addProfile()
                                        },
                                        profileEnvironmentNames = profileEnvironmentNames,
                                        profileInstallationStatuses = profileInstallationStatuses,
                                        profilePickerVisible = sessionProfilePickerVisible,
                                        onSelect = { id ->
                                            attach(id)
                                            if (sessions.none { it.id == id && it.pendingLaunch }) requestTerminalKeyboard()
                                        },
                                        onRename = { pendingRename = it }, onTerminate = {
                                            if (it.pendingLaunch || terminalPreferences().getBoolean(KEY_SKIP_SESSION_CLOSE_CONFIRMATION, false)) {
                                                terminate(it.id)
                                            } else {
                                                pendingTermination = it
                                            }
                                        },
                                        onNew = ::showSessionProfilePicker,
                                        onDismissProfilePicker = { sessionProfilePickerVisible = false },
                                        onSettings = { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) },
                                        onReorder = ::reorderSessionTabs,
                                    )
                                    MEMORY_TOOL -> ToolbarMemoryTool(active, visibleSessions, toolbarMemoryInterval,
                                        onSelectSession = { id ->
                                            attach(id)
                                            if (sessions.none { it.id == id && it.pendingLaunch }) requestTerminalKeyboard()
                                        }, sections = toolbarMemorySections) {
                                        runtime?.sessionMemoryJson?.let { raw ->
                                            org.json.JSONObject(raw).apply {
                                                if (toolbarMemorySections.app) put("appMemory", combineAppMemory(
                                                    dev.terminal.runtime.session.currentAppProcessMemory(),
                                                    optJSONObject("runtimeAppMemory")) ?: org.json.JSONObject.NULL)
                                            }.toString()
                                        }
                                    }
                                    COMMANDS_TOOL -> ToolbarCommandsTool(toolbarCommands, canSend, selected?.title.orEmpty(),
                                        onSend = sendText, onSave = { command ->
                                            val preferences = terminalPreferences()
                                            toolbarCommands = readToolbarCommands(preferences).map {
                                                if (it.id == command.id) command else it
                                            }
                                            preferences.edit().putString(KEY_TOOLBAR_COMMANDS,
                                                serializeToolbarCommands(toolbarCommands)).apply()
                                        })
                                    HOTKEY_TOOL -> ToolbarHotkeys(
                                        layout = hotkeyLayout, control = controlPressed, alt = altPressed, shift = shiftPressed,
                                        colors = if (activeSessionId == null) dev.terminal.app.ui.TerminalChromeColors(screenBackgroundColor) else terminalChromeColors,
                                        enabled = toolbarInputActive && interactive && selected?.pendingLaunch != true,
                                        onKey = { key ->
                                            if (activeSessionId == toolbarSessionId) sendToolbarKey(key)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (edgeToEdgeTerminal) {
                Spacer(
                    Modifier.fillMaxWidth().windowInsetsTopHeight(topInsets)
                        .background(Brush.verticalGradient(listOf(
                            terminalChromeColors.background,
                            terminalChromeColors.background.copy(alpha = 0.6f),
                            terminalChromeColors.background.copy(alpha = 0f),
                        ))),
                )
            }
        } }

        if (newSessionSheetVisible) {
            NewSessionSheet(
                profiles = profiles,
                onSelectProfile = ::selectProfile,
                onAddProfile = ::addProfile,
                onDismiss = { newSessionSheetVisible = false },
                environmentNames = profileEnvironmentNames,
                installationStatuses = profileInstallationStatuses,
            )
        }
        profileInstallToConfirm?.let { spec ->
            ProfileInstallationDialog(spec, ::approveProfileInstallation, onDismiss = { profileInstallToConfirm = null })
        }
        if (rootlessVisible) {
            RootlessSetupDialog(rootlessInstallRuntime, rootlessStatus, rootlessDetail, rootlessRunning, rootlessError,
                { beginRootlessInstall(installationApproved = true) }, rootlessProgress,
                onCancelInstall = if (rootlessCanCancel || rootlessCancelling) ::cancelRootlessInstall else null,
                cancelling = rootlessCancelling, cancelled = rootlessStatus == getString(R.string.install_cancelled)) {
                rootlessVisible = false
            }
        }
        unavailableChoice?.let { choice ->
            RuntimeUnavailableDialog(
                choice = choice,
                onInstall = {
                    unavailableChoice = null
                    rootlessInstallRuntime = choice.id
                    rootlessLaunchSpec = selectedLaunchSpec
                    rootlessVisible = true
                    rootlessStatus = getString(R.string.rootless_setup_required)
                    rootlessDetail = choice.detail.ifBlank { getString(R.string.rootless_setup_initial) }
                    beginRootlessInstall(installationApproved = true)
                },
                onOpenSettings = {
                    unavailableChoice = null
                    openRuntimeSettings(choice.id)
                },
                onDismiss = { unavailableChoice = null },
            )
        }
        avfLaunchFailure?.let { message ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { avfLaunchFailure = null },
                title = { Text(getString(R.string.avf_session_start_failed)) },
                text = { Text(message) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { avfLaunchFailure = null }) {
                        Text(getString(R.string.close))
                    }
                },
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
        if (sessions.any { it.id == activeSessionId && it.pendingLaunch }) return
        if (!toolbarInputActive || !toolbarConfig.enabled || HOTKEY_TOOL !in toolbarConfig.tools ||
            toolbarSheetMode == ToolbarSheetMode.Hidden) return
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
            TerminalToolbarKey.Shift -> {
                terminalInputCallbacks.shiftPressed = !terminalInputCallbacks.shiftPressed
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
        runtimeSessionRows = parseSessions(json)
        val acknowledged = runtimeSessionRows.mapNotNull { it.launchRequestId }.toSet()
        unconfirmedAvfLaunches = unconfirmedAvfLaunches.filter { it.launchRequestId !in acknowledged }
        submittedAvfLaunches.retainAll(unconfirmedAvfLaunches.mapNotNull { it.launchRequestId }.toSet())
        renderSessionProjection()
    }

    private fun renderSessionProjection() {
        val combined = runtimeSessionRows + unconfirmedAvfLaunches
        val replacements = dev.terminal.app.ui.avfLaunchTabReplacements(combined)
        avfFocusHandoffs += sessions.filter { it.pendingLaunch }.mapNotNull { replacements[it.id] }
        sessionTabOrder = sessionTabOrder.map { replacements[it] ?: it }
        activeSessionId = replacements[activeSessionId] ?: activeSessionId
        val updated = orderSessionTabs(combined, sessionTabOrder)
        sessionTabOrder = updated.map { it.id }
        val tabUpdate = reconcileSessionTabs(sessions, activeSessionId, terminatingSessionIds, updated)
        val removedSessionIds = tabUpdate.removedSessionIds
        sessions = updated
        activeSessionId = tabUpdate.activeSessionId
        notificationSessionId?.let { requested ->
            if (updated.any { it.id == requested }) {
                activeSessionId = requested
                notificationSessionId = null
            } else if (lastSessionRevision > 0) {
                notificationSessionId = null
                feedbackMessage = getString(R.string.session_task_session_missing)
            }
        }
        terminatingSessionIds = tabUpdate.pendingTerminationIds
        val ids = updated.mapTo(mutableSetOf()) { it.id }
        pendingRename = pendingRename?.let { pending -> updated.firstOrNull { it.id == pending.id } }
        sessionExitCodes.keys.retainAll(ids)
        updated.forEach { it.exitCode?.let { code -> sessionExitCodes[it.id] = code } }

        if (rootlessInstallInFlight) {
            updated.firstOrNull { it.runtime == rootlessInstallRuntime &&
                (it.launchSpec?.storageId ?: "legacy") == (rootlessLaunchSpec?.storageId ?: "legacy") &&
                it.id !in rootlessSessionsBeforeInstall }?.let {
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
        getSharedPreferences("terminal-ui-state", MODE_PRIVATE).edit()
            .putString("active-session", activeSessionId)
            .putString(KEY_SESSION_TAB_ORDER, org.json.JSONArray(sessionTabOrder).toString()).apply()
        removedSessionIds.forEach { id ->
            terminalStore.remove(id)
            applicationTerminalTitles = applicationTerminalTitles - id
            runtimeAttachment.forget(id)
            if (displayedSessionId == id) displayedSessionId = null
        }
        runtime?.let { service -> runCatching { updateRuntimeChoices(service.runtimeStatusJson) } }
    }

    private fun attach(id: String) {
        val selected = sessions.firstOrNull { it.id == id } ?: return
        paletteTransition.capture(terminalView)
        activeSessionId = id
        getSharedPreferences("terminal-ui-state", MODE_PRIVATE).edit().putString("active-session", id).apply()
        if (selected.pendingLaunch) {
            runtimeAttachment.reset()
            displayedSessionId = null
            releaseTerminalFocus()
            return
        }
        val session = terminalStore.getOrCreate(id, terminalPreferences().getInt(SettingsDetailActivity.KEY_SCROLLBACK, 10_000),
            palette = sessionPalette(id).asProperties())
        updateApplicationTerminalTitle(id, session.title)
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
        terminalView?.let(::updateTerminalPalette)
        runtimeAttachment.select(id, replayTranscript = false)
        terminalStore.attachWhenReady(id)
    }

    private fun attachRenderer(view: TerminalView) {
        view.setSessionScrollPositions(terminalStore.scrollPositions)
        view.setScrollPositionListener { session, position -> terminalStore.saveViewport(session, position) }
        val id = activeSessionId ?: return
        val session = terminalSessions[id] ?: return
        if (view.currentSession === session) {
            updateTerminalPalette(view)
            return
        }
        view.attachSession(session)
        applySessionFontSize(view)
        updateTerminalPalette(view)
        // A different shell must get a fresh IME connection/composition buffer.
        getSystemService(InputMethodManager::class.java).restartInput(view)
        requestTerminalKeyboard()
    }

    private fun sessionPalette(id: String): TerminalAppearance.PaletteColors {
        val session = sessions.firstOrNull { it.id == id }
        val profile = profiles.firstOrNull { it.id == session?.profileId } ?: session?.launchSpec?.toProfile()
        return TerminalAppearance.profilePalette(terminalPreferences(), profile)
    }

    private fun updateTerminalPalette(view: TerminalView) {
        val id = activeSessionId ?: return
        val session = terminalSessions[id] ?: return
        if (view.currentSession !== session) return
        val colors = session.emulator?.mColors ?: return
        val palette = sessionPalette(id).asProperties()
        val changed = colors.setDefaultColors(palette)
        terminalStore.setPalette(id, palette)
        if (paletteSessionId != id || paletteView !== view || changed) {
            val animate = paletteView === view && paletteSessionId != null
            paletteSessionId = id
            paletteView = view
            paletteTransition.show(view, animate)
            if (session.emulator.synchronizedOutputDelayMillis > 0) updateTerminalChrome.run()
        }
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

    private fun applyScrollSettings(view: TerminalView, preferences: android.content.SharedPreferences) {
        view.setSmoothScrollingEnabled(preferences.getBoolean(SettingsDetailActivity.KEY_SMOOTH_SCROLLING, false))
        view.setScrollHapticFeedbackRows(TerminalHapticPreferences.read(preferences).scrollRowsPerTick())
    }

    private fun scaleTerminalFont(scale: Float): Float {
        val id = activeSessionId ?: return 1f
        val view = terminalView ?: return 1f
        return sessionFontSizes.scale(id, sessionFontSize(), scale) { applySessionFontSize(view) }
    }

    private fun reorderSessionTabs(order: List<String>) {
        // A runtime update may remove/add a tab while a drag is finishing.
        sessions = orderSessionTabs(sessions, order)
        sessionTabOrder = sessions.map { it.id }
        getSharedPreferences("terminal-ui-state", MODE_PRIVATE).edit()
            .putString(KEY_SESSION_TAB_ORDER, org.json.JSONArray(sessionTabOrder).toString()).apply()
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
        val pending = sessions.firstOrNull { it.id == id && it.pendingLaunch }
        if (pending != null && pending.launchError == null) return
        if (unconfirmedAvfLaunches.any { it.id == id }) {
            unconfirmedAvfLaunches.first { it.id == id }.launchRequestId?.let(submittedAvfLaunches::remove)
            unconfirmedAvfLaunches = unconfirmedAvfLaunches.filterNot { it.id == id }
            renderSessionProjection()
            return
        }
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

    private fun refreshProfiles() {
        val store = SessionProfiles(this)
        profiles = store.all()
        profileEnvironmentNames = store.environments().associate { it.id to it.name }
    }

    private fun refreshRuntimeChoicesForNewSession(): Boolean {
        refreshProfiles()
        val service = runtime
        if (service == null) {
            feedbackMessage = getString(R.string.runtime_connecting_prompt)
            bindRuntime()
            return false
        }
        updateRuntimeChoices(service.runtimeStatusJson)
        return true
    }

    private fun showSessionProfilePicker() {
        if (!refreshRuntimeChoicesForNewSession()) return
        newSessionSheetVisible = false
        sessionProfilePickerVisible = true
        terminalView?.let { view ->
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
        }
        toolbarSheetMode = ToolbarSheetMode.Expanded
    }

    private fun showNewSessionSheet() {
        if (!refreshRuntimeChoicesForNewSession()) return
        sessionProfilePickerVisible = false
        newSessionSheetVisible = true
    }

    private fun selectRuntime(choice: RuntimeChoiceUi) {
        sessionProfilePickerVisible = false
        newSessionSheetVisible = false
        if (choice.id in rootlessRuntimeIds && choice.state == "CHECKING") {
            rootlessLaunchSpec = selectedLaunchSpec
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
            RuntimeSelectionAction.CREATE -> createSession(selectedLaunchSpec ?: builtInLaunchSpec(choice.id))
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

    private fun beginRootlessInstall(installationApproved: Boolean = false) {
        if (rootlessInstallInFlight) return
        rootlessInstallInFlight = true
        rootlessRunning = true
        rootlessCanCancel = false
        rootlessCancelling = false
        rootlessProgress = null
        rootlessSessionsBeforeInstall = sessions.mapTo(mutableSetOf()) { it.id }
        rootlessStatus = getString(R.string.rootless_installing, getString(runtimeTitleResource(rootlessInstallRuntime)))
        rootlessDetail = getString(R.string.rootless_install_detail)
        rootlessError = null
        createSession(rootlessLaunchSpec ?: builtInLaunchSpec(rootlessInstallRuntime), installationApproved)
    }

    private fun cancelRootlessInstall() {
        if (rootlessCancelling) return
        safeRuntimeCall { service ->
            rootlessCancelling = service.cancelRootlessInstallation(rootlessInstallRuntime,
                rootlessLaunchSpec?.storageId ?: "legacy")
        }
    }

    private fun builtInLaunchSpec(runtimeType: String) =
        SessionProfiles(this).let { store -> store.resolve(store.all().firstOrNull { it.id == dev.terminal.runtime.session.SessionProfile.builtInId(requireNotNull(dev.terminal.runtime.session.SessionRuntime.fromWireName(runtimeType))) }
            ?: store.builtIns().first { it.runtime.wireName == runtimeType }) }

    private fun createSession(spec: dev.terminal.runtime.session.SessionLaunchSpec, installationApproved: Boolean = false) {
        if (spec.runtime == dev.terminal.runtime.session.SessionRuntime.AVF) {
            avfLaunchFailure = null
            val requestId = UUID.randomUUID().toString()
            if (installationApproved) installationApprovedAvfLaunches += requestId
            val launch = dev.terminal.app.ui.avfLaunchTab(requestId, spec)
            unconfirmedAvfLaunches = unconfirmedAvfLaunches + launch
            activeSessionId = launch.id
            renderSessionProjection()
            submitPendingAvfLaunches()
        } else {
            safeRuntimeCall { service ->
                if (installationApproved) service.createSessionFromSpecWithInstallationConsent(spec.toJson().toString(), UUID.randomUUID().toString(), 24, 80)
                else service.createSessionFromSpec(spec.toJson().toString(), 24, 80)
            }
        }
    }

    private fun submitPendingAvfLaunches() = safeRuntimeCall { service ->
        unconfirmedAvfLaunches.filter { it.launchError == null && it.launchRequestId !in submittedAvfLaunches }
            .forEach { launch ->
                val requestId = checkNotNull(launch.launchRequestId)
                val encoded = checkNotNull(launch.launchSpec).toJson().toString()
                if (requestId in installationApprovedAvfLaunches) service.createSessionFromSpecWithInstallationConsent(encoded, requestId, 24, 80)
                else service.createSessionFromSpecWithRequestId(encoded, requestId, 24, 80)
                submittedAvfLaunches += requestId
                installationApprovedAvfLaunches -= requestId
            }
    }

    private fun syncModifierState() {
        controlPressed = terminalInputCallbacks.controlPressed
        altPressed = terminalInputCallbacks.altPressed
        shiftPressed = terminalInputCallbacks.shiftPressed
    }

    private fun updateRuntimeChoices(json: String) {
        runtimeChoices = parseRuntimeChoices(json).map { choice ->
            if (choice.detail.isNotBlank()) choice else choice.copy(detail = defaultRuntimeDetail(choice.id))
        }
        if (rootlessLaunchSpec?.storageId?.let { it != "legacy" } != true) {
            runtimeChoices.firstOrNull { it.id == rootlessInstallRuntime }?.let(::showRootlessProgress)
        }
    }

    private fun showRootlessProgress(choice: RuntimeChoiceUi) {
        rootlessProgress = choice.progress
        rootlessDetail = choice.detail
        rootlessRunning = choice.state == "CHECKING"
        rootlessCanCancel = choice.canCancelInstallation
        if (!rootlessRunning) rootlessCancelling = false
        if (choice.state == "CANCELLED") rootlessInstallInFlight = false
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
        intent.getStringExtra(dev.terminal.runtime.notifications.SessionNotificationSettings.EXTRA_SESSION_ID)?.let { id ->
            notificationSessionId = id
            if (sessions.any { it.id == id }) { notificationSessionId = null; attach(id) }
            // Never create a replacement if the PTY owner has already lost this session.
            intent.removeExtra(dev.terminal.runtime.notifications.SessionNotificationSettings.EXTRA_SESSION_ID)
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_IMAGE_PICKER, false)) {
            // Navigation actions are one-shot; rotation must not reopen the picker.
            intent.removeExtra(EXTRA_OPEN_IMAGE_PICKER)
            imagePicker.launch(null)
        }
    }

    private fun safeRuntimeCall(action: (IRuntimeService) -> Unit) {
        runtimeRetry.reset()
        val service = runtime
        if (service == null) {
            if (activityStarted) bindRuntime()
            return
        }
        runCatching { action(service) }.onFailure { handleRuntimeDisconnect() }
    }

    private fun handleRuntimeDisconnect() {
        if (unconfirmedAvfLaunches.isNotEmpty()) {
            unconfirmedAvfLaunches = unconfirmedAvfLaunches.map {
                if (it.launchRequestId in submittedAvfLaunches)
                    it.copy(state = "LAUNCH_FAILED", launchError = getString(R.string.runtime_disconnected)) else it
            }
            renderSessionProjection()
        }
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
        if (activityStarted) mainHandler.postDelayed(reconnectRuntime, runtimeRetry.nextDelayMs())
    }

    private val runtimeRetry = dev.terminal.runtime.lifecycle.ReconnectBackoff()

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
            if (activityStarted) mainHandler.postDelayed(reconnectRuntime, runtimeRetry.nextDelayMs())
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
        private const val DEFAULT_NATIVE_SHELL = "/system/bin/sh"
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
    "AVF" -> dev.terminal.runtime.session.SessionProfile.AVF_HOME_DIRECTORY
    else -> "/"
}
