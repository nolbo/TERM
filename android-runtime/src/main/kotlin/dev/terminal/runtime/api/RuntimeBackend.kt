package dev.terminal.runtime.api

import dev.terminal.protocol.FrameTransport
import dev.terminal.protocol.ProtocolSessionClient
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow

/** Stable boundary used by UI and session code; it intentionally contains no AVF classes. */
interface RuntimeBackend {
    suspend fun prepare(config: RuntimeConfig): Result<Unit>
    suspend fun start(): Result<Unit>
    suspend fun suspendRuntime(): Result<Unit>
    suspend fun resumeRuntime(): Result<Unit>
    suspend fun stop(mode: StopMode): Result<Unit>

    fun observeState(): Flow<RuntimeState>
    suspend fun connectGuestAgent(): Result<GuestConnection>

    /** Dedicated guest-agent transport for capability RPC; never shares the PTY stream. */
    suspend fun connectGuestBridge(): Result<GuestConnection> = Result.failure(
        UnsupportedOperationException("Guest capability bridge is unavailable"),
    )
    suspend fun connectGuestPortAgent(): Result<GuestConnection> = Result.failure(
        UnsupportedOperationException("Guest port agent is unavailable"),
    )
    suspend fun openGuestPortForward(port: Int): Result<GuestByteStream> = Result.failure(
        UnsupportedOperationException("Guest port forwarding is unavailable"),
    )
    /** Address-aware TCP proxy. Available only after the port agent advertises proxy v1. */
    suspend fun openGuestTcpForward(address: String, port: Int): Result<GuestByteStream> = Result.failure(
        UnsupportedOperationException("Guest TCP proxy is unavailable"),
    )
    /** Explicit copy endpoint in the selected VM; it never exports a host directory. */
    suspend fun openGuestFileTransfer(): Result<GuestByteStream> = Result.failure(
        UnsupportedOperationException("Guest file transfer is unavailable"),
    )
}

data class RuntimeConfig(
    val instanceId: String = "default",
    val imageDirectory: String,
    val memoryMib: Int = 2048,
    val cpuCount: Int = 1,
    /** Product policy: custom-image support and device compatibility decide the default. */
    val protectedVm: Boolean = false,
    /** Enables the AVF guest network device; host port forwarding remains a later layer. */
    val enableNetwork: Boolean = true,
    /** Host-selected distribution; drivers reject a different verified image before boot. */
    val imageDistribution: String = "debian",
    /** Service-created maintenance boot. Never populated from launch JSON or guest requests. */
    val avfMaintenance: AvfMaintenanceBoot? = null,
)

data class AvfMaintenanceBoot(val operation: String, val sourceSystem: String, val copiedState: String,
    val sourceFormatVersion: Int = 1) {
    init {
        require(operation in setOf("components", "rebase"))
        require(sourceFormatVersion in 1..2)
    }
}

enum class StopMode { GRACEFUL, FORCE }

sealed interface RuntimeState {
    val wireName: String

    data object Stopped : RuntimeState { override val wireName = "STOPPED" }
    data object Preparing : RuntimeState { override val wireName = "PREPARING" }
    data object Booting : RuntimeState { override val wireName = "BOOTING" }
    data object Ready : RuntimeState { override val wireName = "READY" }
    data object Active : RuntimeState { override val wireName = "ACTIVE" }
    data object Idle : RuntimeState { override val wireName = "IDLE" }
    data object Suspended : RuntimeState { override val wireName = "SUSPENDED" }
    data object Stopping : RuntimeState { override val wireName = "STOPPING" }
    data class Failed(val reason: String) : RuntimeState { override val wireName = "FAILED" }
}

/** A host-owned framed transport to the authenticated guest agent over vsock. */
interface GuestConnection : FrameTransport {
    val guestCid: Int
}

interface GuestByteStream : Closeable {
    val input: InputStream
    val output: OutputStream
    /** Preserve the response side when the TCP client finishes its request. */
    fun shutdownOutput() {}
}

/** Creates a session client only after a runtime backend has authenticated its guest connection. */
suspend fun RuntimeBackend.connectSessionClient(owner: String? = null): Result<ProtocolSessionClient> =
    connectGuestAgent().onFailure {
        dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record("session.connect.failed", owner, error = it)
    }.map { connection ->
        val connectionId = sessionConnectionIds.incrementAndGet().toString()
        dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record("session.connected", owner, connectionId)
        ProtocolSessionClient(connection, onDiagnostic = { event, error ->
            dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record(event, owner, connectionId, error)
        }, onRequestDiagnostic = { event, fields, error ->
            dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record(event, owner, connectionId, error, fields)
        })
    }

private val sessionConnectionIds = java.util.concurrent.atomic.AtomicLong()
