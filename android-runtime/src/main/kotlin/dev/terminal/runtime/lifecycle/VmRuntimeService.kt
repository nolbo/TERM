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
import dev.terminal.runtime.session.Environment
import dev.terminal.runtime.session.EnvironmentId
import dev.terminal.runtime.session.EnvironmentRegistry
import dev.terminal.runtime.session.SessionLaunchSpec
import dev.terminal.runtime.session.SessionRuntime
import kotlinx.coroutines.flow.first
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
    private val taskNotifications by lazy { dev.terminal.runtime.notifications.SessionTaskNotifications(this) }
    private val listeners = RemoteCallbackList<IRuntimeStateListener>()
    private val listenerLock = Any()
    private val broadcastLock = Any()
    private lateinit var backend: RuntimeBackend
    private lateinit var rootlessBackends: Map<SessionRuntime, RootlessDebianBackend>
    private val environmentBackends = java.util.concurrent.ConcurrentHashMap<EnvironmentId, dev.terminal.runtime.api.RuntimeBackend>()
    private val startedEnvironmentBackends = java.util.concurrent.ConcurrentHashMap.newKeySet<EnvironmentId>()
    // Explicit environment starts outlive UI bindings, including with no terminal sessions.
    private val explicitlyStartedEnvironments = java.util.concurrent.ConcurrentHashMap.newKeySet<EnvironmentId>()
    private val sessionClientMutex = Mutex()
    private val preparationLocks = StoragePreparationLocks()
    private val storageMigrationMutex = Mutex()
    private val primaryPreparationMutex = Mutex()
    private val terminalSinks = java.util.concurrent.ConcurrentHashMap<Pair<String, android.os.IBinder>, dev.terminal.runtime.session.RuntimeTerminalState.Sink>()
    private val terminalDeaths = java.util.concurrent.ConcurrentHashMap<Pair<String, android.os.IBinder>, android.os.IBinder.DeathRecipient>()
    private val terminalOperations = kotlinx.coroutines.channels.Channel<suspend () -> Unit>(512)
    init {
        scope.launch {
            for (operation in terminalOperations) runCatching { operation() }
                .onFailure { notifyOperationError("터미널 화면 복원", it.userMessage()) }
        }
    }
    private fun terminalOperation(operation: suspend () -> Unit) {
        check(terminalOperations.trySend(operation).isSuccess) { "Terminal state queue is full or closed" }
    }
    private val sessionResizeMutex = Mutex()
    private val latestResizeRequests = LatestSessionResizeRequests()
    @Volatile private var sessionClient: SessionController? = null
    private val orderedSessionInput = OrderedSessionInput(scope) { id, bytes ->
        runCatching {
            val client = sessionClient ?: error("세션 런타임에 연결되어 있지 않습니다")
            client.write(id, bytes).getOrThrow()
        }.onFailure { notifyOperationError("입력 전송", it.userMessage()) }
    }
    private val guestRetry = ReconnectBackoff()
    private val bridgeRetry = ReconnectBackoff()
    private val portRetry = ReconnectBackoff()
    private var wakeExpiryJob: Job? = null
    private var reconnectJob: Job? = null
    private var bridgeJob: Job? = null
    private var portAgentJob: Job? = null
    private lateinit var sharedDirectories: SharedDirectoryManager
    private lateinit var wakeController: RuntimeWakeController
    private lateinit var wakePolicies: WakePolicyStore
    private lateinit var capabilityGrants: SharedPreferencesCapabilityGrantStore
    private lateinit var usbBroker: dev.terminal.runtime.usb.UsbBroker
    @Volatile private var guestReportedWorkActive = false
    private val portRegistry = PortForwardRegistry()
    private lateinit var portForwarder: LocalhostPortForwarder
    @Volatile private var publishedSessions = PublishedSessions(0L, "[]", 0)
    private val sessionSnapshots = SessionSnapshotPipeline(::publishSessionSnapshot)
    private val pendingAvfLaunches = PendingAvfSessionLaunches()
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
                        cwd, childEnv, source.session.rows, source.session.cols,
                        storageId = requireNotNull(registry.storageOf(source.session.id))).onSuccess { kittyIdentities[it.id] = id }
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

    private val storageRegistry by lazy {
        dev.terminal.runtime.session.StorageRegistry(filesDir) {
            getSharedPreferences("session-profiles", MODE_PRIVATE).all.mapValues { it.value as String }
        }
    }
    private val environmentRegistry by lazy { EnvironmentRegistry(storageRegistry) }
    private fun environmentForStorage(runtime: SessionRuntime, storageId: String): Environment =
        environmentRegistry.transaction(storageDefaults()) { forStorage(dev.terminal.runtime.session.StorageRegistry.Key(runtime, storageId)) }

    private fun storageDefaults(): List<dev.terminal.runtime.session.SessionProfile> = SessionRuntime.entries
        .filter { it != SessionRuntime.AVF || (checkSelfPermission("android.permission.MANAGE_VIRTUAL_MACHINE") == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission("android.permission.USE_CUSTOM_VIRTUAL_MACHINE") == android.content.pm.PackageManager.PERMISSION_GRANTED) }
        .map { dev.terminal.runtime.session.SessionProfile(dev.terminal.runtime.session.SessionProfile.builtInId(it),
            when (it) { SessionRuntime.ROOTLESS -> "Debian"; SessionRuntime.ROOTLESS_UBUNTU -> "Ubuntu";
                SessionRuntime.ROOTLESS_ARCH -> "Arch"; SessionRuntime.NATIVE -> "Native"; SessionRuntime.AVF -> "AVF" }, it, "/bin/sh") }

    private val binder = object : IRuntimeService.Stub() {
        // Retained Binder adapters for older same-app callers; the domain owner is Environment.
        override fun getStorageJson(): String = JSONArray(getEnvironmentsJson()).let { array ->
            for (index in 0 until array.length()) {
                val row = array.getJSONObject(index)
                row.put("runtime", row.getString("sessionRuntime"))
            }
            array.toString()
        }
        override fun renameStorage(runtimeType: String, storageId: String, name: String): String {
            enforceHostToolUi()
            return runCatching { renameEnvironment(environmentForStorage(requireNotNull(SessionRuntime.fromWireName(runtimeType)), storageId).id.value, name) }
                .getOrElse { it.userMessage() }
        }
        override fun getEnvironmentsJson(): String {
            enforceHostToolUi()
            return kotlinx.coroutines.runBlocking(Dispatchers.IO) { environmentsJson() }
        }
        override fun getEnvironmentSoftwareJson(environmentId: String): String {
            enforceHostToolUi()
            return kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                val environment = environmentRegistry.transaction(storageDefaults()) { requireEnvironment(EnvironmentId(environmentId)) }
                val sessions = (sessionClient as? UnifiedSessionRegistry)?.verifiedSessions(environment)
                val idle = sessions?.getOrNull()?.none { it.state != dev.terminal.protocol.SessionStatus.EXITED } ?: (sessions == null)
                val busy = preparationLocks.isLocked(environment.sessionRuntime, environment.storage.id)
                when (environment.runtime) {
                    dev.terminal.runtime.session.EnvironmentRuntime.ROOTLESS -> {
                        val owner = rootlessEnvironmentBackend(environment)
                        val ownerIdle = runCatching { verifyRootlessOwnerIdle(owner) }.isSuccess
                        rootlessStatusJson(owner, RootlessDistribution.entries.first { it.runtime == environment.sessionRuntime }).apply {
                            put("canInstall", idle && ownerIdle && !busy && getBoolean("installable"))
                            put("canRemove", idle && ownerIdle && !busy && getBoolean("hasData"))
                            put("busy", busy || owner.isInstallationActive())
                        }
                    }
                    dev.terminal.runtime.session.EnvironmentRuntime.AVF -> {
                        val installing = RuntimeSetupCoordinator(applicationContext).isInstallationActive(environment.storage.id)
                        val root = dev.terminal.runtime.session.SessionStorage.root(filesDir, environment.sessionRuntime, environment.storage.id).toPath()
                        val state = environmentRuntimeState(environment, sessionClient as? UnifiedSessionRegistry)
                        val hasData = java.nio.file.Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        JSONObject().put("installed", dev.terminal.image.DebianImageVerifier.hasInstalledImage(root))
                            .put("hasData", hasData).put("busy", busy || installing)
                            .put("canRemove", idle && !busy && !installing && hasData && environment.storage.id != "legacy" &&
                                (state != null || environmentBackends[environment.id] == null && hasAvfPrivileges()))
                    }
                    else -> JSONObject()
                }.put("environmentId", environment.id.value).toString()
            }
        }
        override fun cancelRootlessInstallation(runtimeType: String, storageId: String): Boolean {
            enforceHostToolUi()
            val selected = requireNotNull(SessionRuntime.fromWireName(runtimeType)) { "Unknown runtime" }
            check(selected in rootlessBackends) { "Only Userspace Linux installations can be cancelled here" }
            dev.terminal.runtime.session.SessionStorage.validate(storageId)
            // Cancellation must not acquire the preparation lock held by the installer.
            val environment = environmentRegistry.transaction(storageDefaults()) {
                all().firstOrNull { it.sessionRuntime == selected && it.storage.id == storageId }
            }
            val owner = environment?.let { environmentBackends[it.id] as? RootlessDebianBackend }
                ?: rootlessBackends[selected]?.takeIf { storageId == "legacy" || storageId == defaultStorageId(selected) }
            return owner?.cancelInstallation() == true
        }
        override fun measureEnvironmentUsageJson(environmentId: String): String {
            enforceHostToolUi()
            val environment = environmentRegistry.transaction(storageDefaults()) { requireEnvironment(EnvironmentId(environmentId)) }
            return measureStorageUsageJson(environment.sessionRuntime.wireName, environment.storage.id)
        }
        override fun renameEnvironment(environmentId: String, name: String): String {
            enforceHostToolUi()
            return runCatching {
                environmentRegistry.transaction(storageDefaults()) { rename(EnvironmentId(environmentId), name) }
                ""
            }.exceptionOrNull()?.userMessage().orEmpty()
        }
        override fun deleteEnvironment(environmentId: String): String {
            enforceHostToolUi()
            return runCatching { kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                trackedEnvironmentOperation { removeEnvironment(EnvironmentId(environmentId)) }
            }; "" }
                .exceptionOrNull()?.userMessage().orEmpty()
        }
        override fun operateEnvironment(environmentId: String, operation: String): String {
            enforceHostToolUi()
            return runCatching { kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                trackedEnvironmentOperation {
                    val environment = environmentRegistry.transaction(storageDefaults()) { requireEnvironment(EnvironmentId(environmentId)) }
                    val client = ensureSessionRegistry() ?: error("Runtime unavailable")
                    when (operation) {
                        "START" -> {
                            val profile = dev.terminal.runtime.session.SessionProfile("environment:${environment.id}", environment.name,
                                environment.sessionRuntime, if (environment.sessionRuntime == SessionRuntime.NATIVE) "/system/bin/sh" else "/bin/bash",
                                environmentId = environment.id)
                            check(ensureEnvironmentController(profile.resolve(environment), client)) { "Environment cannot start" }
                            preparationLocks.withStorage(environment.sessionRuntime, environment.storage.id) {
                                check(client.isRegistered(environment.sessionRuntime, environment.storage.id) &&
                                    environment.storage.id !in client.disconnectedStorages(environment.sessionRuntime)) { "Environment stopped during startup" }
                                explicitlyStartedEnvironments.add(environment.id)
                            }
                        }
                        "STOP" -> preparationLocks.withAll {
                            check(environmentRuntimeState(environment, client) != null) { "Cannot verify environment runtime state" }
                            val owner = environmentBackends[environment.id]
                            client.deleteStorageIfIdle(environment.sessionRuntime, environment.storage.id) { release ->
                                owner?.stop(StopMode.GRACEFUL)?.getOrThrow()
                                release()
                                startedEnvironmentBackends.remove(environment.id)
                                explicitlyStartedEnvironments.remove(environment.id)
                            }
                        }
                        "INSTALL", "REPAIR" -> installRootlessEnvironment(environment, client)
                        "REMOVE_SOFTWARE", "REMOVE_SOFTWARE_AND_HOME" -> removeEnvironmentSoftware(environment, client,
                            removeHome = operation == "REMOVE_SOFTWARE_AND_HOME")
                        else -> error("Unsupported environment operation")
                    }
                    refreshSessions(client)
                }
            }; "" }.exceptionOrNull()?.takeUnless {
                it is dev.terminal.runtime.rootless.RootlessInstallationCancelledException
            }?.userMessage().orEmpty()
        }
        override fun measureStorage(runtimeType: String, storageId: String): Long =
            JSONObject(measureStorageUsageJson(runtimeType, storageId)).getLong("bytes")
        override fun measureStorageUsageJson(runtimeType: String, storageId: String): String {
            enforceHostToolUi()
            val key = dev.terminal.runtime.session.StorageRegistry.Key(requireNotNull(SessionRuntime.fromWireName(runtimeType)), storageId)
            return kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                preparationLocks.withStorage(key.runtime, key.storageId) {
                    storageRegistry.transaction { check(key in keys() || key.storageId == "legacy") { "Storage not found" } }
                    try {
                        storageRegistry.measureUsage(key).json().toString()
                    } catch (failure: java.io.IOException) {
                        // Binder only marshals supported runtime exceptions; a checked NIO
                        // exception can otherwise leave an empty reply (zero for a long RPC).
                        throw IllegalStateException("Storage size unavailable", failure)
                    }
                }
            }
        }
        override fun deleteStorage(runtimeType: String, storageId: String): String {
            enforceHostToolUi()
            return runCatching { deleteEnvironment(environmentForStorage(requireNotNull(SessionRuntime.fromWireName(runtimeType)), storageId).id.value) }
                .getOrElse { it.userMessage() }
        }
        override fun getSessionMemoryJson(): String {
            enforceHostToolUi()
            val info = android.app.ActivityManager.MemoryInfo()
            (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(info)
            val measured = (sessionClient as? UnifiedSessionRegistry)?.sessionMemory().orEmpty()
            return JSONObject().put("totalBytes", info.totalMem).put("availableBytes", info.availMem)
                .put("runtimeAppMemory", dev.terminal.runtime.session.currentAppProcessMemory() ?: JSONObject.NULL)
                .put("sessions", JSONObject().apply {
                    measured.forEach { (id, bytes) -> put(id, bytes ?: JSONObject.NULL) }
                }).toString()
        }
        override fun getUsbStateJson(): String = usbBroker.state()
        override fun getHostToolOperationsJson(): String { enforceHostToolUi(); return usbBroker.iphoneTools.hostState() }
        override fun submitHostToolInput(operationId: String, promptNonce: String, values: Array<String>): String {
            enforceHostToolUi()
            return try { usbBroker.iphoneTools.hostInput(operationId, promptNonce, values); "" }
            catch (failure: Exception) { failure.message?.takeIf { it.matches(Regex("[A-Z_]{1,80}")) } ?: "INVALID_TOOL_INPUT" }
            finally { values.fill("") }
        }
        override fun cancelHostToolOperation(operationId: String) { enforceHostToolUi(); usbBroker.iphoneTools.hostCancel(operationId) }
        override fun approveUsbRequest(requestId: String): String = usbBroker.approve(requestId)
        override fun denyUsbRequest(requestId: String) = usbBroker.deny(requestId)
        override fun revokeUsbSession(sessionId: String) = usbBroker.revokeSession(sessionId)
        override fun addUsbStorageTree(uri: String, flags: Int): String = usbBroker.addStorageTree(uri, flags)
        override fun removeUsbStorageTree(id: String) = usbBroker.removeStorageTree(id)
        override fun getState(): String = latestState.wireName

        override fun getDetail(): String = latestState.detail()

        override fun getImageStatus(): String = imageStatus()

        override fun getRuntimeStatusJson(): String = runtimeStatusJson()

        override fun getSessionsJson(): String = pendingAvfLaunches.project(publishedSessions.json)

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
            environmentBackends.values.forEach { it.stop(if (mode == FORCE_STOP) StopMode.FORCE else StopMode.GRACEFUL).getOrThrow() }
            environmentBackends.clear()
            startedEnvironmentBackends.clear()
            explicitlyStartedEnvironments.clear()
            backend.stop(if (mode == FORCE_STOP) StopMode.FORCE else StopMode.GRACEFUL)
            updateServiceLifetime()
        }

        override fun createSessionFromSpec(launchSpecJson: String, rows: Int, cols: Int) =
            createResolvedSession(launchSpecJson, rows, cols)

        override fun createSessionFromSpecWithRequestId(launchSpecJson: String, requestId: String, rows: Int, cols: Int) =
            createResolvedSession(launchSpecJson, rows, cols, requestId)

        override fun createSession(runtimeType: String, shell: String, cwd: String, rows: Int, cols: Int) {
            val selected = SessionRuntime.fromWireName(runtimeType)
            if (selected == null) {
                launchRuntimeOperation { throw IllegalArgumentException("Unknown runtime: $runtimeType") }
                return
            }
            val preferences = getSharedPreferences(RUNTIME_SETTINGS, MODE_PRIVATE)
            val profile = dev.terminal.runtime.session.SessionProfile(
                dev.terminal.runtime.session.SessionProfile.builtInId(selected), selected.wireName, selected, shell,
                if (selected != SessionRuntime.AVF && preferences.getBoolean(KEY_NATIVE_LOGIN, true)) listOf("-l") else emptyList(), cwd)
            createResolvedSession(profile.resolve().toJson().toString(), rows, cols)
        }

        private fun createResolvedSession(encoded: String, rows: Int, cols: Int, requestId: String? = null) {
            val parsed = runCatching { dev.terminal.runtime.session.SessionLaunchSpec.fromJson(encoded) }
            val launch = parsed.getOrNull()?.takeIf { it.runtime == SessionRuntime.AVF }?.let {
                PendingAvfSessionLaunches.Launch(requestId ?: java.util.UUID.randomUUID().toString(), it)
            }
            if (launch != null && !pendingAvfLaunches.begin(launch)) return
            val operation = "세션 생성:${parsed.getOrNull()?.runtime?.wireName ?: "UNKNOWN"}:${parsed.getOrNull()?.storageId ?: "legacy"}" +
                (launch?.let { ":${it.requestId}" } ?: "")
            launchRuntimeOperation(trackAsWork = true, errorOperation = operation) {
                try {
                    if (launch != null) sessionSnapshots.republish()
                    val client = ensureSessionRegistry()
                        ?: throw IllegalStateException("세션 런타임에 연결할 수 없습니다")
                    migrateLegacyStorages(client)
                    storageMigrationMutex.withLock {
                        val spec = environmentRegistry.transaction(storageDefaults()) { resolveLaunch(parsed.getOrThrow()) }
                        val requestedRuntime = spec.runtime
                        Log.i(TAG, "session.create runtime=${requestedRuntime.wireName}")
                        if (!ensureEnvironmentController(spec, client)) {
                            if (launch != null) {
                                pendingAvfLaunches.fail(launch, runtimeUnavailableReason(requestedRuntime))
                                sessionSnapshots.republish()
                            }
                            notifyOperationError("세션 생성:${requestedRuntime.wireName}:${spec.storageId}", runtimeUnavailableReason(requestedRuntime))
                            return@launchRuntimeOperation
                        }
                        val preferences = getSharedPreferences(RUNTIME_SETTINGS, MODE_PRIVATE)
                        val kittyId = nextKittyId()
                        val grantKitty = preferences.getBoolean(KEY_KITTY_REMOTE, false)
                        val result = sessionSnapshots.mutateAndRefresh(client) {
                            client.create(
                                spec,
                                runtimeEnvironment = buildMap {
                                    if ("TERM" !in spec.environment) put("TERM", preferences.getString(KEY_NATIVE_TERM, "xterm-256color").orEmpty())
                                    if (preferences.getBoolean(KEY_KITTY_GRAPHICS, false) || grantKitty) {
                                        put("KITTY_WINDOW_ID", kittyId.toString())
                                    }
                                },
                                rows = rows.toUShortDimension(),
                                cols = cols.toUShortDimension(),
                            ).onSuccess {
                                if (launch != null) {
                                    getSharedPreferences("session-launch-requests", MODE_PRIVATE).edit()
                                        .putString(it.id, launch.requestId).apply()
                                    pendingAvfLaunches.complete(launch.requestId)
                                }
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
                                if (launch != null) {
                                    pendingAvfLaunches.fail(launch, it.userMessage())
                                    sessionSnapshots.republish()
                                }
                                Log.e(TAG, "session.create failed runtime=${requestedRuntime.wireName} cause=${it.javaClass.simpleName}: ${it.userMessage()}")
                                notifyOperationError(if (launch != null) operation else "세션 생성:${requestedRuntime.wireName}:${spec.storageId}", it.userMessage())
                            }
                        result.snapshot?.onFailure {
                            if (launch != null) {
                                pendingAvfLaunches.fail(launch, it.userMessage())
                                sessionSnapshots.republish()
                            }
                            notifyOperationError(if (launch != null) operation else "세션 목록 새로고침", it.userMessage())
                        }
                    }
                } catch (failure: Throwable) {
                    if (launch != null) runCatching {
                        pendingAvfLaunches.fail(launch, failure.userMessage())
                        sessionSnapshots.republish()
                    }
                    throw failure
                }
            }
        }

        override fun attachTerminal(sessionId: String, listener: dev.terminal.runtime.ipc.ITerminalStateListener,
            rows: Int, cols: Int, cellWidth: Int, cellHeight: Int, paletteJson: String) = terminalOperation {
            val key = sessionId to listener.asBinder()
            try {
                val registry = ensureSessionRegistry() ?: error("세션 런타임에 연결할 수 없습니다")
                val sink = object : dev.terminal.runtime.session.RuntimeTerminalState.Sink {
                    override fun checkpoint(emulator: com.termux.terminal.TerminalEmulator,
                        viewport: dev.terminal.runtime.session.RuntimeTerminalState.Viewport?) {
                        val pipe = android.os.ParcelFileDescriptor.createReliablePipe()
                        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
                        // The caller holds the terminal-state mutex until this writer completes.
                        // Binder receives the read end before any potentially pipe-sized write.
                        val writer = Thread({
                            try {
                                java.io.DataOutputStream(java.io.BufferedOutputStream(
                                    android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]))).use { out ->
                                    out.writeBoolean(viewport != null)
                                    if (viewport != null) {
                                        out.writeInt(viewport.row); out.writeFloat(viewport.offset); out.writeLong(viewport.transcriptShift)
                                    }
                                    com.termux.terminal.TerminalCheckpoint.write(emulator, out)
                                }
                            } catch (error: Throwable) {
                                failure.set(error)
                                runCatching { pipe[1].closeWithError("Terminal checkpoint failed") }
                            }
                        }, "TerminalCheckpointWriter")
                        writer.start()
                        try { listener.onCheckpoint(pipe[0]) }
                        finally {
                            // Closing the local read end also unblocks the writer after UI death.
                            pipe[0].close()
                            writer.join()
                        }
                        failure.get()?.let { throw java.io.IOException("Terminal checkpoint failed", it) }
                    }

                    override fun output(bytes: ByteArray) = listener.onOutput(bytes)
                    override fun resized(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) = listener.onResize(columns, rows, cellWidth, cellHeight)
                    override fun exited(code: Int) = listener.onExited(code)
                }
                terminalSinks.put(key, sink)?.let { old ->
                    registry.terminalStates.mutex.withLock { registry.terminalStates.unsubscribe(sessionId, old) }
                }
                val death = android.os.IBinder.DeathRecipient {
                    if (terminalSinks.remove(key, sink)) {
                        terminalDeaths.remove(key)
                        scope.launch { registry.terminalStates.mutex.withLock { registry.terminalStates.unsubscribe(sessionId, sink) } }
                    }
                }
                terminalDeaths.put(key, death)?.let { listener.asBinder().unlinkToDeath(it, 0) }
                listener.asBinder().linkToDeath(death, 0)
                registry.configureTerminal(sessionId, rows, cols, cellWidth, cellHeight, terminalPalette(paletteJson), sink)
            } catch (error: Exception) {
                terminalDeaths.remove(key)?.let { runCatching { key.second.unlinkToDeath(it, 0) } }
                terminalSinks.remove(key)?.let { sink ->
                    (sessionClient as? UnifiedSessionRegistry)?.let { registry ->
                        registry.terminalStates.mutex.withLock { registry.terminalStates.unsubscribe(sessionId, sink) }
                    }
                }
                runCatching { listener.onError(error.userMessage()) }
            }
        }

        override fun detachTerminal(sessionId: String, listener: dev.terminal.runtime.ipc.ITerminalStateListener) = terminalOperation {
            val registry = sessionClient as? UnifiedSessionRegistry ?: return@terminalOperation
            val key = sessionId to listener.asBinder()
            terminalDeaths.remove(key)?.let { key.second.unlinkToDeath(it, 0) }
            terminalSinks.remove(key)?.let { sink ->
                registry.terminalStates.mutex.withLock { registry.terminalStates.unsubscribe(sessionId, sink) }
            }
        }

        override fun configureTerminal(sessionId: String, rows: Int, cols: Int, cellWidth: Int, cellHeight: Int,
            paletteJson: String) = terminalOperation {
            val registry = sessionClient as? UnifiedSessionRegistry ?: error("세션 런타임에 연결되어 있지 않습니다")
            registry.configureTerminal(sessionId, rows, cols, cellWidth, cellHeight, terminalPalette(paletteJson))
        }

        override fun setTerminalViewport(sessionId: String, row: Int, offset: Float, transcriptShift: Long) = terminalOperation {
            require(row in -50_000..0 && offset.isFinite() && offset in 0f..1f)
            val registry = sessionClient as? UnifiedSessionRegistry ?: return@terminalOperation
            registry.terminalStates.mutex.withLock {
                registry.terminalStates.viewport(sessionId, dev.terminal.runtime.session.RuntimeTerminalState.Viewport(row, offset, transcriptShift))
            }
        }

        override fun attachSession(sessionId: String) = launchRuntimeOperation {
            resetReconnectBackoff()
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
            resetReconnectBackoff()
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
            if (pendingAvfLaunches.dismiss(sessionId)) {
                sessionSnapshots.republish()
                return@launchRuntimeOperation
            }
            val client = sessionClient
            if (client == null) {
                notifySessionTerminationFailed(sessionId, "세션 런타임에 연결되어 있지 않습니다")
                return@launchRuntimeOperation
            }
            val runtime = (client as? UnifiedSessionRegistry)?.runtimeOf(sessionId)?.wireName ?: "UNKNOWN"
            Log.i(TAG, "session.terminate start id=$sessionId runtime=$runtime")
            taskNotifications.forget(sessionId)
            val result = sessionSnapshots.mutateAndRefresh(client) { client.terminate(sessionId, SIGKILL) }
            result.operation
                .onSuccess {
                    taskNotifications.forget(sessionId)
                    getSharedPreferences("session-launches", MODE_PRIVATE).edit().remove(sessionId).apply()
                    getSharedPreferences("session-launch-requests", MODE_PRIVATE).edit().remove(sessionId).apply()
                    kittyGrants.remove(sessionId)
                    kittyIdentities.remove(sessionId)
                    kittyTitles.remove(sessionId)
                    latestResizeRequests.forget(sessionId)
                    terminalSinks.keys.filter { it.first == sessionId }.forEach { key ->
                        terminalSinks.remove(key)
                        terminalDeaths.remove(key)?.let { runCatching { key.second.unlinkToDeath(it, 0) } }
                    }
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

        override fun getUserspaceLinuxSettingsJson(): String {
            enforceHostToolUi()
            val policy = dev.terminal.runtime.rootless.RootlessDnsStore(applicationContext).policy()
            return JSONObject().put("dnsMode", policy.mode).put("dnsServers", policy.servers.joinToString("\n"))
                .put("engine", dev.terminal.runtime.rootless.RootlessEngineInfo.inspect(applicationContext).toJson()).toString()
        }

        override fun setUserspaceLinuxDns(mode: String, servers: String): String {
            enforceHostToolUi()
            return runCatching {
                dev.terminal.runtime.rootless.RootlessDnsStore(applicationContext)
                    .save(dev.terminal.runtime.rootless.RootlessDnsPolicy.parse(mode, servers))
            }.exceptionOrNull()?.userMessage().orEmpty()
        }

        override fun setKittyGraphicsCompatibility(enabled: Boolean) {
            getSharedPreferences(RUNTIME_SETTINGS, MODE_PRIVATE).edit()
                .putBoolean(KEY_KITTY_GRAPHICS, enabled).apply()
        }

        override fun setSessionTaskNotifications(enabled: Boolean) = taskNotifications.setEnabled(enabled)

        override fun setKittyRemoteControl(enabled: Boolean) {
            getSharedPreferences(RUNTIME_SETTINGS, MODE_PRIVATE).edit().putBoolean(KEY_KITTY_REMOTE, enabled).apply()
            if (!enabled) kittyGrants.clear()
        }

        override fun prepareRootless(runtimeType: String) = launchRuntimeOperation(trackAsWork = true, errorOperation = "$ROOTLESS_SETUP_OPERATION:$runtimeType") {
            val selected = SessionRuntime.fromWireName(runtimeType)
            val rootless = rootlessBackends[selected]
                ?: throw IllegalArgumentException("알 수 없는 Rootless 환경: $runtimeType")
            migrateLegacyStorages(ensureSessionRegistry())
            val storageId = defaultStorageId(requireNotNull(selected))
            storageRegistry.transaction { validateConnection(dev.terminal.runtime.session.StorageRegistry.Key(selected, storageId)) }
            if (preparationLocks.isLocked(requireNotNull(selected)) && rootless.status() !is RootlessStatus.Ready) rootless.reportPreparationWaiting()
            preparationLocks.withStorage(requireNotNull(selected), storageId) {
                try {
                    val registry = sessionClient as? UnifiedSessionRegistry
                    if (registry != null && rootless.status() !is RootlessStatus.Ready) {
                        registry.removeIfIdle(requireNotNull(selected), storageId) { rootless.prepare().getOrThrow() }
                    } else rootless.prepare().getOrThrow()
                } finally {
                    rootless.clearPreparationWaiting()
                    notifyClients(latestState)
                }
            }
        }

        override fun migrateRootlessToNative(runtimeType: String) = launchRuntimeOperation(trackAsWork = true, errorOperation = "ROOTLESS_MIGRATE:$runtimeType") {
            val selected = SessionRuntime.fromWireName(runtimeType)
                ?: throw IllegalArgumentException("알 수 없는 Rootless 환경")
            val rootless = rootlessBackends[selected] ?: throw IllegalArgumentException("Rootless 환경이 아닙니다")
            val registry = ensureSessionRegistry() ?: error("세션 상태를 확인할 수 없습니다")
            preparationLocks.withAll {
                try { registry.migrateIfAllIdle(selected, defaultStorageId(selected)) { rootless.migrateToNative().getOrThrow() } }
                finally { notifyClients(latestState) }
            }
        }

        override fun removeRootless(runtimeType: String, removeHome: Boolean) = launchRuntimeOperation(trackAsWork = true, errorOperation = "ROOTLESS_REMOVE:$runtimeType") {
            val selected = SessionRuntime.fromWireName(runtimeType)
            val rootless = rootlessBackends[selected]
                ?: throw IllegalArgumentException("알 수 없는 Rootless 환경: $runtimeType")
            preparationLocks.withStorage(requireNotNull(selected), defaultStorageId(selected)) {
                try {
                    // Image management must work before the primary runtime has started.
                    // The backend also checks its PTY owner when there is no registry.
                    val registry = sessionClient as? UnifiedSessionRegistry
                    if (registry != null) {
                        registry.removeIfIdle(requireNotNull(selected), defaultStorageId(selected)) { rootless.remove(removeHome).getOrThrow() }
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
            val snapshot = synchronized(listenerLock) {
                listeners.register(listener)
                publishedSessions
            }
            runCatching {
                listener.onRuntimeStateChanged(latestState.wireName, latestState.detail())
                listener.onSessionsChanged(snapshot.revision, pendingAvfLaunches.project(snapshot.json))
                focusedSession?.let { listener.onSessionFocusRequested(it) }
            }
        }

        override fun unregisterListener(listener: IRuntimeStateListener) {
            listeners.unregister(listener)
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSharedPreferences("session-launches", MODE_PRIVATE).let { launches ->
            launches.edit().apply {
                launches.all.keys.filter { SessionRuntime.fromSessionId(it) != SessionRuntime.AVF }.forEach { remove(it) }
            }.apply()
        }
        createNotificationChannel()
        sharedDirectories = SharedDirectoryManager(applicationContext)
        wakeController = RuntimeWakeController(applicationContext)
        portForwarder = LocalhostPortForwarder { port -> backend.openGuestPortForward(port) }
        wakePolicies = WakePolicyStore(applicationContext)
        capabilityGrants = SharedPreferencesCapabilityGrantStore(applicationContext)
        usbBroker = dev.terminal.runtime.usb.UsbBroker(applicationContext) { delta ->
            hostBackgroundOperationCount.addAndGet(delta)
            updateWakeState()
            updateServiceLifetime()
        }
        backend = resolveRuntimeBackend(applicationContext)
        (backend as? NativeBackend)?.configureUsbTooling({ id ->
            usbBroker.register(id, SessionRuntime.NATIVE.wireName) +
                dev.terminal.runtime.native.ApkNativeExecutableLoader(applicationContext)
                    .resolvePackagedExecutable("terminal-usb").getOrNull()?.let {
                        mapOf("TERMINAL_USB_CLIENT" to it.absolutePath)
                    }.orEmpty()
        }, usbBroker::unregister)
        rootlessBackends = RootlessDistribution.entries.associate { distribution ->
            distribution.runtime to RootlessDebianBackend(applicationContext, distribution = distribution, usbBroker = usbBroker) { phase, detail ->
                Log.i(TAG, "rootless distribution=${distribution.directory} phase=${phase.name}")
                broadcast { it.onRootlessProgress(distribution.runtime.wireName, phase.displayName, detail) }
                val id = defaultStorageId(distribution.runtime)
                if (id != "legacy") broadcast { it.onRootlessProgress("${distribution.runtime.wireName}:$id", phase.displayName, detail) }
            }
        }
        scope.launch {
            backend.observeState().collect { state ->
                latestState = state
                updateWakeState()
                updateServiceLifetime()
                notifyClients(state)
                if (state is RuntimeState.Ready) {
                    updateReconnectWork()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TERMINATE_ALL_SESSIONS) terminateAllSessions()
        // Android 14+ allows swiping ongoing notifications away. Invalidate the
        // cached content so the lifetime check reposts only while work is live.
        if (intent?.action == ACTION_RESTORE_SESSION_NOTIFICATION) lastNotificationContent = null
        updateServiceLifetime()
        // A killed PTY owner cannot restore sessions by restarting an empty service.
        return START_NOT_STICKY
    }

    private fun enforceHostToolUi() {
        val uid = android.os.Binder.getCallingUid(); val pid = android.os.Binder.getCallingPid()
        val manager = getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        if (uid != android.os.Process.myUid() || manager.runningAppProcesses.orEmpty().none {
            it.pid == pid && it.uid == uid && it.processName == packageName
        }) throw SecurityException("UNAUTHORIZED")
    }
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Intentionally empty. Task removal must not own or stop the VM.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        terminalOperations.close()
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
        migrateLegacyStorages(ensureSessionRegistry())
        val runtime = if (backend is NativeBackend) SessionRuntime.NATIVE else SessionRuntime.AVF
        preparationLocks.withStorage(runtime, defaultStorageId(runtime)) { startPrimaryBackend() }
        migrateLegacyStorages(ensureSessionRegistry())
    }

    private suspend fun startPrimaryBackend() = primaryPreparationMutex.withLock {
        // Rebinding an Activity must be idempotent. In particular, do not
        // prepare a Native backend again after it has reached READY.
        if (latestState is RuntimeState.Ready || latestState is RuntimeState.Active || latestState is RuntimeState.Idle) return@withLock
        if (backend is NativeBackend) {
            backend.prepare(RuntimeConfig(imageDirectory = filesDir.toPath().resolve("native-runtime").toString())).getOrThrow()
            backend.start().getOrThrow()
            return@withLock
        }
        val imageRoot = filesDir.toPath().resolve("vm/default")
        val storageId = defaultStorageId(SessionRuntime.AVF)
        if (storageRegistry.transaction { isDeleted(dev.terminal.runtime.session.StorageRegistry.Key(SessionRuntime.AVF, storageId)) }) return@withLock
        storageRegistry.transaction { validateConnection(dev.terminal.runtime.session.StorageRegistry.Key(SessionRuntime.AVF, storageId)) }
        RuntimeSetupCoordinator(applicationContext).awaitInstalledImage(imageRoot, storageId)
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
                resetReconnectBackoff()
                hostBackgroundOperationCount.incrementAndGet()
                updateWakeState()
            }
            try {
                if (trackAsWork) updateServiceLifetime()
                runCatching { withContext(Dispatchers.Default) { block() } }
                    .onFailure {
                        if (it !is dev.terminal.runtime.rootless.RootlessInstallationCancelledException)
                            notifyOperationError(errorOperation, it.userMessage())
                    }
            } finally {
                if (trackAsWork) {
                    hostBackgroundOperationCount.decrementAndGet()
                    updateWakeState()
                }
                if (trackAsWork) updateServiceLifetime()
            }
        }
    }

    /** Binder callers can detach while preparation continues; service work keeps its owner alive. */
    private suspend fun <T> trackedEnvironmentOperation(block: suspend () -> T): T {
        withContext(Dispatchers.Main.immediate) {
            resetReconnectBackoff()
            hostBackgroundOperationCount.incrementAndGet()
            updateWakeState()
            updateServiceLifetime()
        }
        return try { block() } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) {
                hostBackgroundOperationCount.decrementAndGet()
                updateWakeState()
                updateServiceLifetime()
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
                taskNotifications.forget(session.id)
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

    private suspend fun ensureSessionRegistry(): UnifiedSessionRegistry? = storageMigrationMutex.withLock { ensureSessionRegistryLocked() }

    private suspend fun ensureSessionRegistryLocked(): UnifiedSessionRegistry? = sessionClientMutex.withLock {
        (sessionClient as? UnifiedSessionRegistry)?.let { attachPrimaryAvf(it); return@withLock it }
        run {
            val registry = UnifiedSessionRegistry(onDisconnected = { runtime, _ ->
                if (runtime == SessionRuntime.AVF) scheduleGuestReconnect()
            }, onInput = { id, bytes ->
                taskNotifications.input(id, bytes)
                updateWakeState()
            }, restoreLaunch = { id -> getSharedPreferences("session-launches", MODE_PRIVATE).getString(id, null)
                ?.let { runCatching { SessionLaunchSpec.fromJson(it) }.getOrNull() } }, recordLaunch = { id, spec ->
                resetReconnectBackoff()
                getSharedPreferences("session-launches", MODE_PRIVATE).edit().putString(id, spec.toJson().toString()).commit()
            }, beforeCreate = {
                check(!dev.terminal.runtime.rootless.RootlessNativeMigration.pending(java.io.File(filesDir, "rootless").toPath())) {
                    "중단된 가속 형식 이전이 있습니다. 설정에서 이전을 다시 실행해 복구하세요"
                }
            }, remoteCommand = { source, command -> kittyRemote.execute(source, command) },
                terminalStates = dev.terminal.runtime.session.RuntimeTerminalState(
                    getSharedPreferences("terminal-ui", MODE_PRIVATE).getInt("scrollback-rows", 10_000)),
                initialPalette = { spec ->
                    val preferences = getSharedPreferences("terminal-ui", MODE_PRIVATE)
                    dev.terminal.runtime.session.TerminalPalette.asProperties(
                        dev.terminal.runtime.session.TerminalPalette.resolve({ key, fallback -> preferences.getString(key, fallback).orEmpty() }, spec.palette))
                })
            attachPrimaryAvf(registry)
            sessionClient = registry
            scope.launch {
                registry.events.collect { event ->
                    when (event) {
                        is SessionEvent.Output -> notifyTerminalOutput(event.sessionId, event.bytes, event.replay)
                        is SessionEvent.Exited -> {
                            taskNotifications.exited(event.sessionId, event.exitCode, sessionNotificationTitle(event.sessionId))
                            kittyGrants.remove(event.sessionId)
                            broadcast { it.onSessionExited(event.sessionId, event.exitCode) }
                            refreshSessions(registry)
                            scope.launch { migrateLegacyStorages(registry) }
                        }
                    }
                }
            }
            refreshSessions(registry)
            registry
        }
    }

    private suspend fun ensureSessionClient(): SessionController? = ensureSessionRegistry()

    /** A failed/disconnected owner is unknown, never evidence that deletion is safe. */
    private suspend fun environmentRuntimeState(environment: Environment, client: UnifiedSessionRegistry?): RuntimeState? {
        if (client?.verifiedSessions(environment)?.isFailure == true) return null
        val owner = environmentBackends[environment.id]
        if (owner == null) return if (environment.sessionRuntime == SessionRuntime.AVF) null else RuntimeState.Stopped
        return kotlinx.coroutines.withTimeoutOrNull(1000) { owner.observeState().first() }
            ?.takeUnless { it is RuntimeState.Failed }
    }

    private suspend fun environmentsJson(): String {
        val client = ensureSessionRegistry()
        migrateLegacyStorages(client)
        val sessions = client?.list()?.getOrThrow().orEmpty().filter { it.state != dev.terminal.protocol.SessionStatus.EXITED }
        val (environments, profiles) = environmentRegistry.transaction(storageDefaults()) { all() to profiles() }
        return JSONArray(environments.sortedWith(compareBy({ it.runtime.ordinal }, { it.name }, { it.id.value })).map { environment ->
            val linked = profiles.filter { it.environmentId == environment.id }
            val active = sessions.filter { client?.environmentOf(it.id) == environment.id }
            val state = environmentRuntimeState(environment, client)
            val usage = storageRegistry.cachedUsage(environment.storageKey)
            environment.toJson().put("environmentId", environment.id.value).put("sessionRuntime", environment.sessionRuntime.wireName)
                .put("runtimeState", if (state == null) "UNKNOWN" else if (active.isNotEmpty()) "ACTIVE" else state.wireName)
                .put("canDelete", linked.isEmpty() && active.isEmpty() && state != null && environment.storage.id != "legacy")
                .put("bytes", usage?.bytes ?: JSONObject.NULL).put("incomplete", usage?.incomplete ?: false)
                .put("migratedLegacy", dev.terminal.runtime.session.SessionStorage.isMigratedLegacy(filesDir, environment.sessionRuntime, environment.storage.id))
                .put("profiles", JSONArray(linked.map { JSONObject().put("id", it.id).put("name", it.name) }))
                .put("sessions", JSONArray(active.map { JSONObject().put("id", it.id).put("title", it.title) }))
        }).toString()
    }

    private fun hasAvfPrivileges() = checkSelfPermission("android.permission.MANAGE_VIRTUAL_MACHINE") == android.content.pm.PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission("android.permission.USE_CUSTOM_VIRTUAL_MACHINE") == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun newRootlessEnvironmentBackend(environment: Environment): RootlessDebianBackend =
        RootlessDebianBackend(applicationContext,
            distribution = RootlessDistribution.entries.first { it.runtime == environment.sessionRuntime },
            usbBroker = usbBroker,
            storageRoot = dev.terminal.runtime.session.SessionStorage.root(filesDir, environment.sessionRuntime, environment.storage.id)) { phase, detail ->
            broadcast { it.onRootlessProgress("${environment.sessionRuntime.wireName}:${environment.storage.id}", phase.displayName, detail) }
        }

    private fun rootlessEnvironmentBackend(environment: Environment): RootlessDebianBackend {
        check(environment.runtime == dev.terminal.runtime.session.EnvironmentRuntime.ROOTLESS)
        return environmentBackends.getOrPut(environment.id) { newRootlessEnvironmentBackend(environment) } as RootlessDebianBackend
    }

    private fun clearEnvironmentStart(id: EnvironmentId) {
        startedEnvironmentBackends.remove(id)
        explicitlyStartedEnvironments.remove(id)
    }

    private suspend fun verifyRootlessOwnerIdle(owner: RootlessDebianBackend) {
        val controller = owner.sessionController() ?: return
        check(controller.connectionState.value != dev.terminal.protocol.SessionConnectionState.DISCONNECTED) { "Cannot verify environment sessions" }
        check(controller.list().getOrThrow().none { it.state != dev.terminal.protocol.SessionStatus.EXITED }) { "Environment has active sessions" }
    }

    /** Preparation has no session/foreground lease after completion; launch starts the prepared owner. */
    private suspend fun installRootlessEnvironment(environment: Environment, client: UnifiedSessionRegistry) {
        check(environment.runtime == dev.terminal.runtime.session.EnvironmentRuntime.ROOTLESS) { "Only Rootless distributions use this installation operation" }
        preparationLocks.withStorage(environment.sessionRuntime, environment.storage.id) {
            environmentRegistry.transaction(storageDefaults()) { requireEnvironment(environment.id) }
            val owner = rootlessEnvironmentBackend(environment)
            client.deleteStorageIfIdle(environment.sessionRuntime, environment.storage.id) { release ->
                verifyRootlessOwnerIdle(owner)
                owner.stop(StopMode.GRACEFUL).getOrThrow()
                release()
                clearEnvironmentStart(environment.id)
                owner.prepare().getOrThrow()
            }
        }
    }

    private suspend fun removeEnvironmentSoftware(environment: Environment, client: UnifiedSessionRegistry, removeHome: Boolean) {
        check(environment.runtime != dev.terminal.runtime.session.EnvironmentRuntime.NATIVE) { "Native has no removable distribution image" }
        check(!removeHome || environment.runtime == dev.terminal.runtime.session.EnvironmentRuntime.ROOTLESS)
        preparationLocks.withAll {
            client.deleteStorageIfIdle(environment.sessionRuntime, environment.storage.id) { release ->
                environmentRegistry.transaction(storageDefaults()) {
                    removeSoftware(environment.id, removeHome) { current ->
                        kotlinx.coroutines.runBlocking {
                            val owner = environmentBackends[current.id]
                            if (current.sessionRuntime == SessionRuntime.AVF) {
                                check(!RuntimeSetupCoordinator(applicationContext).isInstallationActive(current.storage.id)) { "이미지 설치 중에는 Debian을 제거할 수 없습니다." }
                                check(current.storage.id != "legacy") { "Legacy VM identity must be adopted before removing Debian" }
                                if (owner != null) {
                                    val state = environmentRuntimeState(current, client)
                                    check(state != null && (state is RuntimeState.Stopped || client.isRegistered(current.sessionRuntime, current.storage.id))) { "Cannot verify environment runtime state" }
                                    owner.stop(StopMode.GRACEFUL).getOrThrow()
                                }
                                val avf = owner ?: (applicationContext as? RuntimeBackendProvider)?.createRuntimeBackend(applicationContext)
                                check(avf is dev.terminal.runtime.avf.AvfBackend) { "AVF removal requires a privileged runtime provider" }
                                // The platform queries the persisted VM by identity and rejects running/unreachable instances.
                                // This also permits removing never-booted or verified-stopped images after host restart.
                                avf.deleteStorage(current.storage.id).getOrThrow()
                            } else {
                                (owner as? RootlessDebianBackend)?.let { rootless ->
                                    verifyRootlessOwnerIdle(rootless)
                                    rootless.stop(StopMode.GRACEFUL).getOrThrow()
                                }
                            }
                            release()
                            clearEnvironmentStart(current.id)
                        }
                    }
                }
            }
        }
    }

    private suspend fun removeEnvironment(id: EnvironmentId) {
        val client = ensureSessionRegistry()
        val environment = environmentRegistry.transaction(storageDefaults()) { requireEnvironment(id) }
        preparationLocks.withAll {
            val state = environmentRuntimeState(environment, client)
            check(state != null && state !is RuntimeState.Preparing && state !is RuntimeState.Booting && state !is RuntimeState.Stopping) {
                "Cannot verify environment runtime state"
            }
            val remove: suspend (suspend () -> Unit) -> Unit = { release ->
                environmentRegistry.transaction(storageDefaults()) {
                    // Hold the process-shared lock through the final reference check and removal.
                    requireDeletable(id)
                    kotlinx.coroutines.runBlocking {
                        val owner = environmentBackends[id]
                        val localController = when (owner) {
                            is NativeBackend -> owner.sessionController()
                            is RootlessDebianBackend -> owner.sessionController()
                            else -> null
                        }
                        check(localController?.list()?.getOrThrow().orEmpty().none { it.state != dev.terminal.protocol.SessionStatus.EXITED }) { "Environment has active sessions" }
                        owner?.stop(StopMode.GRACEFUL)?.getOrThrow()
                        release()
                        if (environment.sessionRuntime == SessionRuntime.AVF) {
                            check(owner is dev.terminal.runtime.avf.AvfBackend) { "AVF environment requires a reachable privileged runtime owner" }
                            owner.deleteStorage(environment.storage.id).getOrThrow()
                        }
                        environmentBackends.remove(id)
                        startedEnvironmentBackends.remove(id)
                        explicitlyStartedEnvironments.remove(id)
                    }
                    delete(id)
                }
            }
            if (client != null) client.deleteStorageIfIdle(environment.sessionRuntime, environment.storage.id, remove)
            else {
                check(environmentBackends[id] == null) { "Cannot verify environment sessions" }
                remove {}
            }
            if (client != null) refreshSessions(client)
        }
    }

    private fun defaultStorageId(runtime: SessionRuntime): String =
        dev.terminal.runtime.session.SessionStorage.migratedLegacyId(filesDir, runtime) ?: "legacy"

    private suspend fun attachPrimaryAvf(registry: UnifiedSessionRegistry) {
        if (backend is NativeBackend || latestState !is RuntimeState.Ready && latestState !is RuntimeState.Active && latestState !is RuntimeState.Idle) return
        val id = defaultStorageId(SessionRuntime.AVF)
        if (registry.isRegistered(SessionRuntime.AVF, id) && id !in registry.disconnectedStorages(SessionRuntime.AVF)) return
        if (runCatching { storageRegistry.transaction { validateConnection(dev.terminal.runtime.session.StorageRegistry.Key(SessionRuntime.AVF, id)) } }.isFailure) return
        backend.connectSessionClient().getOrNull()?.let {
            registry.register(environmentForStorage(SessionRuntime.AVF, id), it)
            guestRetry.reset()
            registry.list().getOrNull().orEmpty().filter { session ->
                registry.runtimeOf(session.id) == SessionRuntime.AVF && registry.storageOf(session.id) == id &&
                    session.state == dev.terminal.protocol.SessionStatus.RUNNING
            }.forEach { session -> registry.attach(session.id) }
            environmentBackends[environmentForStorage(SessionRuntime.AVF, id).id] = backend
        }
    }

    /** Runs on service IO/default dispatchers. No UI lifetime owns or stops an environment. */
    private suspend fun migrateLegacyStorages(client: UnifiedSessionRegistry?) = storageMigrationMutex.withLock {
        preparationLocks.withAll {
            val defaults = storageDefaults()
            for (runtime in SessionRuntime.entries) {
                try {
                    val pending = storageRegistry.transaction {
                        migratedLegacyId(runtime) == null &&
                            dev.terminal.runtime.session.StorageRegistry.Key(runtime, "legacy") in
                            (keys() + profiles(defaults).map { dev.terminal.runtime.session.StorageRegistry.Key(it.runtime, it.storageId) })
                    }
                    if (pending) {
                        val localOwner = environmentBackends[environmentForStorage(runtime, "legacy").id]
                        val localController = when (localOwner) {
                            is NativeBackend -> localOwner.sessionController()
                            is RootlessDebianBackend -> localOwner.sessionController()
                            else -> rootlessBackends[runtime]?.sessionController()
                        }
                        if (localController?.list()?.getOrThrow().orEmpty().any { it.state != dev.terminal.protocol.SessionStatus.EXITED }) continue
                        // An unattached persistent VM may still be running after host restart.
                        // Only its reachable PTY owner can prove that changing registration is safe.
                        if (runtime == SessionRuntime.AVF &&
                            ((localOwner ?: backend) !is dev.terminal.runtime.avf.AvfBackend || client?.isRegistered(runtime) != true)) continue
                        val adopt = { environmentRegistry.transaction(defaults) { migrateLegacy(runtime).storageId } }
                        if (client != null) {
                            if (!client.adoptLegacyStorageIfIdle(runtime, adopt)) continue
                        } else adopt()
                    }
                    val id = defaultStorageId(runtime)
                    if (id != "legacy" && storageRegistry.transaction { dev.terminal.runtime.session.StorageRegistry.Key(runtime, id) in keys() }) {
                        rootlessBackends[runtime]?.let { environmentBackends[environmentForStorage(runtime, id).id] = it }
                        if (runtime == SessionRuntime.AVF && backend is dev.terminal.runtime.avf.AvfBackend &&
                            client?.isRegistered(runtime, id) == true) environmentBackends[environmentForStorage(runtime, id).id] = backend
                    }
                } catch (failure: Exception) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    Log.w(TAG, "Legacy storage migration deferred: ${runtime.wireName}", failure)
                }
            }
        }
    }

    private suspend fun ensureEnvironmentController(spec: dev.terminal.runtime.session.SessionLaunchSpec,
        registry: UnifiedSessionRegistry): Boolean {
        // When Native is the default owner, even legacy/default AVF storage needs its own AVF provider.
        if (spec.storageId == "legacy" && spec.runtime != SessionRuntime.NATIVE &&
            (spec.runtime != SessionRuntime.AVF || backend !is NativeBackend)) return ensureRuntimeController(spec.runtime, registry)
        if (spec.runtime == SessionRuntime.AVF && backend !is NativeBackend &&
            spec.storageId == defaultStorageId(SessionRuntime.AVF)) {
            return preparationLocks.withStorage(spec.runtime, spec.storageId) {
                storageRegistry.transaction { validateConnection(dev.terminal.runtime.session.StorageRegistry.Key(spec.runtime, spec.storageId)) }
                startPrimaryBackend()
                attachPrimaryAvf(registry)
                registry.isRegistered(spec.runtime, spec.storageId)
            }
        }
        return preparationLocks.withStorage(spec.runtime, spec.storageId) {
            storageRegistry.transaction { validateConnection(dev.terminal.runtime.session.StorageRegistry.Key(spec.runtime, spec.storageId)) }
            if (registry.isRegistered(spec.runtime, spec.storageId) && spec.storageId !in registry.disconnectedStorages(spec.runtime)) return@withStorage true
            val executionEnvironment = requireNotNull(spec.executionEnvironment)
            val key = executionEnvironment.id
            val root = dev.terminal.runtime.session.SessionStorage.root(filesDir, spec.runtime, spec.storageId)
            val owner = environmentBackends.getOrPut(key) {
                when (spec.runtime) {
                    SessionRuntime.NATIVE -> NativeBackend(applicationContext, root).also { native ->
                        native.configureUsbTooling({ id ->
                            usbBroker.register(id, SessionRuntime.NATIVE.wireName) +
                                dev.terminal.runtime.native.ApkNativeExecutableLoader(applicationContext)
                                    .resolvePackagedExecutable("terminal-usb").getOrNull()?.let {
                                        mapOf("TERMINAL_USB_CLIENT" to it.absolutePath)
                                    }.orEmpty()
                        }, usbBroker::unregister)
                    }
                    SessionRuntime.ROOTLESS, SessionRuntime.ROOTLESS_UBUNTU, SessionRuntime.ROOTLESS_ARCH ->
                        newRootlessEnvironmentBackend(executionEnvironment)
                    SessionRuntime.AVF -> {
                        require(checkSelfPermission("android.permission.MANAGE_VIRTUAL_MACHINE") == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                            checkSelfPermission("android.permission.USE_CUSTOM_VIRTUAL_MACHINE") == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            "AVF requires privileged custom VM access"
                        }
                        val provider = applicationContext as? RuntimeBackendProvider
                            ?: error("AVF system runtime provider is unavailable")
                        provider.createRuntimeBackend(applicationContext).also {
                            check(it is dev.terminal.runtime.avf.AvfBackend && it !== backend && environmentBackends.values.none { existing -> existing === it }) {
                                "AVF provider must create an independent backend for each storage"
                            }
                        }
                    }
                }
            }
            if (spec.runtime == SessionRuntime.AVF && key !in startedEnvironmentBackends) {
                RuntimeSetupCoordinator(applicationContext).awaitInstalledImage(root.toPath(), spec.storageId)
            }
            val preferences = getSharedPreferences(RUNTIME_SETTINGS, MODE_PRIVATE)
            if (key !in startedEnvironmentBackends) {
                owner.prepare(RuntimeConfig(
                    instanceId = if (spec.runtime == SessionRuntime.AVF)
                        requireNotNull(executionEnvironment.runtimeMetadata.vmInstanceId) else spec.storageId,
                    imageDirectory = root.path,
                    memoryMib = preferences.getInt(KEY_AVF_MEMORY, 2048).coerceIn(512, 32768),
                    cpuCount = preferences.getInt(KEY_AVF_CPU, 2).coerceIn(1, 16),
                    protectedVm = preferences.getBoolean(KEY_AVF_PROTECTED, false),
                    enableNetwork = preferences.getBoolean(KEY_AVF_NETWORK, true),
                    sharedDirectory = if (spec.runtime == SessionRuntime.AVF) sharedDirectories.provision(spec.storageId).getOrThrow().path else null,
                )).getOrThrow()
                owner.start().getOrThrow()
                startedEnvironmentBackends.add(key)
            }
            val controller = when (owner) {
                is NativeBackend -> requireNotNull(owner.sessionController())
                is RootlessDebianBackend -> requireNotNull(owner.sessionController())
                else -> owner.connectSessionClient().getOrThrow()
            }
            registry.register(executionEnvironment, controller)
            true
        }
    }

    private suspend fun ensureRuntimeController(runtime: SessionRuntime, registry: UnifiedSessionRegistry): Boolean {
        val selectedRootless = rootlessBackends[runtime]
        if (preparationLocks.isLocked(runtime) && selectedRootless?.status() !is RootlessStatus.Ready) {
            selectedRootless?.reportPreparationWaiting()
        }
        return try {
            preparationLocks.withStorage(runtime) {
                if (registry.isRegistered(runtime)) {
                    val rootless = rootlessBackends[runtime]
                    if (rootless == null || rootless.status() is RootlessStatus.Ready) return@withStorage true
                    registry.removeIfIdle(runtime) { rootless.prepare().getOrThrow() }
                }
                when (runtime) {
                    SessionRuntime.ROOTLESS, SessionRuntime.ROOTLESS_UBUNTU, SessionRuntime.ROOTLESS_ARCH -> {
                        val rootlessBackend = rootlessBackends.getValue(runtime)
                        val prepared = rootlessBackend.prepare()
                        val started = prepared.isSuccess && rootlessBackend.start().isSuccess
                        if (started) rootlessBackend.sessionController()?.let {
                            val environment = environmentForStorage(runtime, "legacy")
                            environmentBackends[environment.id] = rootlessBackend
                            startedEnvironmentBackends.add(environment.id)
                            registry.register(environment, it)
                        }
                        started && registry.isRegistered(runtime)
                    }
                    SessionRuntime.NATIVE -> backend is NativeBackend && (backend as NativeBackend).sessionController()?.let {
                        val environment = environmentForStorage(runtime, "legacy")
                        environmentBackends[environment.id] = backend
                        startedEnvironmentBackends.add(environment.id)
                        registry.register(environment, it); true
                    } == true
                    SessionRuntime.AVF -> backend !is NativeBackend && backend.connectSessionClient().getOrNull()?.let {
                        val environment = environmentForStorage(runtime, "legacy")
                        environmentBackends[environment.id] = backend
                        startedEnvironmentBackends.add(environment.id)
                        registry.register(environment, it); true
                    } == true
                }
            }
        } finally { selectedRootless?.clearPreparationWaiting() }
    }

    private fun hasReconnectWork() = reconnectNeeded(publishedSessions.runningCount,
        hostBackgroundOperationCount.get(), guestReportedWorkActive)

    private fun primaryReady() = latestState is RuntimeState.Ready ||
        latestState is RuntimeState.Active || latestState is RuntimeState.Idle

    private fun resetReconnectBackoff() {
        guestRetry.reset(); bridgeRetry.reset(); portRetry.reset()
        updateReconnectWork()
    }

    @Synchronized private fun updateReconnectWork() {
        if (!hasReconnectWork()) {
            reconnectJob?.cancel(); bridgeJob?.cancel(); portAgentJob?.cancel()
            return
        }
        if (!primaryReady()) return
        scheduleGuestReconnect()
        if (backend !is NativeBackend) {
            scheduleBridgeServer()
            schedulePortAgentCollector()
        }
    }

    @Synchronized private fun scheduleGuestReconnect() {
        if (reconnectJob?.isActive == true || !hasReconnectWork()) return
        reconnectJob = scope.launch {
            while (primaryReady() && hasReconnectWork()) {
                val registry = ensureSessionRegistry()
                if (registry != null) {
                    // Reconnect only failed AVF transports, preserving Native/Rootless owners.
                    for (id in registry.disconnectedStorages(SessionRuntime.AVF)) {
                        // ensureSessionRegistry already attempted the primary transport once.
                        if (id == defaultStorageId(SessionRuntime.AVF)) continue
                        val owner = environmentBackends[environmentForStorage(SessionRuntime.AVF, id).id] ?: continue
                        owner.connectSessionClient().getOrNull()?.let { controller ->
                            registry.register(environmentForStorage(SessionRuntime.AVF, id), controller)
                            guestRetry.reset()
                            registry.list().getOrNull().orEmpty().filter { session ->
                                registry.runtimeOf(session.id) == SessionRuntime.AVF && registry.storageOf(session.id) == id &&
                                    session.state == dev.terminal.protocol.SessionStatus.RUNNING
                            }.forEach { session -> registry.attach(session.id) }
                        }
                    }
                    val primaryMissing = backend !is NativeBackend &&
                        !registry.isRegistered(SessionRuntime.AVF, defaultStorageId(SessionRuntime.AVF))
                    if (!primaryMissing && registry.disconnectedStorages(SessionRuntime.AVF).isEmpty()) {
                        guestRetry.reset()
                        refreshSessions(registry)
                        return@launch
                    }
                }
                guestRetry.awaitRetry()
            }
        }
    }

    @Synchronized private fun scheduleBridgeServer() {
        if (bridgeJob?.isActive == true || !hasReconnectWork()) return
        bridgeJob = scope.launch {
            val broker = AndroidCapabilityBroker(applicationContext, DEFAULT_VM_INSTANCE_ID, capabilityGrants)
            while (primaryReady() && hasReconnectWork()) {
                backend.connectGuestBridge().getOrNull()?.let { connection ->
                    bridgeRetry.reset()
                    try {
                        BridgeHostServer(broker) { work ->
                            guestReportedWorkActive = work == GuestWorkEvent.WORK_ACTIVE
                            updateWakeState()
                            updateServiceLifetime()
                        }.serve(connection)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (error: Exception) { Log.w(TAG, "bridge disconnected: ${error.javaClass.simpleName}") }
                    finally { connection.close() }
                }
                if (hasReconnectWork()) bridgeRetry.awaitRetry()
            }
        }
    }

    @Synchronized private fun schedulePortAgentCollector() {
        if (portAgentJob?.isActive == true || !hasReconnectWork()) return
        portAgentJob = scope.launch {
            while (primaryReady() && hasReconnectWork()) {
                backend.connectGuestPortAgent().getOrNull()?.let { connection ->
                    portRetry.reset()
                    try {
                        PortAgentCollector(
                            registry = portRegistry,
                            onListening = { port -> portForwarder.start(port) },
                            onClosed = { port -> portForwarder.stop(port) },
                        ).collect(connection)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (error: Exception) { Log.w(TAG, "port agent disconnected: ${error.javaClass.simpleName}") }
                    finally { connection.close() }
                }
                if (hasReconnectWork()) portRetry.awaitRetry()
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
        taskNotifications.running(snapshot.sessions.filter { it.state == dev.terminal.protocol.SessionStatus.RUNNING }
            .mapTo(mutableSetOf()) { it.id })
        publishedSessions = published
        updateWakeState()
        updateServiceLifetime()
        Log.d(TAG, "session.snapshot revision=${published.revision} count=${snapshot.sessions.size}")
        broadcast { it.onSessionsChanged(published.revision, published.json) }
    }

    private fun sessionNotificationTitle(sessionId: String): String =
        kittyTitles[sessionId] ?: getSharedPreferences("session-launches", MODE_PRIVATE).getString(sessionId, null)
            ?.let { runCatching { JSONObject(it).getString("name") }.getOrNull() }
            ?: SessionRuntime.fromSessionId(sessionId)?.wireName.orEmpty()

    private fun notifyTerminalOutput(sessionId: String, bytes: ByteArray, replay: Boolean) {
        taskNotifications.output(sessionId, bytes, replay) { sessionNotificationTitle(sessionId) }
        if (!replay) updateWakeState()
        broadcast { it.onTerminalOutput(sessionId, bytes, replay) }
    }

    private fun notifyOperationError(operation: String, message: String) {
        broadcast { it.onOperationError(operation, message) }
    }

    private fun notifySessionTerminationFailed(sessionId: String, message: String) {
        broadcast { it.onSessionTerminationFailed(sessionId, message) }
    }

    private inline fun broadcast(action: (IRuntimeStateListener) -> Unit) {
        synchronized(broadcastLock) {
            val targets = synchronized(listenerLock) {
                val count = listeners.beginBroadcast()
                try { List(count) { listeners.getBroadcastItem(it) } }
                finally { listeners.finishBroadcast() }
            }
            // A bounded terminal reader can wait for the UI to drain its queue. Never hold
            // the registration lock over Binder calls: Activity recreation registers on
            // that same UI thread and would otherwise deadlock against output backpressure.
            targets.forEach { target -> runCatching { action(target) } }
        }
    }

    private fun terminalPalette(json: String): java.util.Properties {
        require(json.length <= 16_384) { "Palette exceeds limit" }
        val value = JSONObject(json)
        require(value.length() <= 259)
        return java.util.Properties().apply {
            value.keys().forEach { key ->
                require(key.length <= 32)
                val color = value.getString(key)
                require(color.length <= 32)
                setProperty(key, color)
            }
        }
    }

    private fun closeSessionClient() {
        terminalDeaths.forEach { (key, death) -> runCatching { key.second.unlinkToDeath(death, 0) } }
        terminalDeaths.clear()
        terminalSinks.clear()
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

    @Synchronized private fun updateWakeState() {
        wakeController.update(wakePolicies.get(),
            if (!hasReconnectWork()) RuntimeWakeController.GuestWorkState.STOPPED
            else runtimeWorkState(latestState, taskNotifications.workActive(), guestReportedWorkActive,
                hostBackgroundOperationCount.get() > 0))
        // One expiry job for unknown-shell leases, never a permanent polling loop.
        if (taskNotifications.nextExpiryMs() == null) {
            wakeExpiryJob?.cancel()
            wakeExpiryJob = null
        } else if (wakeExpiryJob?.isActive != true) {
            wakeExpiryJob = scope.launch {
                while (true) {
                    val wait = taskNotifications.nextExpiryMs() ?: break
                    delay(wait)
                    updateWakeState()
                }
            }
        }
    }

    private fun imageStatus(): String = if (backend is NativeBackend) {
        "Native 사용자 공간 준비됨. AVF Debian은 권한 있는 제품 빌드에서 사용할 수 있습니다."
    } else when (
        val verification = DebianImageVerifier.verifyInstalled(Paths.get(filesDir.absolutePath, "vm", "default"))
    ) {
        is ImageVerification.Ready -> "Debian ARM64 image verified"
        is ImageVerification.Invalid -> if (BundledDebianImageCatalog.find(DEFAULT_DEBIAN_RELEASE_ID) == null) {
            "$verification. 이 제품 빌드에 AVF 자동 설치 이미지가 등록되지 않았습니다."
        } else verification.reason
    }

    private fun List<SessionDescriptor>.toJson(explicitTitleIds: Set<String> = emptySet(), initialTitles: Map<String, String> = emptyMap()): String = pendingAvfLaunches.project(JSONArray().apply {
        forEach { session -> put(JSONObject().apply {
            put("id", session.id)
            getSharedPreferences("session-launches", MODE_PRIVATE).getString(session.id, null)?.let {
                val spec = JSONObject(it)
                put("profileId", spec.getString("profileId"))
                put("launchSpec", spec)
            }
            getSharedPreferences("session-launch-requests", MODE_PRIVATE).getString(session.id, null)?.let {
                put("launchRequestId", it)
            }
            put("runtime", (sessionClient as? UnifiedSessionRegistry)?.runtimeOf(session.id)?.wireName
                ?: SessionRuntime.fromSessionId(session.id)?.wireName
                ?: if (backend is NativeBackend) SessionRuntime.NATIVE.wireName else SessionRuntime.AVF.wireName)
            put("storageId", (sessionClient as? UnifiedSessionRegistry)?.storageOf(session.id) ?: "legacy")
            put("environmentId", (sessionClient as? UnifiedSessionRegistry)?.environmentOf(session.id)?.value ?: JSONObject.NULL)
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
    }.toString())

    private fun rootlessStatusJson(rootlessBackend: RootlessDebianBackend, distribution: RootlessDistribution): JSONObject {
        val rootless = rootlessBackend.status()
        val progress = rootlessBackend.installProgress()
        return JSONObject().apply {
            put("hasData", rootlessBackend.hasSoftwareData())
            put("available", rootless is RootlessStatus.Ready)
            put("canMigrateToNative", rootlessBackend.canMigrateToNative())
            put("installed", rootlessBackend.hasInstalledImage())
            put("installable", rootlessBackend.canInstall())
            put("canCancel", rootlessBackend.canCancelInstallation())
            put("cancelling", rootlessBackend.isInstallationActive() && !rootlessBackend.canCancelInstallation())
            val cancelled = progress?.update?.phase == dev.terminal.runtime.rootless.RootlessPhase.CANCELLED
            put("state", if (cancelled) "CANCELLED" else when (rootless) {
                RootlessStatus.Checking -> "CHECKING"
                RootlessStatus.Ready -> "READY"
                is RootlessStatus.Error -> "ERROR"
                is RootlessStatus.SetupRequired -> "SETUP_REQUIRED"
                is RootlessStatus.EnvironmentRequired -> "ENVIRONMENT_REQUIRED"
            })
            put("detail", if (cancelled) progress!!.update.detail else when (rootless) {
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
        }
    }

    private fun runtimeStatusJson(): String = JSONObject().apply {
        put(SessionRuntime.NATIVE.wireName, JSONObject().apply {
            put("available", backend is NativeBackend)
            put("detail", if (backend is NativeBackend) "즉시 사용 가능" else "이 제품 변형은 Native 컨트롤러를 제공하지 않습니다")
        })
        RootlessDistribution.entries.forEach { distribution ->
            put(distribution.runtime.wireName, rootlessStatusJson(rootlessBackends.getValue(distribution.runtime), distribution))
        }
        val avf = avfRuntimeAvailability(backend, environmentBackends.values.filterIsInstance<dev.terminal.runtime.avf.AvfBackend>())
        put(SessionRuntime.AVF.wireName, JSONObject().apply {
            put("available", avf.available)
            put("detail", avf.detail)
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
        updateReconnectWork()
        scope.launch(Dispatchers.Main.immediate) {
            // Read current state here, not a captured snapshot from a worker thread.
            val keepAlive = hasReconnectWork() || explicitlyStartedEnvironments.isNotEmpty()
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
        count to if (count > 0) "" else if (explicitlyStartedEnvironments.isNotEmpty())
            "실행 중인 환경 ${explicitlyStartedEnvironments.size}개" else latestState.detail()
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
            if (publishedSessions.runningCount > 0) "실행 중인 세션 ${publishedSessions.runningCount}개"
            else if (explicitlyStartedEnvironments.isNotEmpty()) "실행 중인 환경 ${explicitlyStartedEnvironments.size}개"
            else "런타임 준비 중",
        )
        .setContentText(
            if (publishedSessions.runningCount > 0) "터미널을 닫아도 세션은 백그라운드에서 계속 실행됩니다."
            else if (explicitlyStartedEnvironments.isNotEmpty()) "환경 상세에서 실행 환경을 중지할 수 있습니다."
            else state.detail(),
        )
        .setOngoing(true)
        .setAutoCancel(false)
        .setDeleteIntent(PendingIntent.getService(
            this,
            2,
            Intent(this, VmRuntimeService::class.java).setAction(ACTION_RESTORE_SESSION_NOTIFICATION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ))
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
        const val ACTION_RESTORE_SESSION_NOTIFICATION = "dev.terminal.runtime.action.RESTORE_SESSION_NOTIFICATION"
        const val CHANNEL_ID = "linux_runtime"
        const val NOTIFICATION_ID = 1001
        const val FORCE_STOP = 1
        const val SIGKILL = 9
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
    sessionWorkActive: Boolean,
    guestReportedWorkActive: Boolean,
    hostBackgroundWorkActive: Boolean,
): RuntimeWakeController.GuestWorkState = when {
    runtimeState is RuntimeState.Stopping || runtimeState is RuntimeState.Failed ->
        RuntimeWakeController.GuestWorkState.STOPPED
    hostBackgroundWorkActive -> RuntimeWakeController.GuestWorkState.ACTIVE
    runtimeState is RuntimeState.Stopped -> RuntimeWakeController.GuestWorkState.STOPPED
    runtimeState is RuntimeState.Suspended -> RuntimeWakeController.GuestWorkState.IDLE
    sessionWorkActive || guestReportedWorkActive -> RuntimeWakeController.GuestWorkState.ACTIVE
    else -> RuntimeWakeController.GuestWorkState.IDLE
}
