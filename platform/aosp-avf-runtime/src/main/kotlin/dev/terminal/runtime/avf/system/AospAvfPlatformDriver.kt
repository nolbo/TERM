package dev.terminal.runtime.avf.system

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.system.virtualmachine.VirtualMachine
import android.system.virtualmachine.VirtualMachineCallback
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine

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
    private var supportsVirtiofs = false
    private var supportsAndroidStorage = false
    private var supportsShareBroker = false
    @Volatile private var machine: VirtualMachine? = null
    private data class SharedBoot(val vm: VirtualMachine, val instanceId: String, val directories: AvfSharedDirectories)
    private val sharedBoot = AtomicReference<SharedBoot?>()
    private data class PrivateBoot(val vm: VirtualMachine, val instanceId: String, val shares: PrivateAvfShares)
    private val privateBoot = AtomicReference<PrivateBoot?>()
    private val activeBoot = AtomicReference<Any?>()
    private val shareLeases = AvfSharedDirectoryLeases(appContext.filesDir)
    private val sharedStorage = AvfSharedStorageCoordinator(shareLeases,
        android.os.Environment::isExternalStorageManager, ::stopStorageInstance)
    private val pendingSessionConnection = AtomicReference<GuestConnection?>()
    private val events = MutableSharedFlow<AvfPlatformEvent>(extraBufferCapacity = 4)

    override fun observeEvents(): Flow<AvfPlatformEvent> = events.asSharedFlow()

    override suspend fun prepare(config: RuntimeConfig): Result<Unit> = mutex.withLock {
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
                    supportsVirtiofs = verification.manifest.virtiofsVersion == 1
                    supportsAndroidStorage = verification.manifest.androidStorageVersion == 1
                    supportsShareBroker = verification.manifest.shareBrokerVersion == 1
                    AvfSharedDirectories.validate(appContext.filesDir.canonicalFile, config.sharedFolders, publicSharesRoot(),
                        false)
                    require(config.sharedFolders.isEmpty() || supportsShareBroker) {
                        "이 이미지는 앱 전용 공유 디렉토리를 지원하지 않습니다. 새 공유 기능을 포함한 AVF 이미지가 필요합니다. 기존 VM 데이터는 유지됩니다."
                    }
                    require(config.sharedFolders.all { java.io.File(it.directory).absoluteFile ==
                        java.io.File(appContext.filesDir.canonicalFile, "shared-folders/${it.id}") }) {
                        "설정 → 공유 디렉토리에서 이 폴더를 저장해 최신 데이터를 앱 전용 저장소로 전환하세요."
                    }
                    require(config.sharedFolders.isEmpty() || !config.protectedVm) {
                        "앱 전용 공유 디렉토리는 비보호 AVF VM에서만 사용할 수 있습니다."
                    }
                }
                is ImageVerification.Invalid -> error(verification.reason)
            }
            prepared = config
        }
    }

    override suspend fun start(config: RuntimeConfig): Result<Unit> = mutex.withLock {
        startedNewVm = false
        val bootStartedAt = SystemClock.elapsedRealtime()
        var nextMachine: VirtualMachine? = null
        var startedHere = false
        var stagedShares: AvfSharedDirectories? = null
        try {
            check(prepared == config) { "Prepare the exact image configuration before starting" }
            check(pendingSessionConnection.get() == null) { "VM session connection is already prepared" }
            // Do not provision or replace sharing artifacts while reconnecting to a live guest.
            val vm = machine ?: manager.get(vmName(config)) ?: manager.getOrCreate(vmName(config), buildConfig(config))
            nextMachine = vm
            val boot = AvfGuestBoot()
            sharedStorage.boot(config.instanceId) { storageGranted ->
                val token = Any()
                activeBoot.set(token)
                vm.setCallback(Executor { it.run() }, callback(boot, token, config.instanceId))
                machine = vm
                // A previous host/boot timeout may have left this persistent VM running.
                // Reconnect to it without restarting Linux or replacing its writable disks.
                if (vm.status == VirtualMachine.STATUS_STOPPED) {
                    // getOrCreate ignores the supplied config for an existing VM. Refresh
                    // stopped instances so boot parameters also reach installed guests.
                    releaseSharedBoot(vm)
                    releaseStoppedShares(config.instanceId)
                    val storage = if (config.enableInternalStorage && supportsAndroidStorage && storageGranted && !config.protectedVm)
                        android.os.Environment.getExternalStorageDirectory().canonicalFile else null
                    stagedShares = if (supportsVirtiofs)
                        AvfSharedDirectories.create(appContext.filesDir.canonicalFile, emptyList(), storage) else null
                    requireAvfPlatformApi { vm.setConfig(buildConfig(config, stagedShares)) }
                    val shares = stagedShares
                    if (shares != null) {
                        // Persist before run: a lost host owner must never make a live export deletable.
                        if (shares.requiresStoragePermission) sharedStorage.requirePermission()
                        shareLeases.record(config.instanceId, config.sharedFolders.map { it.id }, shares.artifact, shares.requiresStoragePermission)
                        sharedBoot.set(SharedBoot(vm, config.instanceId, shares))
                        stagedShares = null
                    }
                    if (config.sharedFolders.isNotEmpty()) {
                        // Private SFTP streams need no SharedPath/index or all-files permission.
                        // Merge the lease with an optional /sdcard export before VM run.
                        shareLeases.record(config.instanceId, config.sharedFolders.map { it.id }, shares?.artifact,
                            shares?.requiresStoragePermission == true)
                        val owner = PrivateAvfShares(config.sharedFolders)
                        PrivateShareOwners.register(privateShareKey(config.instanceId), owner)
                        privateBoot.set(PrivateBoot(vm, config.instanceId, owner))
                    }
                    startedHere = true
                    if (shares?.requiresStoragePermission == true) sharedStorage.requirePermission()
                    vm.run()
                }
            }
            if (config.sharedFolders.isNotEmpty()) {
                val owner = checkNotNull(privateBoot.get()?.takeIf { it.vm === vm }) {
                    "앱 전용 공유 연결의 소유자가 없습니다. VM을 중지한 뒤 다시 시작하세요."
                }
                if (!owner.shares.ready) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val deadline = SystemClock.elapsedRealtime() + 45_000
                    owner.shares.start {
                        var stream: GuestByteStream? = null
                        while (stream == null) {
                            check(SystemClock.elapsedRealtime() < deadline && machine === vm) { "앱 전용 공유 연결 시간이 초과되었습니다." }
                            stream = runCatching { ParcelFileDescriptorByteStream(vm.connectVsock(40_506)) }.getOrNull()
                            if (stream == null) Thread.sleep(100)
                        }
                        stream
                    }
                }
            }
            pendingSessionConnection.set(boot.awaitSessiond(BOOT_TIMEOUT_MS, GUEST_CONNECT_RETRY_MS) {
                ParcelFileDescriptorFrameTransport(vm.connectVsock(DROID_SESSIOND_PORT), GUEST_CID_UNAVAILABLE)
            })
            check(machine === vm) { "AVF stopped while connecting to the Linux session daemon" }
            Log.i(TAG, "vm.sessiond duration_ms=${SystemClock.elapsedRealtime() - bootStartedAt} reused=${!startedHere}")
            startedNewVm = startedHere
            Result.success(Unit)
        } catch (failure: Throwable) {
            stagedShares?.let { runCatching { it.close() }.exceptionOrNull()?.let(failure::addSuppressed) }
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
                    releaseSharedBoot(vm)
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
        runCatching {
            val activeMachine = machine ?: prepared?.let { manager.get(vmName(it)) } ?: return@runCatching
            // AVF stop is immediate. A graceful guest shutdown RPC belongs to LifecycleService.
            if (activeMachine.status != VirtualMachine.STATUS_STOPPED) activeMachine.stop()
            check(activeMachine.status == VirtualMachine.STATUS_STOPPED) { "AVF stop could not be verified" }
            releaseSharedBoot(activeMachine)
            // Reconnected VMs have a persisted lease but no in-memory SharedBoot owner.
            prepared?.let { releaseStoppedShares(it.instanceId) }
            pendingSessionConnection.getAndSet(null)?.close()
            machine = null
            activeBoot.set(null)
            startedNewVm = false
        }
    }

    override suspend fun verifyStoppedInstance(instanceId: String): Result<Unit> = mutex.withLock {
        runCatching {
            require(VM_NAME.matches(instanceId))
            val persisted = manager.get("droid-$instanceId")
            check(persisted == null || persisted.status == VirtualMachine.STATUS_STOPPED) {
                "공유 디렉토리 데이터를 삭제하기 전에 AVF 실행환경을 중지하세요. 연결 해제만으로 기존 마운트는 사라지지 않습니다."
            }
            persisted?.let(::releaseSharedBoot)
            releaseStoppedShares(instanceId)
        }
    }

    override suspend fun revokeSharedStorage(): Result<Unit> = runCatching { sharedStorage.revoke(); Unit }

    private fun stopStorageInstance(instanceId: String) {
        val vm = manager.get("droid-$instanceId")
        if (vm != null) {
            if (vm.status != VirtualMachine.STATUS_STOPPED) vm.stop()
            check(vm.status == VirtualMachine.STATUS_STOPPED) { "Cannot verify VM stopped after storage permission revocation" }
            releaseSharedBoot(vm)
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

    override suspend fun connectGuestAgent(): Result<GuestConnection> {
        val pending = mutex.withLock {
            pendingSessionConnection.getAndSet(null)
        }
        return pending?.let { Result.success(it) } ?: connectGuestPort(DROID_SESSIOND_PORT)
    }

    override suspend fun connectGuestBridge(): Result<GuestConnection> = connectGuestPort(DROID_AGENT_PORT)
    override suspend fun connectImageInitializer(): Result<GuestConnection> = connectGuestPort(40_503)

    override suspend fun connectGuestPortAgent(): Result<GuestConnection> = connectGuestPort(DROID_PORT_AGENT_PORT)
    override suspend fun openGuestFileTransfer(): Result<GuestByteStream> = runCatching {
        val activeMachine = mutex.withLock { checkNotNull(machine) { "VM is not running" } }
        // A platform connect may block. Do not hold the VM lifecycle mutex or the
        // caller's job while it does; a late descriptor is closed after cancellation.
        suspendCancellableCoroutine<GuestByteStream> { continuation ->
            try {
                FILE_CONNECTORS.execute {
                    try {
                        val stream = ParcelFileDescriptorByteStream(activeMachine.connectVsock(40_505))
                        continuation.resume(stream) { _, acquired, _ -> acquired.close() }
                    } catch (failure: Exception) { continuation.resumeWith(Result.failure(failure)) }
                }
            } catch (failure: java.util.concurrent.RejectedExecutionException) {
                continuation.resumeWith(Result.failure(IllegalStateException("Guest file connections are busy; retry later", failure)))
            }
        }
    }

    override suspend fun openGuestTcpForward(address: String, port: Int): Result<GuestByteStream> {
        require(port in 1..65_535 && dev.terminal.protocol.PortPayloadCodec.validAddress(address))
        return mutex.withLock {
            runCatching {
                val stream = ParcelFileDescriptorByteStream(checkNotNull(machine) { "VM is not running" }.connectVsock(40_504))
                try {
                    val encoded = address.toByteArray(Charsets.US_ASCII)
                    stream.output.write(byteArrayOf(1, (port shr 8).toByte(), port.toByte(), encoded.size.toByte()) + encoded)
                    stream.output.flush()
                    stream
                } catch (failure: Exception) { stream.close(); throw failure }
            }
        }
    }

    override suspend fun openGuestPortForward(port: Int): Result<GuestByteStream> {
        require(port in 1024..65_535) { "Invalid guest forwarded port" }
        return mutex.withLock {
            runCatching {
                val activeMachine = checkNotNull(machine) { "VM is not running" }
                ParcelFileDescriptorByteStream(activeMachine.connectVsock(port.toLong()))
            }
        }
    }

    private suspend fun connectGuestPort(port: Long): Result<GuestConnection> = mutex.withLock {
        runCatching {
            val activeMachine = checkNotNull(machine) { "VM is not running" }
            withTimeout<GuestConnection>(GUEST_CONNECT_TIMEOUT_MS) {
                var connection: GuestConnection? = null
                while (connection == null) {
                    connection = runCatching {
                        ParcelFileDescriptorFrameTransport(
                            descriptor = activeMachine.connectVsock(port),
                            guestCid = GUEST_CID_UNAVAILABLE,
                        )
                    }.getOrNull()
                    if (connection == null) delay(GUEST_CONNECT_RETRY_MS)
                }
                connection
            }
        }
    }

    private fun buildConfig(config: RuntimeConfig, shares: AvfSharedDirectories? = null): VirtualMachineConfig = requireAvfPlatformApi {
        val image = AvfImageLayout(Paths.get(config.imageDirectory))
        val customImage = VirtualMachineCustomImageConfig.Builder()
            .setName(vmName(config))
            .setKernelPath(image.kernelImage.toString())
            .setInitrdPath(image.initrdImage.toString())
            .addParam("root=/dev/vda")
            .addParam("rootfstype=ext4")
            .addParam("ro")
            .addParam("console=hvc0")
            .addParam("systemd.hostname=localhost")
            .useNetwork(config.enableNetwork)
            .useAutoMemoryBalloon(true)
            // The guest fstab is part of the signed image contract: vda=system, vdb=home, vdc=config.
            .addDisk(VirtualMachineCustomImageConfig.Disk.RODisk(image.systemImage.toString()))
            .addDisk(VirtualMachineCustomImageConfig.Disk.RWDisk(image.homeImage.toString()))
            .addDisk(VirtualMachineCustomImageConfig.Disk.RODisk(image.configImage.toString()))
        if (config.sharedFolders.isNotEmpty()) customImage.addParam("term.share-broker=1")
        if (shares != null) {
            if (shares.hasFolders) customImage.addParam(AvfSharedDirectories.BOOT_PARAM)
            if (shares.hasAndroidStorage) customImage.addParam(AvfSharedDirectories.ANDROID_STORAGE_PARAM)
            shares.exports.forEach { export ->
                requireAvfSharedDirectoryApi { customImage.addSharedPath(avfSharedPath(export)) }
            }
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

    private fun callback(boot: AvfGuestBoot, token: Any, instanceId: String): VirtualMachineCallback {
        val failureReported = AtomicBoolean(false)
        return object : VirtualMachineCallback {
            override fun onPayloadStarted(vm: VirtualMachine) = Unit

            override fun onPayloadReady(vm: VirtualMachine) = Unit

            override fun onPayloadFinished(vm: VirtualMachine, exitCode: Int) {
                if (activeBoot.get() !== token) return
                markMachineStopped(vm, instanceId)
                if (exitCode == 0) {
                    boot.fail(IllegalStateException("Guest exited during boot"))
                    events.tryEmit(AvfPlatformEvent.Stopped)
                } else {
                    val failure = IllegalStateException("Guest exited (code $exitCode)")
                    boot.fail(failure)
                    failureReported.set(true)
                    events.tryEmit(AvfPlatformEvent.Failed(failure.message.orEmpty()))
                }
            }

            override fun onError(vm: VirtualMachine, errorCode: Int, message: String) {
                if (activeBoot.get() !== token) return
                markMachineStopped(vm, instanceId)
                val failure = IllegalStateException("AVF error $errorCode: $message")
                boot.fail(failure)
                failureReported.set(true)
                events.tryEmit(AvfPlatformEvent.Failed(failure.message.orEmpty()))
            }

            override fun onStopped(vm: VirtualMachine, reason: Int) {
                if (activeBoot.get() !== token) return
                markMachineStopped(vm, instanceId)
                boot.fail(IllegalStateException("AVF stopped (reason $reason)"))
                if (!failureReported.get()) events.tryEmit(AvfPlatformEvent.Stopped)
            }
        }
    }

    private fun markMachineStopped(stoppedMachine: VirtualMachine, instanceId: String) {
        if (runCatching { stoppedMachine.status == VirtualMachine.STATUS_STOPPED }.getOrDefault(false)) {
            releaseSharedBoot(stoppedMachine)
            runCatching { releaseStoppedShares(instanceId) }
                .onFailure { Log.w(TAG, "persisted virtiofs artifact cleanup failed", it) }
        }
        if (machine === stoppedMachine) {
            machine = null
            pendingSessionConnection.getAndSet(null)?.let { runCatching { it.close() } }
        }
    }

    private fun releaseSharedBoot(vm: VirtualMachine) {
        val owned = sharedBoot.get() ?: return
        if (owned.vm !== vm || !sharedBoot.compareAndSet(owned, null)) return
        runCatching { releaseStoppedShares(owned.instanceId); owned.directories.close() }
            .onFailure { Log.w(TAG, "virtiofs artifact cleanup failed", it) }
    }

    private fun releaseStoppedShares(instanceId: String) {
        // This may be a separate verifier/revocation driver, not the boot owner.
        PrivateShareOwners.releaseStopped(privateShareKey(instanceId))
        privateBoot.get()?.takeIf { it.instanceId == instanceId }?.let { owned ->
            privateBoot.compareAndSet(owned, null)
        }
        shareLeases.release(instanceId) { artifact -> artifact?.let {
            AvfSharedDirectories.cleanup(appContext.filesDir.canonicalFile, it, publicSharesRoot())
        } }
    }

    private fun privateShareKey(instanceId: String) = "${appContext.filesDir.canonicalPath}:$instanceId"

    private fun publicSharesRoot() = dev.terminal.runtime.storage.AndroidSharedFolderStorage.root(appContext)

    private fun vmName(config: RuntimeConfig): String = "droid-${config.instanceId}"

    private companion object {
        // Bounded even when a platform binder call cannot be interrupted.
        val FILE_CONNECTORS = java.util.concurrent.ThreadPoolExecutor(0, 2, 30, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.SynchronousQueue(), java.util.concurrent.ThreadFactory { task ->
                Thread(task, "term-file-connect").apply { isDaemon = true }
            })
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
        const val GUEST_CONNECT_RETRY_MS = 500L
    }
}
