package dev.terminal.runtime.avf.system

import android.content.Context
import android.system.virtualmachine.VirtualMachine
import android.system.virtualmachine.VirtualMachineCallback
import android.system.virtualmachine.VirtualMachineConfig
import android.system.virtualmachine.VirtualMachineCustomImageConfig
import android.system.virtualmachine.VirtualMachineManager
import dev.terminal.image.DebianImageLayout
import dev.terminal.image.DebianImageVerifier
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
import kotlinx.coroutines.CompletableDeferred
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
 * This source deliberately is not part of the public-SDK Gradle modules: it references
 * `android.system.virtualmachine` System APIs and must be compiled with the exact system SDK
 * supplied by the target AOSP build. UI code sees only [AvfPlatformDriver].
 */
class AospAvfPlatformDriver(
    context: Context,
) : AvfPlatformDriver {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(VirtualMachineManager::class.java)
    private val mutex = Mutex()

    private var prepared: RuntimeConfig? = null
    @Volatile private var machine: VirtualMachine? = null
    @Volatile private var readySignal: CompletableDeferred<Unit>? = null
    private val events = MutableSharedFlow<AvfPlatformEvent>(extraBufferCapacity = 4)

    override fun observeEvents(): Flow<AvfPlatformEvent> = events.asSharedFlow()

    override suspend fun prepare(config: RuntimeConfig): Result<Unit> = mutex.withLock {
        runCatching {
            require(VM_NAME.matches(config.instanceId)) {
                "VM instance ID may contain only letters, digits, '.', '_' and '-'"
            }
            require(config.memoryMib >= MIN_MEMORY_MIB) { "VM memory must be at least $MIN_MEMORY_MIB MiB" }
            require(config.cpuCount >= 1) { "VM CPU count must be positive" }
            when (val verification = DebianImageVerifier.verify(Paths.get(config.imageDirectory))) {
                is ImageVerification.Ready -> Unit
                is ImageVerification.Invalid -> error(verification.reason)
            }
            prepared = config
        }
    }

    override suspend fun start(config: RuntimeConfig): Result<Unit> = mutex.withLock {
        runCatching {
            check(prepared == config) { "Prepare the exact image configuration before starting" }
            check(machine == null) { "VM is already running" }

            val vmConfig = buildConfig(config)
            val nextMachine = manager.getOrCreate(vmName(config), vmConfig)
            val nextReadySignal = CompletableDeferred<Unit>()
            nextMachine.setCallback(Executor { it.run() }, callback(nextReadySignal))
            machine = nextMachine
            readySignal = nextReadySignal
            nextMachine.run()

            // `run()` means startup was requested, not that Linux or sessiond is reachable.
            withTimeout(BOOT_TIMEOUT_MS) { nextReadySignal.await() }
        }.onFailure {
            machine = null
            readySignal = null
        }
    }

    override suspend fun stop(mode: StopMode): Result<Unit> = mutex.withLock {
        runCatching {
            val activeMachine = machine ?: return@runCatching
            // AVF stop is immediate. A graceful guest shutdown RPC belongs to LifecycleService.
            activeMachine.stop()
            machine = null
            readySignal = null
        }
    }

    override suspend fun connectGuestAgent(): Result<GuestConnection> = connectGuestPort(DROID_SESSIOND_PORT)

    override suspend fun connectGuestBridge(): Result<GuestConnection> = connectGuestPort(DROID_AGENT_PORT)

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
            withTimeout(GUEST_CONNECT_TIMEOUT_MS) {
                while (true) {
                    runCatching {
                        return@withTimeout ParcelFileDescriptorFrameTransport(
                            descriptor = activeMachine.connectVsock(port),
                            guestCid = GUEST_CID_UNAVAILABLE,
                        )
                    }
                    delay(GUEST_CONNECT_RETRY_MS)
                }
            }
        }
    }

    private fun buildConfig(config: RuntimeConfig): VirtualMachineConfig {
        val image = DebianImageLayout(Paths.get(config.imageDirectory))
        val customImage = VirtualMachineCustomImageConfig.Builder()
            .setName(vmName(config))
            .setKernelPath(image.kernelImage.toString())
            .setInitrdPath(image.initrdImage.toString())
            .useNetwork(config.enableNetwork)
            .useAutoMemoryBalloon(true)
            // The guest fstab is part of the signed image contract: vda=system, vdb=home, vdc=config.
            .addDisk(VirtualMachineCustomImageConfig.Disk.RODisk(image.systemImage.toString()))
            .addDisk(VirtualMachineCustomImageConfig.Disk.RWDisk(image.homeImage.toString()))
            .addDisk(VirtualMachineCustomImageConfig.Disk.RODisk(image.configImage.toString()))
            .build()

        return VirtualMachineConfig.Builder(appContext)
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

    private fun callback(ready: CompletableDeferred<Unit>): VirtualMachineCallback {
        val failureReported = AtomicBoolean(false)
        return object : VirtualMachineCallback {
            override fun onPayloadStarted(vm: VirtualMachine) = Unit

            override fun onPayloadReady(vm: VirtualMachine) {
                ready.complete(Unit)
            }

            override fun onPayloadFinished(vm: VirtualMachine, exitCode: Int) {
                markMachineStopped(vm)
                if (exitCode == 0) {
                    ready.completeExceptionally(IllegalStateException("Guest exited during boot"))
                    events.tryEmit(AvfPlatformEvent.Stopped)
                } else {
                    val failure = IllegalStateException("Guest exited (code $exitCode)")
                    ready.completeExceptionally(failure)
                    failureReported.set(true)
                    events.tryEmit(AvfPlatformEvent.Failed(failure.message.orEmpty()))
                }
            }

            override fun onError(vm: VirtualMachine, errorCode: Int, message: String) {
                markMachineStopped(vm)
                val failure = IllegalStateException("AVF error $errorCode: $message")
                ready.completeExceptionally(failure)
                failureReported.set(true)
                events.tryEmit(AvfPlatformEvent.Failed(failure.message.orEmpty()))
            }

            override fun onStopped(vm: VirtualMachine, reason: Int) {
                markMachineStopped(vm)
                ready.completeExceptionally(IllegalStateException("AVF stopped (reason $reason)"))
                if (!failureReported.get()) events.tryEmit(AvfPlatformEvent.Stopped)
            }
        }
    }

    private fun markMachineStopped(stoppedMachine: VirtualMachine) {
        if (machine === stoppedMachine) {
            machine = null
            readySignal = null
        }
    }

    private fun vmName(config: RuntimeConfig): String = "droid-${config.instanceId}"

    private companion object {
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
