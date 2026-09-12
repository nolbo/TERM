package dev.terminal.runtime.lifecycle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteCallbackList
import android.util.Log
import dev.terminal.image.DebianImageVerifier
import dev.terminal.image.BundledDebianImageCatalog
import dev.terminal.bridge.AndroidCapabilityBroker
import dev.terminal.bridge.BridgeHostServer
import dev.terminal.bridge.Capability
import dev.terminal.bridge.CapabilityGrant
import dev.terminal.bridge.SharedPreferencesCapabilityGrantStore
import dev.terminal.image.ImageVerification
import dev.terminal.runtime.image.RuntimeSetupCoordinator
import dev.terminal.runtime.image.SetupState
import dev.terminal.runtime.R
import dev.terminal.runtime.api.RuntimeConfig
import dev.terminal.runtime.api.RuntimeState
import dev.terminal.runtime.api.StopMode
import dev.terminal.runtime.api.connectSessionClient
import dev.terminal.runtime.api.RuntimeBackend
import dev.terminal.runtime.native.NativeBackend
import dev.terminal.runtime.rootless.RootlessDebianBackend
import dev.terminal.runtime.rootless.RootlessDistribution
import dev.terminal.runtime.rootless.RootlessStatus
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.session.UnifiedSessionRegistry
import dev.terminal.runtime.forwarding.PortForwardRegistry
import dev.terminal.runtime.forwarding.PortAgentCollector
import dev.terminal.runtime.forwarding.LocalhostPortForwarder
import dev.terminal.runtime.power.RuntimeWakeController
import dev.terminal.runtime.power.WakePolicyStore
import dev.terminal.runtime.storage.SharedDirectoryManager
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.ipc.IRuntimeStateListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import dev.terminal.protocol.SessionController
import dev.terminal.protocol.SessionConnectionState
import dev.terminal.protocol.SessionDescriptor
import dev.terminal.protocol.SessionEvent
import dev.terminal.protocol.GuestWorkEvent
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

