package dev.terminal.runtime.avf.system

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.system.virtualmachine.VirtualMachine
import android.system.virtualmachine.VirtualMachineConfig
import android.system.virtualmachine.VirtualMachineCustomImageConfig
import android.system.virtualmachine.VirtualMachineManager
import dev.terminal.image.AvfImageLayout
import dev.terminal.image.AvfImageInstaller
import dev.terminal.image.ImageVerification
import dev.terminal.runtime.api.GuestConnection
import dev.terminal.runtime.api.GuestByteStream
import dev.terminal.runtime.api.RuntimeConfig
import dev.terminal.runtime.api.StopMode
import dev.terminal.runtime.avf.AvfPlatformDriver
import dev.terminal.runtime.avf.AvfPlatformEvent
import dev.terminal.runtime.storage.AvfSharedDirectoryLeases
import dev.terminal.runtime.vsock.ParcelFileDescriptorFrameTransport
import dev.terminal.runtime.vsock.ParcelFileDescriptorByteStream
import java.nio.file.Paths
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * AVF custom-image driver for a platform-signed AOSP product build.
 *
 * Compiled against compile-only AOSP API declarations and packaged in the APK.
 * The device must provide the matching implementation and authorize the custom-image APIs.
 * UI code sees only [AvfPlatformDriver].
 */
