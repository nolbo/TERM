package dev.terminal.protocol

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Transport boundary for vsock, in-memory tests, or another framed byte stream. */
interface FrameTransport : Closeable {
    suspend fun send(frame: ProtocolFrame): Result<Unit>
    fun incomingFrames(): Flow<ProtocolFrame>
}

/**
 * Host-side session client. It is transport-agnostic and remains valid when an
 * AOSP system-SDK AVF transport replaces the current platform probe.
 */
interface SessionController : Closeable {
    val events: SharedFlow<SessionEvent>
    val connectionState: StateFlow<SessionConnectionState>
    suspend fun create(shell: String, cwd: String, env: Map<String, String> = emptyMap(), rows: UShort, cols: UShort): Result<SessionDescriptor>
    suspend fun list(): Result<List<SessionDescriptor>>
    suspend fun attach(sessionId: String): Result<SessionDescriptor>
    suspend fun detach(sessionId: String): Result<Unit>
    suspend fun resize(sessionId: String, rows: UShort, cols: UShort): Result<SessionDescriptor>
    suspend fun write(sessionId: String, bytes: ByteArray): Result<Int>
    suspend fun terminate(sessionId: String, signal: Int): Result<Unit>
}

class ProtocolSessionClient(
    private val transport: FrameTransport,
    private val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onDiagnostic: (String, Throwable) -> Unit = { _, _ -> },
) : SessionController {
    private val disconnected = AtomicBoolean()
    private val nextRequestId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<ProtocolFrame>>()
    private val mutableEvents = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 64)
    private val mutableConnectionState = MutableStateFlow(SessionConnectionState.CONNECTED)
    override val events: SharedFlow<SessionEvent> = mutableEvents.asSharedFlow()
    override val connectionState: StateFlow<SessionConnectionState> = mutableConnectionState.asStateFlow()

    private val receiveJob = scope.launch {
        var disconnectReason: Throwable = ProtocolRequestException("Session transport disconnected")
        var diagnostic = "session.eof"
        try {
            transport.incomingFrames().collect(::handleIncomingFrame)
        } catch (cancelled: CancellationException) {
            disconnectReason = cancelled
            diagnostic = "session.cancelled"
            throw cancelled
        } catch (failure: Exception) {
            // A stopped VM may reset vsock instead of returning EOF. Contain
            // transport/frame errors here: an uncaught launch exception would
            // kill the shared Android runtime process and its other PTY owners.
            disconnectReason = failure
            diagnostic = "session.read.failed"
        } finally {
            disconnect(disconnectReason, diagnostic)
        }
    }

    override suspend fun create(
        shell: String,
        cwd: String,
        env: Map<String, String>,
        rows: UShort,
        cols: UShort,
    ): Result<SessionDescriptor> = request(SessionRequest.Create(shell, cwd, env, rows, cols)).mapResponse {
        it as? SessionResponse.Created ?: throw ProtocolRequestException("Expected create response")
    }.map { it.session }

    override suspend fun list(): Result<List<SessionDescriptor>> = request(SessionRequest.List).mapResponse {
        it as? SessionResponse.Listed ?: throw ProtocolRequestException("Expected session list")
    }.map { it.sessions }

    override suspend fun attach(sessionId: String): Result<SessionDescriptor> = request(SessionRequest.Attach(sessionId)).mapResponse {
        it as? SessionResponse.Attached ?: throw ProtocolRequestException("Expected attach response")
    }.map { it.session }

    override suspend fun detach(sessionId: String): Result<Unit> = request(SessionRequest.Detach(sessionId)).mapResponse {
        it as? SessionResponse.Detached ?: throw ProtocolRequestException("Expected detach response")
    }.map { Unit }

    override suspend fun resize(sessionId: String, rows: UShort, cols: UShort): Result<SessionDescriptor> =
        request(SessionRequest.Resize(sessionId, rows, cols)).mapResponse {
            it as? SessionResponse.Resized ?: throw ProtocolRequestException("Expected resize response")
        }.map { it.session }

    override suspend fun write(sessionId: String, bytes: ByteArray): Result<Int> = request(SessionRequest.Write(sessionId, bytes)).mapResponse {
        it as? SessionResponse.Written ?: throw ProtocolRequestException("Expected write response")
    }.map { it.acceptedBytes }

    override suspend fun terminate(sessionId: String, signal: Int): Result<Unit> =
        request(SessionRequest.Terminate(sessionId, signal)).mapResponse {
            it as? SessionResponse.Terminating ?: throw ProtocolRequestException("Expected terminate response")
        }.map { Unit }

    override fun close() {
        disconnect(ProtocolRequestException("Session client closed"), "session.closed")
        receiveJob.cancel()
    }

    private fun disconnect(reason: Throwable, diagnostic: String) {
        if (!disconnected.compareAndSet(false, true)) return
        runCatching { onDiagnostic(diagnostic, reason) }
        mutableConnectionState.value = SessionConnectionState.DISCONNECTED
        pending.values.forEach { it.completeExceptionally(reason) }
        pending.clear()
        // Cleanup of a broken/closed descriptor must not escape to another owner.
        runCatching { transport.close() }
    }

    private suspend fun request(request: SessionRequest): Result<SessionResponse> {
        val requestId = nextRequestId.getAndIncrement()
        val waiting = CompletableDeferred<ProtocolFrame>()
        pending[requestId] = waiting
        try {
            if (mutableConnectionState.value == SessionConnectionState.DISCONNECTED) {
                return Result.failure(ProtocolRequestException("Session transport disconnected"))
            }
            transport.send(
                ProtocolFrame(
                    service = Service.SESSION,
                    kind = FrameKind.REQUEST,
                    requestId = requestId,
                    payload = SessionPayloadCodec.encode(request),
                ),
            ).getOrElse {
                runCatching { onDiagnostic("session.send.failed", it) }
                return Result.failure(it)
            }
            val frame = withTimeout(requestTimeoutMillis) { waiting.await() }
            return when (frame.kind) {
                FrameKind.RESPONSE -> runCatching { SessionResponseCodec.decode(frame.payload) }
                FrameKind.ERROR -> Result.failure(GuestRequestException(ProtocolErrorCodec.decode(frame.payload)))
                else -> Result.failure(ProtocolRequestException("Unexpected response frame"))
            }
        } catch (error: Throwable) {
            return Result.failure(error)
        } finally {
            pending.remove(requestId)
        }
    }

    private suspend fun handleIncomingFrame(frame: ProtocolFrame) {
        if (frame.service != Service.SESSION) return
        when (frame.kind) {
            FrameKind.STREAM -> runCatching { SessionEventCodec.decode(frame.payload) }
                .onSuccess { mutableEvents.emit(it) }
            FrameKind.RESPONSE, FrameKind.ERROR -> pending[frame.requestId]?.complete(frame)
            else -> Unit
        }
    }

    private inline fun <T> Result<SessionResponse>.mapResponse(
        transform: (SessionResponse) -> T,
    ): Result<T> = fold(onSuccess = { runCatching { transform(it) } }, onFailure = { Result.failure(it) })

    private companion object {
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 10_000L
    }
}

enum class SessionConnectionState { CONNECTED, DISCONNECTED }

class GuestRequestException(val error: ProtocolErrorPayload) : Exception("${error.code}: ${error.message}")
class ProtocolRequestException(message: String) : Exception(message)