/** Owns the runtime in :vmhost; activities only bind, observe, and unbind. */
class VmRuntimeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var foreground = false // Accessed only on the main dispatcher.
    private var notificationUpdateJob: Job? = null
    private var lastNotificationContent: Pair<Int, String>? = null
    private val listeners = RemoteCallbackList<IRuntimeStateListener>()
    private val listenerLock = Any()
    private lateinit var backend: RuntimeBackend
    private lateinit var rootlessBackends: Map<SessionRuntime, RootlessDebianBackend>
    private val sessionClientMutex = Mutex()
    private val runtimeControllerMutex = Mutex()
    private val sessionResizeMutex = Mutex()
    private val latestResizeRequests = LatestSessionResizeRequests()
    private var sessionClient: SessionController? = null
    private val orderedSessionInput = OrderedSessionInput(scope) { id, bytes ->
        runCatching {
            val client = sessionClient ?: error("세션 런타임에 연결되어 있지 않습니다")
            client.write(id, bytes).getOrThrow()
        }.onFailure { notifyOperationError("입력 전송", it.userMessage()) }
    }
    private var reconnectJob: Job? = null
    private var bridgeJob: Job? = null
    private var portAgentJob: Job? = null
    private lateinit var sharedDirectories: SharedDirectoryManager
    private lateinit var wakeController: RuntimeWakeController
    private lateinit var wakePolicies: WakePolicyStore
    private lateinit var capabilityGrants: SharedPreferencesCapabilityGrantStore
    private lateinit var usbBroker: dev.terminal.runtime.usb.UsbBroker
    private var guestReportedWorkActive = false
    private val portRegistry = PortForwardRegistry()
    private lateinit var portForwarder: LocalhostPortForwarder
    @Volatile private var publishedSessions = PublishedSessions(0L, "[]", 0)
    private val sessionSnapshots = SessionSnapshotPipeline(::publishSessionSnapshot)
    private val hostBackgroundOperationCount = AtomicInteger(0)
    @Volatile private var latestState: RuntimeState = RuntimeState.Stopped

    private val kittyIdentities = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val kittyGrants = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var focusedSession: String? = null
    private fun nextKittyId(): Int = dev.terminal.runtime.session.KittyWindowIds(
        getSharedPreferences("kitty-window-identities", MODE_PRIVATE),
    ).allocate().toInt()
    private val kittyRemote by lazy {
        dev.terminal.runtime.session.KittyRemoteControl(object : dev.terminal.runtime.session.KittyRemoteControl.Host {
            override fun allowed(source: String) = source in kittyGrants &&
                getSharedPreferences(RUNTIME_SETTINGS, MODE_PRIVATE).getBoolean(KEY_KITTY_REMOTE, false)
            override fun focused() = focusedSession
            override suspend fun windows(): List<dev.terminal.runtime.session.KittyRemoteControl.Window> {
                val registry = sessionClient as? UnifiedSessionRegistry ?: return emptyList()
                return registry.list().getOrThrow().mapNotNull { session ->
                    val runtime = registry.runtimeOf(session.id) ?: return@mapNotNull null
                    val id = kittyIdentities.getOrPut(session.id) { nextKittyId() }
                    dev.terminal.runtime.session.KittyRemoteControl.Window(id, runtime,
                        session.copy(title = kittyTitles[session.id] ?: session.title))
                }
            }
            override suspend fun launch(source: dev.terminal.runtime.session.KittyRemoteControl.Window, args: List<String>, cwd: String, env: Map<String, String>, title: String): dev.terminal.runtime.session.KittyRemoteControl.Window {
                val registry = sessionClient as? UnifiedSessionRegistry ?: error("Runtime unavailable")
                val id = nextKittyId()
                val preferences = getSharedPreferences(RUNTIME_SETTINGS, MODE_PRIVATE)
                val childEnv = env.toMutableMap().apply {
                    put("TERM", preferences.getString(KEY_NATIVE_TERM, "xterm-256color").orEmpty())
                    put("DROID_LOGIN_SHELL", preferences.getBoolean(KEY_NATIVE_LOGIN, true).toString())
                    put("KITTY_WINDOW_ID", id.toString())
                    if (args.isNotEmpty()) put("DROID_SESSION_ARGV", dev.terminal.runtime.session.SessionCommand.encode(args))
                }
                val result = sessionSnapshots.mutateAndRefresh(registry) {
                    registry.create(source.runtime, if (source.runtime == SessionRuntime.NATIVE) "/system/bin/sh" else "/bin/sh",
                        cwd, childEnv, source.session.rows, source.session.cols).onSuccess { kittyIdentities[it.id] = id }
                }.operation.getOrThrow()
                // Remote-created children never receive a remote-control grant.
                val child = dev.terminal.runtime.session.KittyRemoteControl.Window(id, source.runtime, result)
                sessionSnapshots.setInitialTitle(result.id, title).getOrThrow()
                kittyTitles[result.id] = title
                return child
            }
            override suspend fun focus(window: dev.terminal.runtime.session.KittyRemoteControl.Window) {
                focusedSession = window.session.id
                broadcast { it.onSessionFocusRequested(window.session.id) }
            }
            override suspend fun title(window: dev.terminal.runtime.session.KittyRemoteControl.Window, title: String) {
                sessionSnapshots.rename(window.session.id, title).getOrThrow()
                kittyTitles[window.session.id] = title
            }
            override suspend fun close(window: dev.terminal.runtime.session.KittyRemoteControl.Window) {
                val client = sessionClient ?: error("Runtime unavailable")
                sessionSnapshots.mutateAndRefresh(client) { client.terminate(window.session.id, SIGKILL) }.operation.getOrThrow()
                kittyGrants.remove(window.session.id)
                kittyIdentities.remove(window.session.id)
                kittyTitles.remove(window.session.id)
            }
            override suspend fun resize(window: dev.terminal.runtime.session.KittyRemoteControl.Window, rows: Int, cols: Int) {
                val client = sessionClient ?: error("Runtime unavailable")
                sessionSnapshots.mutateAndRefresh(client) { client.resize(window.session.id, rows.toUShort(), cols.toUShort()) }.operation.getOrThrow()
            }
            override suspend fun write(window: dev.terminal.runtime.session.KittyRemoteControl.Window, bytes: ByteArray) {
                (sessionClient ?: error("Runtime unavailable")).write(window.session.id, bytes).getOrThrow()
            }
            override fun keys(window: dev.terminal.runtime.session.KittyRemoteControl.Window, keys: List<String>) =
                dev.terminal.runtime.session.KittyRemoteKeys.encode(keys, (sessionClient as? UnifiedSessionRegistry)?.keyboardFlags(window.session.id) ?: 0)
        })
    }
    private val kittyTitles = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val binder = object : IRuntimeService.Stub() {
        override fun getUsbStateJson(): String = usbBroker.state()
        override fun approveUsbRequest(requestId: String): String = usbBroker.approve(requestId)
        override fun denyUsbRequest(requestId: String) = usbBroker.deny(requestId)
        override fun revokeUsbSession(sessionId: String) = usbBroker.revokeSession(sessionId)
        override fun addUsbStorageTree(uri: String, flags: Int): String = usbBroker.addStorageTree(uri, flags)
        override fun removeUsbStorageTree(id: String) = usbBroker.removeStorageTree(id)
        override fun getState(): String = latestState.wireName

        override fun getDetail(): String = latestState.detail()

        override fun getImageStatus(): String = imageStatus()

        override fun getRuntimeStatusJson(): String = runtimeStatusJson()

        override fun getSessionsJson(): String = publishedSessions.json

        override fun getForwardedPortsJson(): String = JSONArray().apply {
            portRegistry.ports.value.forEach { port ->
                put(JSONObject().apply {
                    put("guestPort", port.guestPort)
                    put("hostPort", port.hostPort)
                    put("hostAddress", port.hostAddress)
                    put("label", port.label)
                })
            }
        }.toString()

        override fun getWakePolicy(): String = wakePolicies.get().name

        override fun getCapabilityGrant(capability: String): String = runCatching {
            capabilityGrants.get(DEFAULT_VM_INSTANCE_ID, Capability.entries.first { it.wireName == capability }).name
        }.getOrDefault(CapabilityGrant.ASK.name)

        override fun prepare() = launchRuntimeOperation(trackAsWork = true) { prepareAndStart() }

        override fun startRuntime() = launchRuntimeOperation(trackAsWork = true) { prepareAndStart() }

        override fun stopRuntime(mode: Int) = launchRuntimeOperation {
            sessionSnapshots.clear(::closeSessionClient)
            backend.stop(if (mode == FORCE_STOP) StopMode.FORCE else StopMode.GRACEFUL)
        }

        override fun createSession(runtimeType: String, shell: String, cwd: String, rows: Int, cols: Int) = launchRuntimeOperation(trackAsWork = true, errorOperation = "세션 생성:$runtimeType") {
            val requestedRuntime = SessionRuntime.fromWireName(runtimeType)
                ?: throw IllegalArgumentException("알 수 없는 런타임: $runtimeType")
            Log.i(TAG, "session.create runtime=${requestedRuntime.wireName}")
            val client = ensureSessionRegistry()
                ?: throw IllegalStateException("세션 런타임에 연결할 수 없습니다")
            if (!ensureRuntimeController(requestedRuntime, client)) {
                notifyOperationError("세션 생성:${requestedRuntime.wireName}", runtimeUnavailableReason(requestedRuntime))
                return@launchRuntimeOperation
            }
            val preferences = getSharedPreferences(RUNTIME_SETTINGS, MODE_PRIVATE)
            val kittyId = nextKittyId()
            val grantKitty = preferences.getBoolean(KEY_KITTY_REMOTE, false)
            val result = sessionSnapshots.mutateAndRefresh(client) {
                client.create(
                    requestedRuntime,
                    shell,
                    cwd,
                    env = buildMap {
                        put("TERM", preferences.getString(KEY_NATIVE_TERM, "xterm-256color").orEmpty())
                        put("DROID_LOGIN_SHELL", preferences.getBoolean(KEY_NATIVE_LOGIN, true).toString())
                        if (preferences.getBoolean(KEY_KITTY_GRAPHICS, false) || grantKitty) {
                            put("KITTY_WINDOW_ID", kittyId.toString())
                        }
                    },
                    rows = rows.toUShortDimension(),
                    cols = cols.toUShortDimension(),
                ).onSuccess {
                    kittyIdentities[it.id] = kittyId
                    if (grantKitty && preferences.getBoolean(KEY_KITTY_REMOTE, false)) kittyGrants.add(it.id)
                }
            }
            result.operation
                .onSuccess { created ->
                    // Publish the new session before requesting focus so the UI
                    // can attach its renderer and move keyboard input to it.
                    if (result.snapshot?.isSuccess == true) {
                        focusedSession = created.id
                        broadcast { it.onSessionFocusRequested(created.id) }
                    }
                }
                .onFailure {
                    Log.e(TAG, "session.create failed runtime=${requestedRuntime.wireName} cause=${it.javaClass.simpleName}: ${it.userMessage()}")
                    notifyOperationError("세션 생성:${requestedRuntime.wireName}", it.userMessage())
                }
            result.snapshot?.onFailure { notifyOperationError("세션 목록 새로고침", it.userMessage()) }
        }

        override fun attachSession(sessionId: String) = launchRuntimeOperation {
            focusedSession = sessionId
            val client = ensureSessionClient()
                ?: throw IllegalStateException("세션 런타임에 연결할 수 없습니다")
            val result = sessionSnapshots.mutateAndRefresh(client) { client.attach(sessionId) }
            result.operation
                .onFailure { notifyOperationError("세션 연결", it.userMessage()) }
            result.snapshot?.onFailure { notifyOperationError("세션 목록 새로고침", it.userMessage()) }
        }

        override fun detachSession(sessionId: String) = launchRuntimeOperation {
            val client = sessionClient
                ?: throw IllegalStateException("세션 런타임에 연결되어 있지 않습니다")
            val result = sessionSnapshots.mutateAndRefresh(client) { client.detach(sessionId) }
            result.operation
                .onFailure { notifyOperationError("세션 분리", it.userMessage()) }
            result.snapshot?.onFailure { notifyOperationError("세션 목록 새로고침", it.userMessage()) }
        }

        override fun writeSessionInput(sessionId: String, bytes: ByteArray) {
            if (!orderedSessionInput.offer(sessionId, bytes)) {
                notifyOperationError("입력 전송", "입력 대기열이 가득 찼거나 런타임이 종료되었습니다")
            }
        }

        override fun resizeSession(sessionId: String, rows: Int, cols: Int) {
            val request = latestResizeRequests.record(sessionId, rows, cols)
            launchRuntimeOperation {
                sessionResizeMutex.withLock {
                    if (!latestResizeRequests.isLatest(request)) return@withLock
                    val client = sessionClient
                        ?: throw IllegalStateException("세션 런타임에 연결되어 있지 않습니다")
                    client.resize(sessionId, rows.toUShortDimension(), cols.toUShortDimension())
                        .onFailure { notifyOperationError("터미널 크기 변경", it.userMessage()) }
                }
            }
        }

        override fun renameSession(sessionId: String, title: String) = launchRuntimeOperation {
            sessionSnapshots.rename(sessionId, title)
                .onSuccess { kittyTitles[sessionId] = title }
                .onFailure { notifyOperationError("세션 이름 변경", it.userMessage()) }
        }

        override fun terminateSession(sessionId: String) = launchRuntimeOperation {
            val client = sessionClient
            if (client == null) {
                notifySessionTerminationFailed(sessionId, "세션 런타임에 연결되어 있지 않습니다")
                return@launchRuntimeOperation
            }
            val runtime = (client as? UnifiedSessionRegistry)?.runtimeOf(sessionId)?.wireName ?: "UNKNOWN"
            Log.i(TAG, "session.terminate start id=$sessionId runtime=$runtime")
            val result = sessionSnapshots.mutateAndRefresh(client) { client.terminate(sessionId, SIGKILL) }
            result.operation
                .onSuccess {
                    kittyGrants.remove(sessionId)
                    kittyIdentities.remove(sessionId)
                    kittyTitles.remove(sessionId)
                    latestResizeRequests.forget(sessionId)
                    val snapshot = result.snapshot?.getOrNull()
                    Log.i(TAG, "session.terminate success id=$sessionId runtime=$runtime revision=${snapshot?.revision} count=${snapshot?.sessions?.size}")
                }
                .onFailure {
                    Log.w(TAG, "session.terminate failed id=$sessionId runtime=$runtime cause=${it.javaClass.simpleName}: ${it.userMessage()}")
                    notifySessionTerminationFailed(sessionId, it.userMessage())
                }
            result.snapshot?.onFailure { notifyOperationError("세션 목록 새로고침", it.userMessage()) }
        }

        override fun setWakePolicy(policy: String) {
            val parsed = runCatching { RuntimeWakeController.WakePolicy.valueOf(policy) }.getOrNull() ?: return
            wakePolicies.set(parsed)
            updateWakeState()
        }

        override fun setAvfSettings(memoryMib: Int, cpuCount: Int, protectedVm: Boolean, enableNetwork: Boolean) {
            getSharedPreferences(RUNTIME_SETTINGS, MODE_PRIVATE).edit()
                .putInt(KEY_AVF_MEMORY, memoryMib.coerceIn(512, 32768))
                .putInt(KEY_AVF_CPU, cpuCount.coerceIn(1, 16))
                .putBoolean(KEY_AVF_PROTECTED, protectedVm)
                .putBoolean(KEY_AVF_NETWORK, enableNetwork)
                .apply()
        }

        override fun setNativeSessionSettings(terminalType: String, loginShell: Boolean) {
            val validatedTerminalType = terminalType.takeIf { it in SUPPORTED_TERMINAL_TYPES } ?: DEFAULT_TERMINAL_TYPE
            getSharedPreferences(RUNTIME_SETTINGS, MODE_PRIVATE).edit()
                .putString(KEY_NATIVE_TERM, validatedTerminalType)
                .putBoolean(KEY_NATIVE_LOGIN, loginShell)
                .apply()
        }

        override fun setKittyGraphicsCompatibility(enabled: Boolean) {
            getSharedPreferences(RUNTIME_SETTINGS, MODE_PRIVATE).edit()
                .putBoolean(KEY_KITTY_GRAPHICS, enabled).apply()
        }

        override fun setKittyRemoteControl(enabled: Boolean) {
            getSharedPreferences(RUNTIME_SETTINGS, MODE_PRIVATE).edit().putBoolean(KEY_KITTY_REMOTE, enabled).apply()
            if (!enabled) kittyGrants.clear()
        }

        override fun prepareRootless(runtimeType: String) = launchRuntimeOperation(trackAsWork = true, errorOperation = "$ROOTLESS_SETUP_OPERATION:$runtimeType") {
            val selected = SessionRuntime.fromWireName(runtimeType)
            val rootless = rootlessBackends[selected]
                ?: throw IllegalArgumentException("알 수 없는 Rootless 환경: $runtimeType")
            if (runtimeControllerMutex.isLocked && rootless.status() !is RootlessStatus.Ready) rootless.reportPreparationWaiting()
            runtimeControllerMutex.withLock {
                try {
                    val registry = sessionClient as? UnifiedSessionRegistry
                    if (registry != null && rootless.status() !is RootlessStatus.Ready) {
                        registry.removeIfIdle(requireNotNull(selected)) { rootless.prepare().getOrThrow() }
                    } else rootless.prepare().getOrThrow()
                } finally {
                    rootless.clearPreparationWaiting()
                    notifyClients(latestState)
                }
            }
        }

        override fun removeRootless(runtimeType: String, removeHome: Boolean) = launchRuntimeOperation(trackAsWork = true, errorOperation = "ROOTLESS_REMOVE:$runtimeType") {
            val selected = SessionRuntime.fromWireName(runtimeType)
            val rootless = rootlessBackends[selected]
                ?: throw IllegalArgumentException("알 수 없는 Rootless 환경: $runtimeType")
            runtimeControllerMutex.withLock {
                try {
                    // Image management must work before the primary runtime has started.
                    // The backend also checks its PTY owner when there is no registry.
                    val registry = sessionClient as? UnifiedSessionRegistry
                    if (registry != null) {
                        registry.removeIfIdle(requireNotNull(selected)) { rootless.remove(removeHome).getOrThrow() }
                    } else {
                        rootless.remove(removeHome).getOrThrow()
                    }
                } finally {
                    notifyClients(latestState)
                }
            }
        }

        override fun setCapabilityGrant(capability: String, grant: String) = launchRuntimeOperation {
            val requestedCapability = Capability.entries.firstOrNull { it.wireName == capability } ?: return@launchRuntimeOperation
            val requestedGrant = runCatching { CapabilityGrant.valueOf(grant) }.getOrNull() ?: return@launchRuntimeOperation
            capabilityGrants.set(DEFAULT_VM_INSTANCE_ID, requestedCapability, requestedGrant)
        }

        override fun registerListener(listener: IRuntimeStateListener) {
            synchronized(listenerLock) {
                listeners.register(listener)
                val snapshot = publishedSessions
                runCatching {
                    listener.onRuntimeStateChanged(latestState.wireName, latestState.detail())
                    listener.onSessionsChanged(snapshot.revision, snapshot.json)
                    focusedSession?.let { listener.onSessionFocusRequested(it) }
                }
            }
        }

        override fun unregisterListener(listener: IRuntimeStateListener) {
            listeners.unregister(listener)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        sharedDirectories = SharedDirectoryManager(applicationContext)
        wakeController = RuntimeWakeController(applicationContext)
        portForwarder = LocalhostPortForwarder { port -> backend.openGuestPortForward(port) }
        wakePolicies = WakePolicyStore(applicationContext)
        capabilityGrants = SharedPreferencesCapabilityGrantStore(applicationContext)
        usbBroker = dev.terminal.runtime.usb.UsbBroker(applicationContext)
        backend = resolveRuntimeBackend(applicationContext)
        rootlessBackends = RootlessDistribution.entries.associate { distribution ->
            distribution.runtime to RootlessDebianBackend(applicationContext, distribution = distribution, usbBroker = usbBroker) { phase, detail ->
                Log.i(TAG, "rootless distribution=${distribution.directory} phase=${phase.name}")
                broadcast { it.onRootlessProgress(distribution.runtime.wireName, phase.displayName, detail) }
            }
        }
        scope.launch {
            backend.observeState().collect { state ->
                latestState = state
                updateWakeState()
                updateServiceLifetime()
                notifyClients(state)
                if (state is RuntimeState.Ready) {
                    scheduleGuestReconnect()
                    // Native mode has no guest transport. Repeated connection attempts
                    // here used to wake the process every two seconds indefinitely.
                    if (backend !is NativeBackend) {
                        scheduleBridgeServer()
                        schedulePortAgentCollector()
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TERMINATE_ALL_SESSIONS) terminateAllSessions()
        updateServiceLifetime()
        // A killed PTY owner cannot restore sessions by restarting an empty service.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Intentionally empty. Task removal must not own or stop the VM.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        usbBroker.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        listeners.kill()
        closeSessionClient()
        portRegistry.clear()
        portForwarder.stopAll()
        wakeController.release()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun prepareAndStart() {
        // Rebinding an Activity must be idempotent. In particular, do not
        // prepare a Native backend again after it has reached READY.
        if (latestState is RuntimeState.Ready || latestState is RuntimeState.Active || latestState is RuntimeState.Idle) return
        if (backend is NativeBackend) {
            backend.prepare(RuntimeConfig(imageDirectory = filesDir.toPath().resolve("native").toString())).getOrThrow()
            backend.start().getOrThrow()
            return
        }
        val imageRoot = filesDir.toPath().resolve("vm/default")
        if (RuntimeSetupCoordinator(applicationContext).ensureImage(imageRoot, DEFAULT_DEBIAN_RELEASE_ID) is SetupState.Downloading) return
        val sharedDirectory = sharedDirectories.provision(DEFAULT_VM_INSTANCE_ID).getOrThrow()
        val preferences = getSharedPreferences(RUNTIME_SETTINGS, MODE_PRIVATE)
        val config = RuntimeConfig(
            imageDirectory = imageRoot.toString(),
            memoryMib = preferences.getInt(KEY_AVF_MEMORY, 2048).coerceIn(512, 32768),
            cpuCount = preferences.getInt(KEY_AVF_CPU, 2).coerceIn(1, 16),
            protectedVm = preferences.getBoolean(KEY_AVF_PROTECTED, false),
            enableNetwork = preferences.getBoolean(KEY_AVF_NETWORK, true),
            sharedDirectory = sharedDirectory.absolutePath,
        )
        backend.prepare(config).getOrThrow()
        backend.start().getOrThrow()
    }

    private fun launchRuntimeOperation(trackAsWork: Boolean = false, errorOperation: String = "런타임 작업", block: suspend () -> Unit) {
        scope.launch(Dispatchers.Main.immediate) {
            if (trackAsWork) {
                hostBackgroundOperationCount.incrementAndGet()
                updateWakeState()
            }
            try {
                if (trackAsWork) updateServiceLifetime()
                runCatching { withContext(Dispatchers.Default) { block() } }
                    .onFailure { notifyOperationError(errorOperation, it.userMessage()) }
            } finally {
                if (trackAsWork) {
                    hostBackgroundOperationCount.decrementAndGet()
                    updateWakeState()
                }
                if (trackAsWork) updateServiceLifetime()
            }
        }
    }

    private fun terminateAllSessions() = launchRuntimeOperation(trackAsWork = true) {
        // The service remains alive until the whole batch and its snapshot finish,
        // including when no Activity is bound. Never stop a backend to close tabs.
        val client = sessionClient ?: return@launchRuntimeOperation
        val result = sessionSnapshots.mutateAndRefresh(client) {
            val sessions = client.list().getOrThrow()
            sessions.filter { it.state != dev.terminal.protocol.SessionStatus.EXITED }.forEach { session ->
                runCatching { client.terminate(session.id, SIGKILL).getOrThrow() }
                    .onSuccess { latestResizeRequests.forget(session.id) }
                    .onFailure {
                        Log.w(TAG, "session.terminateAll failed id=${session.id}", it)
                        notifySessionTerminationFailed(session.id, it.userMessage())
                    }
            }
            // Refresh even if one termination failed; surviving sessions retain
            // their notification and may be retried with the same action.
            Result.success(Unit)
        }
        result.operation.onFailure { notifyOperationError("모든 세션 종료", it.userMessage()) }
        result.snapshot?.onFailure { notifyOperationError("세션 목록 새로고침", it.userMessage()) }
    }

    private fun notifyClients(state: RuntimeState) {
        broadcast { it.onRuntimeStateChanged(state.wireName, state.detail()) }
    }

    private suspend fun ensureSessionRegistry(): UnifiedSessionRegistry? = sessionClientMutex.withLock {
        (sessionClient as? UnifiedSessionRegistry)?.let { return@withLock it }
        val selectedBackend = backend
        val client = when (selectedBackend) {
            is NativeBackend -> selectedBackend.sessionController()
            else -> selectedBackend.connectSessionClient().getOrNull()
        }
        client?.let { primaryController ->
            val registry = UnifiedSessionRegistry(remoteCommand = { source, command -> kittyRemote.execute(source, command) })
            registry.register(
                if (selectedBackend is NativeBackend) SessionRuntime.NATIVE else SessionRuntime.AVF,
                primaryController,
            )
            sessionClient = registry
            scope.launch {
                registry.events.collect { event ->
                    when (event) {
                        is SessionEvent.Output -> notifyTerminalOutput(event.sessionId, event.bytes, event.replay)
                        is SessionEvent.Exited -> {
                            kittyGrants.remove(event.sessionId)
                            broadcast { it.onSessionExited(event.sessionId, event.exitCode) }
                            refreshSessions(registry)
                        }
                    }
                }
            }
            scope.launch {
                registry.connectionState.collect { connectionState ->
                    if (connectionState == SessionConnectionState.DISCONNECTED && sessionClient === registry) {
                        sessionClient = null
                        // A transport disconnect is not proof that the guest PTY
                        // owner died. Retain the last snapshot while reconnecting.
                        if (latestState is RuntimeState.Ready) scheduleGuestReconnect()
                    }
                }
            }
            refreshSessions(registry)
            registry
        }
    }

    private suspend fun ensureSessionClient(): SessionController? = ensureSessionRegistry()

    private suspend fun ensureRuntimeController(runtime: SessionRuntime, registry: UnifiedSessionRegistry): Boolean {
        val selectedRootless = rootlessBackends[runtime]
        if (runtimeControllerMutex.isLocked && selectedRootless?.status() !is RootlessStatus.Ready) {
            selectedRootless?.reportPreparationWaiting()
        }
        return try {
            runtimeControllerMutex.withLock {
                if (registry.isRegistered(runtime)) {
                    val rootless = rootlessBackends[runtime]
                    if (rootless == null || rootless.status() is RootlessStatus.Ready) return@withLock true
                    registry.removeIfIdle(runtime) { rootless.prepare().getOrThrow() }
                }
                when (runtime) {
                    SessionRuntime.ROOTLESS, SessionRuntime.ROOTLESS_UBUNTU, SessionRuntime.ROOTLESS_ARCH -> {
                        val rootlessBackend = rootlessBackends.getValue(runtime)
                        val prepared = rootlessBackend.prepare()
                        val started = prepared.isSuccess && rootlessBackend.start().isSuccess
                        if (started) rootlessBackend.sessionController()?.let { registry.register(runtime, it) }
                        started && registry.isRegistered(runtime)
                    }
                    SessionRuntime.NATIVE -> backend is NativeBackend && (backend as NativeBackend).sessionController()?.let {
                        registry.register(runtime, it); true
                    } == true
                    SessionRuntime.AVF -> backend !is NativeBackend && backend.connectSessionClient().getOrNull()?.let {
                        registry.register(runtime, it); true
                    } == true
                }
            }
        } finally { selectedRootless?.clearPreparationWaiting() }
    }

    private fun scheduleGuestReconnect() {
        if (reconnectJob?.isActive == true || sessionClient != null) return
        reconnectJob = scope.launch {
            while (latestState is RuntimeState.Ready && sessionClient == null) {
                if (ensureSessionRegistry() != null) return@launch
                delay(GUEST_RECONNECT_DELAY_MS)
            }
        }
    }

    private fun scheduleBridgeServer() {
        if (bridgeJob?.isActive == true) return
        bridgeJob = scope.launch {
            val broker = AndroidCapabilityBroker(applicationContext, DEFAULT_VM_INSTANCE_ID, capabilityGrants)
            while (latestState is RuntimeState.Ready) {
                backend.connectGuestBridge().getOrNull()?.let { connection ->
                    runCatching {
                        BridgeHostServer(broker) { work ->
                            guestReportedWorkActive = work == GuestWorkEvent.WORK_ACTIVE
                            updateWakeState()
                        }.serve(connection)
                    }
                    connection.close()
                }
                delay(BRIDGE_RECONNECT_DELAY_MS)
            }
        }
    }

    private fun schedulePortAgentCollector() {
        if (portAgentJob?.isActive == true) return
        portAgentJob = scope.launch {
            while (latestState is RuntimeState.Ready) {
                backend.connectGuestPortAgent().getOrNull()?.let { connection ->
                    runCatching {
                        PortAgentCollector(
                            registry = portRegistry,
                            onListening = { port -> portForwarder.start(port) },
                            onClosed = { port -> portForwarder.stop(port) },
                        ).collect(connection)
                    }
                    connection.close()
                }
                delay(BRIDGE_RECONNECT_DELAY_MS)
            }
        }
    }

    private suspend fun refreshSessions(client: SessionController) {
        sessionSnapshots.refresh(client)
            .onFailure { Log.w(TAG, "session.refresh failed cause=${it.javaClass.simpleName}: ${it.userMessage()}") }
    }

    private fun publishSessionSnapshot(snapshot: SessionSnapshot) {
        val json = snapshot.sessions.toJson(snapshot.explicitTitleIds, snapshot.initialTitles)
        val published = PublishedSessions(
            revision = snapshot.revision,
            json = json,
            runningCount = snapshot.sessions.count { it.state == dev.terminal.protocol.SessionStatus.RUNNING },
        )
        publishedSessions = published
        updateWakeState()
        updateServiceLifetime()
        Log.d(TAG, "session.snapshot revision=${published.revision} count=${snapshot.sessions.size}")
        broadcast { it.onSessionsChanged(published.revision, published.json) }
    }

    private fun notifyTerminalOutput(sessionId: String, bytes: ByteArray, replay: Boolean) {
        broadcast { it.onTerminalOutput(sessionId, bytes, replay) }
    }

    private fun notifyOperationError(operation: String, message: String) {
        broadcast { it.onOperationError(operation, message) }
    }

    private fun notifySessionTerminationFailed(sessionId: String, message: String) {
        broadcast { it.onSessionTerminationFailed(sessionId, message) }
    }

    private inline fun broadcast(action: (IRuntimeStateListener) -> Unit) {
        synchronized(listenerLock) {
            val count = listeners.beginBroadcast()
            repeat(count) { index -> runCatching { action(listeners.getBroadcastItem(index)) } }
            listeners.finishBroadcast()
        }
    }

    private fun closeSessionClient() {
        kittyGrants.clear()
        kittyIdentities.clear()
        kittyTitles.clear()
        focusedSession = null
        reconnectJob?.cancel()
        reconnectJob = null
        bridgeJob?.cancel()
        bridgeJob = null
        portAgentJob?.cancel()
        portAgentJob = null
        portForwarder.stopAll()
        val client = sessionClient
        sessionClient = null
        client?.close()
        guestReportedWorkActive = false
    }

    private fun Throwable.userMessage(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    private fun updateWakeState() = wakeController.update(
        wakePolicies.get(),
        if (publishedSessions.runningCount == 0 && hostBackgroundOperationCount.get() == 0) {
            RuntimeWakeController.GuestWorkState.STOPPED
        } else runtimeWorkState(
            latestState,
            publishedSessions.runningCount,
            guestReportedWorkActive,
            hostBackgroundOperationCount.get() > 0,
        ),
    )

    private fun imageStatus(): String = if (backend is NativeBackend) {
        "Native 사용자 공간 준비됨. AVF Debian은 권한 있는 제품 빌드에서 사용할 수 있습니다."
    } else when (
        val verification = DebianImageVerifier.verify(Paths.get(filesDir.absolutePath, "vm", "default"))
    ) {
        is ImageVerification.Ready -> "Debian ARM64 image verified"
        is ImageVerification.Invalid -> if (BundledDebianImageCatalog.find(DEFAULT_DEBIAN_RELEASE_ID) == null) {
            "$verification. 승인된 자동 설치 카탈로그가 이 빌드에 포함되지 않았습니다. 로컬 검증 이미지를 선택하거나 OEM 제품 카탈로그를 사용하세요."
        } else verification.reason
    }

    private fun List<SessionDescriptor>.toJson(explicitTitleIds: Set<String> = emptySet(), initialTitles: Map<String, String> = emptyMap()): String = JSONArray().apply {
        forEach { session -> put(JSONObject().apply {
            put("id", session.id)
            put("runtime", (sessionClient as? UnifiedSessionRegistry)?.runtimeOf(session.id)?.wireName
                ?: SessionRuntime.fromSessionId(session.id)?.wireName
                ?: if (backend is NativeBackend) SessionRuntime.NATIVE.wireName else SessionRuntime.AVF.wireName)
            put("title", session.title)
            put("explicitTitle", session.id in explicitTitleIds)
            put("initialTitle", initialTitles[session.id] ?: session.title)
            put("command", session.command)
            put("cwd", session.cwd)
            put("rows", session.rows.toInt())
            put("cols", session.cols.toInt())
            put("state", session.state.name)
            if (session.state == dev.terminal.protocol.SessionStatus.EXITED) {
                put("exitCode", session.exitCode)
            }
        }) }
    }.toString()

    private fun runtimeStatusJson(): String = JSONObject().apply {
        put(SessionRuntime.NATIVE.wireName, JSONObject().apply {
            put("available", backend is NativeBackend)
            put("detail", if (backend is NativeBackend) "즉시 사용 가능" else "이 제품 변형은 Native 컨트롤러를 제공하지 않습니다")
        })
        RootlessDistribution.entries.forEach { distribution ->
            val rootlessBackend = rootlessBackends.getValue(distribution.runtime)
            val rootless = rootlessBackend.status()
            val progress = rootlessBackend.installProgress()
            put(distribution.runtime.wireName, JSONObject().apply {
                put("available", rootless is RootlessStatus.Ready)
                put("installed", rootlessBackends.getValue(distribution.runtime).hasInstalledImage())
                put("installable", rootlessBackends.getValue(distribution.runtime).canInstall())
                put("state", when (rootless) {
                    RootlessStatus.Checking -> "CHECKING"
                    RootlessStatus.Ready -> "READY"
                    is RootlessStatus.Error -> "ERROR"
                    is RootlessStatus.SetupRequired -> "SETUP_REQUIRED"
                    is RootlessStatus.EnvironmentRequired -> "ENVIRONMENT_REQUIRED"
                })
                put("detail", when (rootless) {
                    RootlessStatus.Checking -> progress?.update?.detail ?: "${distribution.displayName} 상태를 확인하고 있습니다"
                    RootlessStatus.Ready -> "${distribution.displayName} 기본 구성과 셸 실행 확인 완료"
                    is RootlessStatus.Error -> rootless.reason
                    is RootlessStatus.SetupRequired -> rootless.reason
                    is RootlessStatus.EnvironmentRequired -> rootless.reason
                })
                if (progress != null) put("progress", JSONObject().apply {
                    put("phase", progress.update.phase.name)
                    put("label", progress.update.phase.displayName)
                    put("detail", progress.update.detail)
                    put("fraction", progress.update.fraction)
                    put("completedBytes", progress.update.completedBytes)
                    put("totalBytes", progress.update.totalBytes)
                    put("startedAtElapsedMs", progress.startedAtElapsedMs)
                    put("updatedAtElapsedMs", progress.updatedAtElapsedMs)
                    put("finishedAtElapsedMs", progress.finishedAtElapsedMs)
                })
            })
        }
        put(SessionRuntime.AVF.wireName, JSONObject().apply {
            put("available", backend !is NativeBackend && latestState is RuntimeState.Ready)
            put("detail", if (backend is NativeBackend) "특권 AVF 제품 빌드가 필요합니다" else latestState.detail())
        })
    }.toString()

    private fun runtimeUnavailableReason(runtime: SessionRuntime): String = when (runtime) {
        SessionRuntime.NATIVE -> "Native 런타임이 이 제품 변형에서 준비되지 않았습니다"
        SessionRuntime.ROOTLESS, SessionRuntime.ROOTLESS_UBUNTU, SessionRuntime.ROOTLESS_ARCH -> when (val status = rootlessBackends.getValue(runtime).status()) {
            RootlessStatus.Checking -> "선택한 Rootless 환경을 준비하고 있습니다"
            RootlessStatus.Ready -> "선택한 Rootless 세션을 시작할 수 없습니다"
            is RootlessStatus.Error -> status.reason
            is RootlessStatus.SetupRequired -> status.reason
            is RootlessStatus.EnvironmentRequired -> status.reason
        }
        SessionRuntime.AVF -> "AVF는 지원 기기, 특권 권한, 시스템 SDK 드라이버가 필요합니다"
    }

    private fun Int.toUShortDimension(): UShort {
        require(this in 1..UShort.MAX_VALUE.toInt()) { "Terminal dimensions must be between 1 and 65535" }
        return toUShort()
    }

    private fun updateServiceLifetime() {
        scope.launch(Dispatchers.Main.immediate) {
            // Read current state here, not a captured snapshot from a worker thread.
            val keepAlive = publishedSessions.runningCount > 0 || hostBackgroundOperationCount.get() > 0
            if (keepAlive) {
                if (!foreground) {
                    // Binding alone must not own a live PTY after the Activity detaches.
                    startService(Intent(this@VmRuntimeService, VmRuntimeService::class.java))
                    startForeground(NOTIFICATION_ID, notification(latestState))
                    lastNotificationContent = notificationContent()
                    foreground = true
                } else if (notificationUpdateJob?.isActive != true) {
                    // Coalesce bursts of snapshots/operation completion. Android
                    // otherwise drops updates when the enqueue rate is exceeded.
                    notificationUpdateJob = scope.launch(Dispatchers.Main.immediate) {
                        delay(500)
                        val content = notificationContent()
                        if (foreground && content != lastNotificationContent) {
                            getSystemService(NotificationManager::class.java)
                                .notify(NOTIFICATION_ID, notification(latestState))
                            lastNotificationContent = content
                        }
                    }
                }
            } else {
                notificationUpdateJob?.cancel()
                notificationUpdateJob = null
                lastNotificationContent = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                foreground = false
                wakeController.release()
                // A visible bound UI can still create the next session. Once it
                // unbinds Android destroys this idle service normally.
                stopSelf()
            }
        }
    }

    private fun notificationContent(): Pair<Int, String> = publishedSessions.runningCount.let { count ->
        count to if (count > 0) "" else latestState.detail()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Linux runtime", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(state: RuntimeState): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification_terminal)
        .setContentTitle(
            if (publishedSessions.runningCount > 0) "TERM · 실행 중인 세션 ${publishedSessions.runningCount}개"
            else "TERM · 런타임 준비 중",
        )
        .setContentText(
            if (publishedSessions.runningCount > 0) "터미널을 닫아도 세션은 백그라운드에서 계속 실행됩니다."
            else state.detail(),
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(contentIntent)
        .apply {
            if (publishedSessions.runningCount > 0) {
                val terminateIntent = PendingIntent.getService(
                    this@VmRuntimeService,
                    1,
                    Intent(this@VmRuntimeService, VmRuntimeService::class.java)
                        .setAction(ACTION_TERMINATE_ALL_SESSIONS),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                addAction(Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this@VmRuntimeService, android.R.drawable.ic_menu_close_clear_cancel),
                    "모든 세션 종료",
                    terminateIntent,
                ).build())
            }
        }
        .build()
    }

    private fun RuntimeState.detail(): String = when (this) {
        RuntimeState.Stopped -> if (backend is NativeBackend) "Native 런타임 중지됨" else "VM 중지됨"
        RuntimeState.Preparing -> if (backend is NativeBackend) "Native 사용자 공간 준비 중" else "Debian 이미지 준비 중"
        RuntimeState.Booting -> if (backend is NativeBackend) "Native 런타임 시작 중" else "Linux VM 부팅 중"
        RuntimeState.Ready -> if (backend is NativeBackend) "Android Native 셸 준비됨" else "게스트 에이전트 준비됨"
        RuntimeState.Active -> if (backend is NativeBackend) "Native 세션 실행 중" else "Linux 세션 실행 중"
        RuntimeState.Idle -> if (backend is NativeBackend) "Native 런타임 대기 중" else "Linux 런타임 대기 중"
        RuntimeState.Suspended -> "Linux VM 일시 중단됨"
        RuntimeState.Stopping -> if (backend is NativeBackend) "Native 런타임 종료 중" else "Linux VM 종료 중"
        is RuntimeState.Failed -> reason
    }

    private companion object {
        const val TAG = "VmRuntimeService"
        const val ACTION_TERMINATE_ALL_SESSIONS = "dev.terminal.runtime.action.TERMINATE_ALL_SESSIONS"
        const val CHANNEL_ID = "linux_runtime"
        const val NOTIFICATION_ID = 1001
        const val FORCE_STOP = 1
        const val SIGKILL = 9
        const val GUEST_RECONNECT_DELAY_MS = 2_000L
        const val BRIDGE_RECONNECT_DELAY_MS = 2_000L
        const val DEFAULT_VM_INSTANCE_ID = "default"
        const val DEFAULT_DEBIAN_RELEASE_ID = "debian-arm64-current"
        const val RUNTIME_SETTINGS = "runtime-settings"
        const val KEY_AVF_MEMORY = "avf-memory-mib"
        const val KEY_AVF_CPU = "avf-cpu-count"
        const val KEY_AVF_PROTECTED = "avf-protected"
        const val KEY_AVF_NETWORK = "avf-network"
        const val KEY_NATIVE_TERM = "native-term"
        const val KEY_NATIVE_LOGIN = "native-login-shell"
        const val KEY_KITTY_REMOTE = "kitty-remote-control"
        const val KEY_KITTY_GRAPHICS = "kitty-graphics-compatibility"
        const val DEFAULT_TERMINAL_TYPE = "xterm-256color"
        const val ROOTLESS_SETUP_OPERATION = "ROOTLESS_SETUP"
        val SUPPORTED_TERMINAL_TYPES = setOf("xterm-256color", "xterm", "screen-256color")
    }

    private data class PublishedSessions(
        val revision: Long,
        val json: String,
        val runningCount: Int,
    )
}

internal data class SessionResizeRequest(
    val sessionId: String,
    val rows: Int,
    val cols: Int,
    val sequence: Long,
)

/** Keeps stale asynchronous Binder resize calls from overriding the newest terminal geometry. */
internal class LatestSessionResizeRequests {
    private val sequence = AtomicLong()
    private val latest = ConcurrentHashMap<String, SessionResizeRequest>()

    fun record(sessionId: String, rows: Int, cols: Int): SessionResizeRequest =
        SessionResizeRequest(sessionId, rows, cols, sequence.incrementAndGet()).also {
            latest[sessionId] = it
        }

    fun isLatest(request: SessionResizeRequest): Boolean = latest[request.sessionId] == request

    fun forget(sessionId: String) {
        latest.remove(sessionId)
    }
}

internal fun runtimeWorkState(
    runtimeState: RuntimeState,
    runningSessionCount: Int,
    guestReportedWorkActive: Boolean,
    hostBackgroundWorkActive: Boolean,
): RuntimeWakeController.GuestWorkState = when {
    runtimeState is RuntimeState.Stopping || runtimeState is RuntimeState.Failed ->
        RuntimeWakeController.GuestWorkState.STOPPED
    hostBackgroundWorkActive -> RuntimeWakeController.GuestWorkState.ACTIVE
    runtimeState is RuntimeState.Stopped -> RuntimeWakeController.GuestWorkState.STOPPED
    runtimeState is RuntimeState.Suspended -> RuntimeWakeController.GuestWorkState.IDLE
    runningSessionCount > 0 || guestReportedWorkActive -> RuntimeWakeController.GuestWorkState.ACTIVE
    else -> RuntimeWakeController.GuestWorkState.IDLE
}