class AospAvfPlatformDriver(
    context: Context,
) : AvfPlatformDriver {
    private val appContext = context.applicationContext
    private val manager = checkNotNull(avfVirtualMachineManager(appContext)) {
        "AVF VirtualMachineManager is unavailable on this device"
    }
    private val mutex = Mutex()
    @Volatile override var startedNewVm: Boolean = false
        private set

    private var prepared: RuntimeConfig? = null
    private var sessionResumeVersion = 0
    private var rootDevice = "/dev/vda"
    private var rootMountMode = "ro"
    @Volatile private var machine: VirtualMachine? = null
    private val activeBoot = AtomicReference<Any?>()
    private val shareLeases = AvfSharedDirectoryLeases(appContext.filesDir)
    private val sharedStorage = AvfSharedStorageCoordinator(shareLeases,
        android.os.Environment::isExternalStorageManager, ::stopStorageInstance)
    private val pendingSessionConnection = AtomicReference<GuestConnection?>()
    private val events = MutableSharedFlow<AvfPlatformEvent>(extraBufferCapacity = 4,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    override fun observeEvents(): Flow<AvfPlatformEvent> = events.asSharedFlow()

    override suspend fun prepare(config: RuntimeConfig): Result<Unit> = mutex.withLock {
        dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record("vm.prepare", config.instanceId,
            "cpu_policy:${if (config.cpuCount == 1) "single" else "host"}.memory_mib:${config.memoryMib}.protected:${config.protectedVm}.network:${config.enableNetwork}")
        runCatching {
            require(VM_NAME.matches(config.instanceId)) {
                "VM instance ID may contain only letters, digits, '.', '_' and '-'"
            }
            require(config.memoryMib >= MIN_MEMORY_MIB) { "VM memory must be at least $MIN_MEMORY_MIB MiB" }
            require(config.cpuCount >= 1) { "VM CPU count must be positive" }
            val verificationStartedAt = SystemClock.elapsedRealtime()
            val verification = AvfImageInstaller.verifyForStartup(Paths.get(config.imageDirectory),
                dev.terminal.image.AvfDistribution.fromId(config.imageDistribution))
            Log.i(TAG, "image.verify duration_ms=${SystemClock.elapsedRealtime() - verificationStartedAt} ready=${verification is ImageVerification.Ready}")
            when (verification) {
                is ImageVerification.Ready -> {
                    rootDevice = verification.manifest.rootDevice
                    rootMountMode = verification.manifest.rootMountMode
                    val capabilities = dev.terminal.image.AvfGuestComponents.effective(Paths.get(config.imageDirectory), verification.manifest)
                    sessionResumeVersion = capabilities.sessionResumeVersion
                    if (config.avfMaintenance != null) {
                        require(!config.protectedVm)
                        require(verification.manifest.maintenanceVersion == 1) { "새 관리 기능을 포함한 이미지가 필요합니다." }
                    }
                }
                is ImageVerification.Invalid -> error(verification.reason)
            }
            prepared = config
        }.onSuccess { dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record("vm.prepared", config.instanceId,
            "${config.imageDistribution}.resume:$sessionResumeVersion") }
            .onFailure { dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record("vm.prepare.failed", config.instanceId, error = it) }
    }

    override suspend fun start(config: RuntimeConfig): Result<Unit> = mutex.withLock {
        dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record("vm.start", config.instanceId,
            details = mapOf("memory_mib" to config.memoryMib.toString(), "cpu_policy" to if (config.cpuCount == 1) "single" else "host",
                "network" to config.enableNetwork.toString(), "protected" to config.protectedVm.toString(),
                "resume_version" to sessionResumeVersion.toString()))
        startedNewVm = false
        val bootStartedAt = SystemClock.elapsedRealtime()
        var nextMachine: VirtualMachine? = null
        var startedHere = false
        try {
            check(prepared == config) { "Prepare the exact image configuration before starting" }
            check(pendingSessionConnection.get() == null) { "VM session connection is already prepared" }
            val vm = machine ?: manager.get(vmName(config)) ?: manager.getOrCreate(vmName(config), buildConfig(config))
            nextMachine = vm
            val boot = AvfGuestBoot()
            sharedStorage.withoutShares {
                // Old running guests may still have host mounts. Preserve their data and
                // require an explicit stop rather than silently adopting a shared VM.
                shareLeases.requireUnsharedReconnect(config.instanceId, vm.status == VirtualMachine.STATUS_STOPPED)
                val token = Any()
                activeBoot.set(token)
                vm.setCallback(Executor { it.run() }, avfCallback(boot, { activeBoot.get() === token }, config.instanceId, ::markMachineStopped, events::tryEmit))
                machine = vm
                if (vm.status == VirtualMachine.STATUS_STOPPED) {
                    releaseStoppedShares(config.instanceId)
                    // getOrCreate retains existing configs; refresh stopped VMs to remove
                    // legacy SharedPaths and broker boot parameters without replacing disks.
                    requireAvfPlatformApi { vm.setConfig(buildConfig(config)) }
                    startedHere = true
                    vm.run()
                    dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record("vm.run", config.instanceId)
                }
            }
            pendingSessionConnection.set(boot.awaitSessiond(BOOT_TIMEOUT_MS, GUEST_CONNECT_RETRY_MS) {
                sessionConnection(vm)
            })
            check(machine === vm) { "AVF stopped while connecting to the Linux session daemon" }
            Log.i(TAG, "vm.sessiond duration_ms=${SystemClock.elapsedRealtime() - bootStartedAt} reused=${!startedHere}")
            startedNewVm = startedHere
            dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record("vm.connected", config.instanceId,
                if (startedHere) "new" else "reused")
            Result.success(Unit)
        } catch (failure: Throwable) {
            dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record("vm.start.failed", config.instanceId, error = failure)
            pendingSessionConnection.getAndSet(null)?.let { connection ->
                runCatching { connection.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            // Retain existing running guests on a reconnect failure. Stop only a new boot
            // owned by this operation, so a timeout does not leave an orphan VM behind.
            if (startedHere) nextMachine?.let { vm ->
                val stopped = runCatching {
                    if (vm.status != VirtualMachine.STATUS_STOPPED) vm.stop()
                    vm.status == VirtualMachine.STATUS_STOPPED
                }.getOrElse { failure.addSuppressed(it); false }
                if (stopped) {
                    runCatching { releaseStoppedShares(config.instanceId) }.exceptionOrNull()?.let(failure::addSuppressed)
                    if (machine === vm) machine = null
                }
            }
            if (failure is CancellationException) throw failure
            val reported = describeAvfNetworkFailure(failure)
            if (reported !== failure) Log.w(TAG, "vm.network fixed TAP denied", failure)
            Result.failure(reported)
        }
    }

    override suspend fun stop(mode: StopMode): Result<Unit> = mutex.withLock {
        dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record("vm.stop.request", prepared?.instanceId, mode.name)
        runCatching {
            val activeMachine = machine ?: prepared?.let { manager.get(vmName(it)) } ?: return@runCatching
            // AVF stop is immediate. A graceful guest shutdown RPC belongs to LifecycleService.
            if (activeMachine.status != VirtualMachine.STATUS_STOPPED) activeMachine.stop()
            check(activeMachine.status == VirtualMachine.STATUS_STOPPED) { "AVF stop could not be verified" }
            // Legacy sharing records are released only after platform-proven stop.
            prepared?.let { releaseStoppedShares(it.instanceId) }
            pendingSessionConnection.getAndSet(null)?.close()
            machine = null
            activeBoot.set(null)
            startedNewVm = false
        }.onSuccess { dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record("vm.stop.completed", prepared?.instanceId) }
            .onFailure { dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record("vm.stop.failed", prepared?.instanceId, error = it) }
    }

    override suspend fun verifyStoppedInstance(instanceId: String): Result<Unit> = mutex.withLock {
        runCatching {
            require(VM_NAME.matches(instanceId))
            val persisted = manager.get("droid-$instanceId")
            check(persisted == null || persisted.status == VirtualMachine.STATUS_STOPPED) {
                "공유 디렉토리 데이터를 삭제하기 전에 AVF 실행환경을 중지하세요. 연결 해제만으로 기존 마운트는 사라지지 않습니다."
            }
            releaseStoppedShares(instanceId)
        }
    }

    override suspend fun revokeSharedStorage(): Result<Unit> = runCatching { sharedStorage.revoke(); Unit }

    override suspend fun discardMaintenance(instanceId: String): Result<Unit> = mutex.withLock {
        runCatching {
            require(instanceId.matches(Regex("maintenance-[a-f0-9]{32}")))
            val name = "droid-$instanceId"
            manager.get(name)?.let { vm ->
                if (vm.status != VirtualMachine.STATUS_STOPPED) vm.stop()
                check(vm.status == VirtualMachine.STATUS_STOPPED) { "관리 VM의 중지를 확인할 수 없습니다." }
                manager.delete(name)
            }
            Unit
        }
    }

    private fun stopStorageInstance(instanceId: String) {
        val vm = manager.get("droid-$instanceId")
        if (vm != null) {
            if (vm.status != VirtualMachine.STATUS_STOPPED) vm.stop()
            check(vm.status == VirtualMachine.STATUS_STOPPED) { "Cannot verify VM stopped after storage permission revocation" }
        }
        releaseStoppedShares(instanceId)
    }

    override suspend fun deleteStorage(instanceId: String): Result<Unit> = mutex.withLock {
        runCatching {
            dev.terminal.runtime.session.SessionStorage.validate(instanceId)
            require(instanceId != "legacy") { "Legacy storage is protected" }
            val name = "droid-${dev.terminal.runtime.session.SessionStorage.avfInstanceId(appContext.filesDir, instanceId)}"
            val persisted = manager.get(name)
            if (persisted != null) {
                check(persisted.status == VirtualMachine.STATUS_STOPPED) { "AVF instance must be stopped before storage deletion" }
                manager.delete(name)
            }
            releaseStoppedShares(dev.terminal.runtime.session.SessionStorage.avfInstanceId(appContext.filesDir, instanceId))
            prepared = null
        }
    }

    override suspend fun connectGuestAgent(): Result<GuestConnection> = connectionResult {
        mutex.withLock { pendingSessionConnection.getAndSet(null) }
            ?: withActiveMachine { vm -> sessionConnection(vm) }
    }

    private suspend fun sessionConnection(vm: VirtualMachine): GuestConnection = if (sessionResumeVersion == 1) {
        // Keep legacy handshakes on IO; prompt coroutine cancellation must also
        // close a connection returned just before dispatching back to the caller.
        val opened = AtomicReference<GuestConnection?>()
        try {
            kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
                dev.terminal.runtime.vsock.ResumableGuestConnection(GUEST_CID_UNAVAILABLE,
                    connect = { ParcelFileDescriptorByteStream(vm.connectVsock(40_507)) },
                    report = { event, code, error, details -> dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record(
                        event, prepared?.instanceId, code = code, error = error, details = details) }).also { opened.set(it) }
            }.also { opened.set(null) }
        } finally { opened.getAndSet(null)?.let { runCatching { it.close() } } }
    } else ParcelFileDescriptorFrameTransport(connectVsock(vm, DROID_SESSIOND_PORT), GUEST_CID_UNAVAILABLE)

    override suspend fun connectGuestBridge(): Result<GuestConnection> = connectGuestPort(DROID_AGENT_PORT)
    override suspend fun connectImageInitializer(): Result<GuestConnection> = connectGuestPort(40_503)
    override suspend fun connectGuestPortAgent(): Result<GuestConnection> = connectGuestPort(DROID_PORT_AGENT_PORT)

    override suspend fun openGuestFileTransfer(): Result<GuestByteStream> = openGuestStream(40_505)

    override suspend fun openGuestTcpForward(address: String, port: Int): Result<GuestByteStream> {
        require(port in 1..65_535 && dev.terminal.protocol.PortPayloadCodec.validAddress(address))
        return connectionResult {
            withActiveMachine { vm ->
                val stream = ParcelFileDescriptorByteStream(connectVsock(vm, 40_504))
                try {
                    // Closing on cancellation also unblocks a native handshake write.
                    withTimeout(PLATFORM_CONNECT_TIMEOUT_MS) {
                        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
                        val closer = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                            try { kotlinx.coroutines.awaitCancellation() } finally { if (!completed.get()) stream.close() }
                        }
                        try {
                            kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
                                val encoded = address.toByteArray(Charsets.US_ASCII)
                                stream.output.write(byteArrayOf(1, (port shr 8).toByte(), port.toByte(), encoded.size.toByte()) + encoded)
                                stream.output.flush()
                            }
                            completed.set(true)
                        } finally { closer.cancel() }
                    }
                    stream
                } catch (failure: Throwable) { stream.close(); throw failure }
            }
        }
    }

    override suspend fun openGuestPortForward(port: Int): Result<GuestByteStream> {
        require(port in 1024..65_535) { "Invalid guest forwarded port" }
        return openGuestStream(port.toLong())
    }

    private suspend fun openGuestStream(port: Long): Result<GuestByteStream> = connectionResult {
        withActiveMachine { vm -> ParcelFileDescriptorByteStream(connectVsock(vm, port)) }
    }

    private suspend fun connectGuestPort(port: Long): Result<GuestConnection> = connectionResult {
        withActiveMachine { vm ->
            withTimeout(GUEST_CONNECT_TIMEOUT_MS) {
                var connection: GuestConnection? = null
                while (connection == null) {
                    connection = try { ParcelFileDescriptorFrameTransport(connectVsock(vm, port), GUEST_CID_UNAVAILABLE) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    if (connection == null) delay(GUEST_CONNECT_RETRY_MS)
                }
                connection
            }
        }
    }

    /** Only snapshot and commit hold the lifecycle lock, never a Binder connect or handshake. */
    private suspend fun <T : AutoCloseable> withActiveMachine(connect: suspend (VirtualMachine) -> T): T {
        val (vm, token) = mutex.withLock { checkNotNull(machine) { "VM is not running" } to activeBoot.get() }
        val result = connect(vm)
        try {
            mutex.withLock { check(machine === vm && activeBoot.get() === token) { "AVF owner changed while connecting" } }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            return result
        } catch (failure: Throwable) { runCatching { result.close() }; throw failure }
    }

    private suspend fun <T> connectionResult(action: suspend () -> T): Result<T> = try { Result.success(action()) }
        catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            Result.failure(java.io.IOException("Guest channel connection timed out", timeout))
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { Result.failure(failure) }

    private suspend fun connectVsock(vm: VirtualMachine, port: Long): android.os.ParcelFileDescriptor =
        dev.terminal.runtime.vsock.GuestConnectionOpener.openSuspending(PLATFORM_CONNECT_TIMEOUT_MS) { vm.connectVsock(port) }

    private fun buildConfig(config: RuntimeConfig): VirtualMachineConfig = requireAvfPlatformApi {
        val image = AvfImageLayout(Paths.get(config.imageDirectory))
        val customImage = VirtualMachineCustomImageConfig.Builder()
            .setName(vmName(config))
            .setKernelPath(image.kernelImage.toString())
            .setInitrdPath(image.initrdImage.toString())
            .addParam("root=$rootDevice")
            .addParam("rootfstype=ext4")
            .addParam(rootMountMode)
            .addParam("console=hvc0")
            .addParam("systemd.hostname=localhost")
            .useNetwork(config.enableNetwork)
            .useAutoMemoryBalloon(true)
            // The guest fstab is part of the signed image contract: vda=system, vdb=home, vdc=config.
            .addDisk(VirtualMachineCustomImageConfig.Disk.RODisk(image.systemImage.toString()))
            .addDisk(VirtualMachineCustomImageConfig.Disk.RWDisk(image.homeImage.toString()))
            .addDisk(VirtualMachineCustomImageConfig.Disk.RODisk(image.configImage.toString()))
        config.avfMaintenance?.let { maintenance ->
            customImage.addParam("term.maintenance=${maintenance.operation}")
                .addParam("term.source-format=${maintenance.sourceFormatVersion}")
                .addDisk(VirtualMachineCustomImageConfig.Disk.RODisk(maintenance.sourceSystem))
                .addDisk(VirtualMachineCustomImageConfig.Disk.RWDisk(maintenance.copiedState))
        }

        VirtualMachineConfig.Builder(appContext)
            .setProtectedVm(config.protectedVm)
            .setMemoryBytes(config.memoryMib.toLong() * MIB)
            .setCpuTopology(
                if (config.cpuCount == 1) {
                    VirtualMachineConfig.CPU_TOPOLOGY_ONE_CPU
                } else {
                    VirtualMachineConfig.CPU_TOPOLOGY_MATCH_HOST
                },
            )
            .setCustomImageConfig(customImage.build())
            .setDebugLevel(VirtualMachineConfig.DEBUG_LEVEL_NONE)
            .setVmOutputCaptured(false)
            .setConnectVmConsole(false)
            .build()
    }

    private fun markMachineStopped(stoppedMachine: VirtualMachine, instanceId: String) {
        // An error callback alone is not proof of stop. Keep a live/unknown VM's
        // Binder reference and let its session connection recover independently.
        if (!runCatching { stoppedMachine.status == VirtualMachine.STATUS_STOPPED }.getOrDefault(false)) return
        runCatching { releaseStoppedShares(instanceId) }
            .onFailure { Log.w(TAG, "legacy AVF share cleanup failed", it) }
        if (machine === stoppedMachine) {
            machine = null
            pendingSessionConnection.getAndSet(null)?.let { runCatching { it.close() } }
        }
    }

    private fun releaseStoppedShares(instanceId: String) {
        shareLeases.release(instanceId) { artifact -> artifact?.let {
            LegacyAvfShareArtifacts.cleanup(appContext.filesDir.canonicalFile, it, publicSharesRoot())
        } }
    }

    private fun publicSharesRoot() = dev.terminal.runtime.storage.AndroidSharedFolderStorage.root(appContext)

    private fun vmName(config: RuntimeConfig): String = "droid-${config.instanceId}"

    private companion object {
        const val TAG = "TermAvfDriver"
        val VM_NAME = Regex("[A-Za-z0-9._-]{1,64}")
        const val MIN_MEMORY_MIB = 512
        const val MIB = 1024L * 1024L
        const val DROID_SESSIOND_PORT = 40500L
        const val DROID_AGENT_PORT = 40501L
        const val DROID_PORT_AGENT_PORT = 40502L
        const val GUEST_CID_UNAVAILABLE = -1
        const val BOOT_TIMEOUT_MS = 120_000L
        const val GUEST_CONNECT_TIMEOUT_MS = 60_000L
        const val PLATFORM_CONNECT_TIMEOUT_MS = 10_000L
        const val GUEST_CONNECT_RETRY_MS = 500L
    }
}
