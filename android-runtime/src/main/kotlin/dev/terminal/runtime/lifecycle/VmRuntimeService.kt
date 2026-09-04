package dev.terminal.runtime.lifecycle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteCallbackList
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
import java.util.concurrent.ConcurrentHashMap

/** Owns the runtime in :vmhost; activities only bind, observe, and unbind. */
class VmRuntimeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val listeners = RemoteCallbackList<IRuntimeStateListener>()
    private val listenerLock = Any()
    private lateinit var backend: RuntimeBackend
    private lateinit var rootlessBackend: RootlessDebianBackend
    private val sessionClientMutex = Mutex()
    private val runtimeControllerMutex = Mutex()
    private var sessionClient: SessionController? = null
    private var reconnectJob: Job? = null
    private var bridgeJob: Job? = null
    private var portAgentJob: Job? = null
    private lateinit var sharedDirectories: SharedDirectoryManager
    private lateinit var wakeController: RuntimeWakeController
    private lateinit var wakePolicies: WakePolicyStore
    private lateinit var capabilityGrants: SharedPreferencesCapabilityGrantStore
    private var guestWork = RuntimeWakeController.GuestWorkState.IDLE
    private val portRegistry = PortForwardRegistry()
    private lateinit var portForwarder: LocalhostPortForwarder
    @Volatile private var sessionsJson: String = "[]"
    @Volatile private var runningSessionCount: Int = 0
    private val attachedSessionIds = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var latestState: RuntimeState = RuntimeState.Stopped

    private val binder = object : IRuntimeService.Stub() {
        override fun getState(): String = latestState.wireName

        override fun getDetail(): String = latestState.detail()

        override fun getImageStatus(): String = imageStatus()

        override fun getRuntimeStatusJson(): String = runtimeStatusJson()

        override fun getSessionsJson(): String = this@VmRuntimeService.sessionsJson

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

        override fun prepare() = launchRuntimeOperation { prepareAndStart() }

        override fun startRuntime() = launchRuntimeOperation { prepareAndStart() }

        override fun stopRuntime(mode: Int) = launchRuntimeOperation {
            closeSessionClient()
            backend.stop(if (mode == FORCE_STOP) StopMode.FORCE else StopMode.GRACEFUL)
        }

        override fun createSession(runtimeType: String, shell: String, cwd: String, rows: Int, cols: Int) = launchRuntimeOperation {
            val requestedRuntime = SessionRuntime.fromWireName(runtimeType)
                ?: throw IllegalArgumentException("알 수 없는 런타임: $runtimeType")
            val client = ensureSessionRegistry()
                ?: throw IllegalStateException("세션 런타임에 연결할 수 없습니다")
            if (!ensureRuntimeController(requestedRuntime, client)) {
                notifyOperationError("세션 생성", runtimeUnavailableReason(requestedRuntime))
                return@launchRuntimeOperation
            }
            val preferences = getSharedPreferences(RUNTIME_SETTINGS, MODE_PRIVATE)
            val result = client.create(
                requestedRuntime,
                shell,
                cwd,
                env = mapOf(
                    "TERM" to preferences.getString(KEY_NATIVE_TERM, "xterm-256color").orEmpty(),
                    "DROID_LOGIN_SHELL" to preferences.getBoolean(KEY_NATIVE_LOGIN, true).toString(),
                ),
                rows = rows.toUShortDimension(),
                cols = cols.toUShortDimension(),
            )
            result.onSuccess { refreshSessions(client) }
                .onFailure { notifyOperationError("세션 생성", it.userMessage()) }
        }

        override fun attachSession(sessionId: String) = launchRuntimeOperation {
            val client = ensureSessionClient()
                ?: throw IllegalStateException("세션 런타임에 연결할 수 없습니다")
            client.attach(sessionId)
                .onSuccess {
                    attachedSessionIds += sessionId
                    refreshSessions(client)
                    updateAttachedWorkState()
                }
                .onFailure { notifyOperationError("세션 연결", it.userMessage()) }
        }

        override fun detachSession(sessionId: String) = launchRuntimeOperation {
            val client = sessionClient
                ?: throw IllegalStateException("세션 런타임에 연결되어 있지 않습니다")
            client.detach(sessionId)
                .onSuccess {
                    attachedSessionIds -= sessionId
                    refreshSessions(client)
                    updateAttachedWorkState()
                }
                .onFailure { notifyOperationError("세션 분리", it.userMessage()) }
        }

        override fun writeSessionInput(sessionId: String, bytes: ByteArray) = launchRuntimeOperation {
            val client = sessionClient
                ?: throw IllegalStateException("세션 런타임에 연결되어 있지 않습니다")
            client.write(sessionId, bytes)
                .onFailure { notifyOperationError("입력 전송", it.userMessage()) }
        }

        override fun resizeSession(sessionId: String, rows: Int, cols: Int) = launchRuntimeOperation {
            val client = sessionClient
                ?: throw IllegalStateException("세션 런타임에 연결되어 있지 않습니다")
            client.resize(sessionId, rows.toUShortDimension(), cols.toUShortDimension())
                .onFailure { notifyOperationError("터미널 크기 변경", it.userMessage()) }
        }

        override fun terminateSession(sessionId: String) = launchRuntimeOperation {
            val client = sessionClient
                ?: throw IllegalStateException("세션 런타임에 연결되어 있지 않습니다")
            // Tab close is an explicit terminate operation, unlike a UI detach.
            client.terminate(sessionId, SIGKILL)
                .onSuccess {
                    attachedSessionIds -= sessionId
                    refreshSessions(client)
                    updateAttachedWorkState()
                }
                .onFailure { notifyOperationError("세션 종료", it.userMessage()) }
        }

        override fun setWakePolicy(policy: String) = launchRuntimeOperation {
            val parsed = runCatching { RuntimeWakeController.WakePolicy.valueOf(policy) }.getOrNull() ?: return@launchRuntimeOperation
            wakePolicies.set(parsed)
            wakeController.update(parsed, guestWork)
        }

        override fun setCapabilityGrant(capability: String, grant: String) = launchRuntimeOperation {
            val requestedCapability = Capability.entries.firstOrNull { it.wireName == capability } ?: return@launchRuntimeOperation
            val requestedGrant = runCatching { CapabilityGrant.valueOf(grant) }.getOrNull() ?: return@launchRuntimeOperation
            capabilityGrants.set(DEFAULT_VM_INSTANCE_ID, requestedCapability, requestedGrant)
        }

        override fun registerListener(listener: IRuntimeStateListener) {
            listeners.register(listener)
            runCatching { listener.onRuntimeStateChanged(latestState.wireName, latestState.detail()) }
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
        backend = resolveRuntimeBackend(applicationContext)
        rootlessBackend = RootlessDebianBackend(applicationContext)
        scope.launch {
            backend.observeState().collect { state ->
                latestState = state
                updateNotification(state)
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
        // Start foreground immediately: a VM runtime is an explicit persistent operation.
        startForeground(NOTIFICATION_ID, notification(latestState))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Intentionally empty. Task removal must not own or stop the VM.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
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

    private fun launchRuntimeOperation(block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }
                .onFailure { notifyOperationError("런타임 작업", it.userMessage()) }
        }
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
            val registry = UnifiedSessionRegistry()
            registry.register(
                if (selectedBackend is NativeBackend) SessionRuntime.NATIVE else SessionRuntime.AVF,
                primaryController,
            )
            sessionClient = registry
            scope.launch {
                registry.events.collect { event ->
                    when (event) {
                        is SessionEvent.Output -> notifyTerminalOutput(event.sessionId, event.bytes)
                        is SessionEvent.Exited -> {
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
                        sessionsJson = "[]"
                        broadcast { it.onSessionsChanged(sessionsJson) }
                        if (latestState is RuntimeState.Ready) scheduleGuestReconnect()
                    }
                }
            }
            refreshSessions(registry)
            registry
        }
    }

    private suspend fun ensureSessionClient(): SessionController? = ensureSessionRegistry()

    private suspend fun ensureRuntimeController(runtime: SessionRuntime, registry: UnifiedSessionRegistry): Boolean =
        runtimeControllerMutex.withLock {
            if (registry.isRegistered(runtime)) return@withLock true
            when (runtime) {
                SessionRuntime.ROOTLESS -> {
                    val root = filesDir.toPath().resolve("rootless/debian")
                    val prepared = rootlessBackend.prepare(RuntimeConfig(imageDirectory = root.toString()))
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
                            setGuestWork(
                                if (work == GuestWorkEvent.WORK_ACTIVE) {
                                    RuntimeWakeController.GuestWorkState.ACTIVE
                                } else {
                                    RuntimeWakeController.GuestWorkState.IDLE
                                },
                            )
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
        client.list().onSuccess { sessions ->
            sessionsJson = sessions.toJson()
            runningSessionCount = sessions.count { it.state == dev.terminal.protocol.SessionStatus.RUNNING }
            updateNotification(latestState)
            broadcast { it.onSessionsChanged(sessionsJson) }
        }
    }

    private fun notifyTerminalOutput(sessionId: String, bytes: ByteArray) {
        broadcast { it.onTerminalOutput(sessionId, bytes) }
    }

    private fun notifyOperationError(operation: String, message: String) {
        broadcast { it.onOperationError(operation, message) }
    }

    private inline fun broadcast(action: (IRuntimeStateListener) -> Unit) {
        synchronized(listenerLock) {
            val count = listeners.beginBroadcast()
            repeat(count) { index -> runCatching { action(listeners.getBroadcastItem(index)) } }
            listeners.finishBroadcast()
        }
    }

    private fun closeSessionClient() {
        reconnectJob?.cancel()
        reconnectJob = null
        bridgeJob?.cancel()
        bridgeJob = null
        portAgentJob?.cancel()
        portAgentJob = null
        portForwarder.stopAll()
        sessionClient?.close()
        sessionClient = null
        attachedSessionIds.clear()
        sessionsJson = "[]"
        runningSessionCount = 0
        updateNotification(latestState)
        setGuestWork(RuntimeWakeController.GuestWorkState.IDLE)
    }

    private fun updateAttachedWorkState() = setGuestWork(
        if (attachedSessionIds.isEmpty()) RuntimeWakeController.GuestWorkState.IDLE
        else RuntimeWakeController.GuestWorkState.ACTIVE,
    )

    private fun Throwable.userMessage(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    private fun setGuestWork(state: RuntimeWakeController.GuestWorkState) {
        guestWork = state
        wakeController.update(wakePolicies.get(), state)
    }

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

    private fun List<SessionDescriptor>.toJson(): String = JSONArray().apply {
        forEach { session -> put(JSONObject().apply {
            put("id", session.id)
            put("runtime", (sessionClient as? UnifiedSessionRegistry)?.runtimeOf(session.id)?.wireName
                ?: SessionRuntime.fromSessionId(session.id)?.wireName
                ?: if (backend is NativeBackend) SessionRuntime.NATIVE.wireName else SessionRuntime.AVF.wireName)
            put("title", session.title)
            put("command", session.command)
            put("cwd", session.cwd)
            put("rows", session.rows.toInt())
            put("cols", session.cols.toInt())
            put("state", session.state.name)
            session.exitCode?.let { put("exitCode", it) }
        }) }
    }.toString()

    private fun runtimeStatusJson(): String = JSONObject().apply {
        put(SessionRuntime.NATIVE.wireName, JSONObject().apply {
            put("available", backend is NativeBackend)
            put("detail", if (backend is NativeBackend) "즉시 사용 가능" else "이 제품 변형은 Native 컨트롤러를 제공하지 않습니다")
        })
        val rootless = rootlessBackend.status()
        put(SessionRuntime.ROOTLESS.wireName, JSONObject().apply {
            put("available", rootless is RootlessStatus.Ready)
            put("detail", when (rootless) {
                RootlessStatus.Ready -> "Debian rootfs와 APK PRoot 준비됨"
                is RootlessStatus.SetupRequired -> rootless.reason
            })
        })
        put(SessionRuntime.AVF.wireName, JSONObject().apply {
            put("available", backend !is NativeBackend && latestState is RuntimeState.Ready)
            put("detail", if (backend is NativeBackend) "특권 AVF 제품 빌드가 필요합니다" else latestState.detail())
        })
    }.toString()

    private fun runtimeUnavailableReason(runtime: SessionRuntime): String = when (runtime) {
        SessionRuntime.NATIVE -> "Native 런타임이 이 제품 변형에서 준비되지 않았습니다"
        SessionRuntime.ROOTLESS -> when (val status = rootlessBackend.status()) {
            RootlessStatus.Ready -> "Rootless Debian 세션을 시작할 수 없습니다"
            is RootlessStatus.SetupRequired -> status.reason
        }
        SessionRuntime.AVF -> "AVF는 지원 기기, 특권 권한, 시스템 SDK 드라이버가 필요합니다"
    }

    private fun Int.toUShortDimension(): UShort {
        require(this in 1..UShort.MAX_VALUE.toInt()) { "Terminal dimensions must be between 1 and 65535" }
        return toUShort()
    }

    private fun updateNotification(state: RuntimeState) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(state))
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
        .setSmallIcon(android.R.drawable.stat_sys_warning)
        .setContentTitle(
            if (runningSessionCount > 0) "Droid Terminal · 실행 중인 세션 ${runningSessionCount}개"
            else "Droid Terminal runtime",
        )
        .setContentText(
            if (runningSessionCount > 0) "터미널을 닫아도 세션은 백그라운드에서 계속 실행됩니다."
            else state.detail(),
        )
        // A live shell is a user-visible persistent operation. Do not allow
        // this notification to be dismissed while at least one PTY is alive.
        .setOngoing(runningSessionCount > 0)
        .setContentIntent(contentIntent)
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
    }
}
