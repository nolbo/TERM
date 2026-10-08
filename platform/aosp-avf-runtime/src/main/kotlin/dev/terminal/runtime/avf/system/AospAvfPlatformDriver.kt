package dev.terminal.runtime.avf.system

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.system.virtualmachine.VirtualMachine
import android.system.virtualmachine.VirtualMachineCallback
import android.system.virtualmachine.VirtualMachineConfig
import android.system.virtualmachine.VirtualMachineCustomImageConfig
import android.system.virtualmachine.VirtualMachineManager
import dev.terminal.image.DebianImageLayout
import dev.terminal.image.DebianImageInstaller
import dev.terminal.image.ImageVerification
import dev.terminal.runtime.api.GuestConnection
import dev.terminal.runtime.api.GuestByteStream
import dev.terminal.runtime.api.RuntimeConfig
import dev.terminal.runtime.api.StopMode
import dev.terminal.runtime.avf.AvfPlatformDriver
import dev.terminal.runtime.avf.AvfPlatformEvent
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
    private val manager = checkNotNull(appContext.getSystemService(VirtualMachineManager::class.java)) {
        "AVF VirtualMachineManager is unavailable on this device"
    }
    private val mutex = Mutex()
    @Volatile override var startedNewVm: Boolean = false
        private set

    private var prepared: RuntimeConfig? = null
    @Volatile private var machine: VirtualMachine? = null
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
            val verification = DebianImageInstaller.verifyForStartup(Paths.get(config.imageDirectory))
            Log.i(TAG, "image.verify duration_ms=${SystemClock.elapsedRealtime() - verificationStartedAt} ready=${verification is ImageVerification.Ready}")
            when (verification) {
                is ImageVerification.Ready -> Unit
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
        try {
            check(prepared == config) { "Prepare the exact image configuration before starting" }
            check(pendingSessionConnection.get() == null) { "VM session connection is already prepared" }
            val vm = machine ?: manager.getOrCreate(vmName(config), buildConfig(config))
            nextMachine = vm
            val boot = AvfGuestBoot()
            vm.setCallback(Executor { it.run() }, callback(boot))
            machine = vm
            // A previous host/boot timeout may have left this persistent VM running.
            // Reconnect to it without restarting Linux or replacing its writable disks.
            if (vm.status == VirtualMachine.STATUS_STOPPED) {
                // getOrCreate ignores the supplied config for an existing VM. Refresh
                // stopped instances so boot parameters also reach installed guests.
                requireAvfPlatformApi { vm.setConfig(buildConfig(config)) }
                startedHere = true
                vm.run()
            }
            pendingSessionConnection.set(boot.awaitSessiond(BOOT_TIMEOUT_MS, GUEST_CONNECT_RETRY_MS) {
                ParcelFileDescriptorFrameTransport(vm.connectVsock(DROID_SESSIOND_PORT), GUEST_CID_UNAVAILABLE)
            })
            check(machine === vm) { "AVF stopped while connecting to the Debian session daemon" }
            Log.i(TAG, "vm.sessiond duration_ms=${SystemClock.elapsedRealtime() - bootStartedAt} reused=${!startedHere}")
            startedNewVm = startedHere
            Result.success(Unit)
        } catch (failure: Throwable) {
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
                if (stopped && machine === vm) machine = null
            }
            if (failure is CancellationException) throw failure
            val reported = describeAvfNetworkFailure(failure)
            if (reported !== failure) Log.w(TAG, "vm.network fixed TAP denied", failure)
            Result.failure(reported)
        }
    }

    override suspend fun stop(mode: StopMode): Result<Unit> = mutex.withLock {
        runCatching {
            val activeMachine = machine ?: return@runCatching
            // AVF stop is immediate. A graceful guest shutdown RPC belongs to LifecycleService.
            activeMachine.stop()
            pendingSessionConnection.getAndSet(null)?.close()
            machine = null
            startedNewVm = false
        }
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

    private fun buildConfig(config: RuntimeConfig): VirtualMachineConfig = requireAvfPlatformApi {
        val image = DebianImageLayout(Paths.get(config.imageDirectory))
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
            .build()

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
            .setCustomImageConfig(customImage)
            .setDebugLevel(VirtualMachineConfig.DEBUG_LEVEL_NONE)
            .setVmOutputCaptured(false)
            .setConnectVmConsole(false)
            .build()
    }

    private fun callback(boot: AvfGuestBoot): VirtualMachineCallback {
        val failureReported = AtomicBoolean(false)
        return object : VirtualMachineCallback {
            override fun onPayloadStarted(vm: VirtualMachine) = Unit

            override fun onPayloadReady(vm: VirtualMachine) = Unit

            override fun onPayloadFinished(vm: VirtualMachine, exitCode: Int) {
                markMachineStopped(vm)
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
                markMachineStopped(vm)
                val failure = IllegalStateException("AVF error $errorCode: $message")
                boot.fail(failure)
                failureReported.set(true)
                events.tryEmit(AvfPlatformEvent.Failed(failure.message.orEmpty()))
            }

            override fun onStopped(vm: VirtualMachine, reason: Int) {
                markMachineStopped(vm)
                boot.fail(IllegalStateException("AVF stopped (reason $reason)"))
                if (!failureReported.get()) events.tryEmit(AvfPlatformEvent.Stopped)
            }
        }
    }

    private fun markMachineStopped(stoppedMachine: VirtualMachine) {
        if (machine === stoppedMachine) {
            machine = null
            pendingSessionConnection.getAndSet(null)?.let { runCatching { it.close() } }
        }
    }

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
        const val GUEST_CONNECT_RETRY_MS = 500L
    }
}
