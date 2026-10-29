package dev.terminal.protocol

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.io.IOException
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel

/** Transport boundary for vsock, in-memory tests, or another framed byte stream. */
interface FrameTransport : Closeable {
    /** RPC responsiveness is independent of the transport's recovery lease. */
    val requestTimeoutMillis: Long get() = 10_000L
    val linkState: StateFlow<SessionConnectionState>? get() = null
    suspend fun send(frame: ProtocolFrame): Result<Unit>
    fun incomingFrames(): Flow<ProtocolFrame>
    /** Unblocks pending reads and writes without flushing. */
    override fun close()
}

/**
 * Host-side session client. It is transport-agnostic and remains valid when an
 * AOSP system-SDK AVF transport replaces the current platform probe.
 */
interface SessionController : Closeable {
    val events: SharedFlow<SessionEvent>
    val connectionState: StateFlow<SessionConnectionState>
    suspend fun create(shell: String, cwd: String, env: Map<String, String> = emptyMap(), rows: UShort, cols: UShort): Result<SessionDescriptor>
    suspend fun resourceUsage(): Result<GuestResourceUsage> = Result.failure(UnsupportedOperationException("Guest resource metrics unavailable"))
    suspend fun list(): Result<List<SessionDescriptor>>
    suspend fun attach(sessionId: String): Result<SessionDescriptor>
    suspend fun detach(sessionId: String): Result<Unit>
    suspend fun resize(sessionId: String, rows: UShort, cols: UShort): Result<SessionDescriptor>
    /** Ordered implementations emit Resized with the supplied identity before subsequent live output. */
    val supportsOrderedResize: Boolean get() = false
    suspend fun resizeOrdered(sessionId: String, rows: UShort, cols: UShort, barrier: Any): Result<SessionDescriptor> =
        resize(sessionId, rows, cols)
    suspend fun write(sessionId: String, bytes: ByteArray): Result<Int>
    suspend fun terminate(sessionId: String, signal: Int): Result<Unit>
}

class ProtocolSessionClient(
    private val transport: FrameTransport,
    private val requestTimeoutMillis: Long = transport.requestTimeoutMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onDiagnostic: (String, Throwable) -> Unit = { _, _ -> },
    private val onRequestDiagnostic: (String, Map<String, String>, Throwable?) -> Unit = { _, _, _ -> },
    sessionNamespace: String? = null,
) : SessionController {
    init { require(requestTimeoutMillis > 0) }
    private val identities = sessionNamespace?.let(::SessionIdentities)
    private val disconnectFailure = AtomicReference<Throwable?>()
    private val nextRequestId = AtomicLong(1)
    private data class PendingRequest(val result: CompletableDeferred<Result<SessionResponse>>,
        val request: SessionRequest, val wire: SessionRequest)
    private val pending = ConcurrentHashMap<Long, PendingRequest>()
    private val resizeBarriers = ConcurrentHashMap<Long, Pair<SessionRequest.Resize, Any>>()
    override val supportsOrderedResize = true
    private val mutableEvents = MutableSharedFlow<SessionEvent>()
    private val eventBuffer = SessionEventBuffer()
    private val eventReady = Channel<Unit>(Channel.CONFLATED)
    private val eventJob = scope.launch {
        for (ready in eventReady) {
            while (true) mutableEvents.emit(eventBuffer.poll() ?: break)
        }
    }
    private val mutableConnectionState = MutableStateFlow(SessionConnectionState.CONNECTED)
    override val events: SharedFlow<SessionEvent> = mutableEvents.asSharedFlow()
    override val connectionState: StateFlow<SessionConnectionState> = mutableConnectionState.asStateFlow()
    private val linkJob = transport.linkState?.let { state -> scope.launch(start = CoroutineStart.UNDISPATCHED) {
        state.collect { if (disconnectFailure.get() == null) mutableConnectionState.value = it }
    } }

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

    override suspend fun resourceUsage(): Result<GuestResourceUsage> = request(SessionRequest.ResourceUsage).mapResponse {
        (it as? SessionResponse.Resources)?.usage ?: throw ProtocolRequestException("Expected guest resource metrics")
    }

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

    override suspend fun resizeOrdered(sessionId: String, rows: UShort, cols: UShort, barrier: Any): Result<SessionDescriptor> =
        request(SessionRequest.Resize(sessionId, rows, cols), barrier).mapResponse {
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
        if (!disconnectFailure.compareAndSet(null, reason)) return
        runCatching { onDiagnostic(diagnostic, reason) }
        mutableConnectionState.value = SessionConnectionState.DISCONNECTED
        pending.values.forEach { it.result.completeExceptionally(reason) }
        pending.clear()
        resizeBarriers.clear()
        eventBuffer.close()
        eventReady.close()
        eventJob.cancel()
        linkJob?.cancel()
        // Cleanup of a broken/closed descriptor must not escape to another owner.
        runCatching { transport.close() }
    }

    private fun failRequestConnection(reason: Throwable, diagnostic: String) {
        disconnect(reason, diagnostic)
        receiveJob.cancel()
    }

    private suspend fun request(request: SessionRequest, resizeBarrier: Any? = null): Result<SessionResponse> = coroutineScope {
        val started = System.nanoTime()
        if (disconnectFailure.get() != null) {
            val failure = ProtocolRequestException("Session transport disconnected")
            requestDiagnostic(request, 0, "disconnected", started, failure)
            return@coroutineScope Result.failure(failure)
        }
        if (transport.linkState?.value == SessionConnectionState.RECOVERING)
            return@coroutineScope Result.failure(ProtocolRecoveringException())
        val requestId = nextRequestId.getAndIncrement()
        val wireRequest: SessionRequest
        val requestFrame = try {
            wireRequest = identities?.request(request) ?: request
            ProtocolFrame(service = Service.SESSION, kind = FrameKind.REQUEST, requestId = requestId,
                payload = SessionPayloadCodec.encode(wireRequest))
        } catch (error: Exception) {
            requestDiagnostic(request, requestId, "encode", started, error)
            return@coroutineScope Result.failure(error)
        }
        val waiting = CompletableDeferred<Result<SessionResponse>>()
        val sent = AtomicBoolean()
        // CREATE establishes the identity needed to initialize output; an
        // abandoned CREATE must reconcile through a fresh owner attachment.
        val needsReconciliation = request is SessionRequest.Create || resizeBarrier != null
        pending[requestId] = PendingRequest(waiting, request, wireRequest)
        if (request is SessionRequest.Resize && resizeBarrier != null) resizeBarriers[requestId] = request to resizeBarrier
        // The deadline runs independently of a possibly blocked send. Closing the
        // socket before joining the sender is what wakes a native vsock write.
        val deadline = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            delay(requestTimeoutMillis)
            if (!waiting.isCompleted) {
                val error = ProtocolRequestTimeoutException()
                // Unknown creation/geometry requires reconciliation. Ordinary
                // complete requests can expire independently of sibling calls.
                if (!sent.get() || needsReconciliation) failRequestConnection(error, "session.request.timeout")
                else waiting.completeExceptionally(error)
            }
        }
        var sender: Deferred<Result<Unit>>? = null
        var stage = "send"
        var failure: Throwable? = null
        var acceptedBytes: Int? = null
        if (request !is SessionRequest.Write) requestDiagnostic(request, requestId, "start", started, null)
        try {
            if (disconnectFailure.get() != null) {
                val disconnected = ProtocolRequestException("Session transport disconnected")
                failure = disconnected
                return@coroutineScope Result.failure(disconnected)
            }
            sender = async(Dispatchers.IO) {
                runCatching { transport.send(requestFrame).getOrThrow(); sent.set(true) }
            }
            sender.await().getOrElse { error ->
                failRequestConnection(error, "session.send.failed")
                failure = disconnectFailure.get() ?: error
                return@coroutineScope Result.failure(requireNotNull(failure))
            }
            stage = "response"
            waiting.await().also {
                stage = "decode"
                failure = it.exceptionOrNull()
                acceptedBytes = (it.getOrNull() as? SessionResponse.Written)?.acceptedBytes
            }
        } catch (cancelled: CancellationException) {
            failure = cancelled
            // Complete frames stay ordered even if their caller stops waiting.
            // Never automatically repeat an uncertain mutation.
            if (!sent.get() || needsReconciliation) failRequestConnection(cancelled, "session.request.cancelled")
            throw cancelled
        } catch (error: Exception) {
            failure = error
            Result.failure(error)
        } finally {
            deadline.cancel()
            sender?.cancel()
            pending.remove(requestId)
            resizeBarriers.remove(requestId)
            if (request !is SessionRequest.Write || failure != null || acceptedBytes != request.bytes.size ||
                System.nanoTime() - started >= 1_000_000_000)
                requestDiagnostic(request, requestId, stage, started, failure, acceptedBytes)
        }
    }

    private fun requestDiagnostic(request: SessionRequest, id: Long, stage: String, started: Long, error: Throwable?, acceptedBytes: Int? = null) {
        val fields = linkedMapOf("request_id" to id.toString(), "stage" to stage,
            "elapsed_ms" to ((System.nanoTime() - started) / 1_000_000).toString(),
            "timeout_ms" to requestTimeoutMillis.toString(), "pending" to pending.size.toString())
        fields["operation"] = when (request) {
            is SessionRequest.Create -> "create"
            SessionRequest.ResourceUsage -> "resources"
            SessionRequest.List -> "list"
            is SessionRequest.Attach -> "attach"
            is SessionRequest.Detach -> "detach"
            is SessionRequest.Resize -> "resize"
            is SessionRequest.Write -> "write"
            is SessionRequest.Terminate -> "terminate"
        }
        when (request) {
            is SessionRequest.Attach -> fields["session"] = request.sessionId
            is SessionRequest.Detach -> fields["session"] = request.sessionId
            is SessionRequest.Resize -> { fields["session"] = request.sessionId; fields["rows"] = request.rows.toString(); fields["cols"] = request.cols.toString() }
            is SessionRequest.Write -> { fields["session"] = request.sessionId; fields["bytes"] = request.bytes.size.toString() }
            is SessionRequest.Terminate -> { fields["session"] = request.sessionId; fields["signal"] = request.signal.toString() }
            is SessionRequest.Create -> { fields["rows"] = request.rows.toString(); fields["cols"] = request.cols.toString() }
            SessionRequest.ResourceUsage, SessionRequest.List -> Unit
        }
        if (error is GuestRequestException) fields["guest_error"] = error.error.code.name
        acceptedBytes?.let { fields["accepted_bytes"] = it.toString() }
        val event = if (stage == "start") "session.rpc.start" else if (error != null) "session.rpc.failed" else "session.rpc.completed"
        runCatching { onRequestDiagnostic(event, fields, error) }
    }

    private fun publishEvent(event: SessionEvent) {
        if (!eventBuffer.offer(event)) throw ProtocolRequestException("Session control event backlog exceeded")
        eventReady.trySend(Unit)
    }

    private fun handleIncomingFrame(frame: ProtocolFrame) {
        if (frame.service != Service.SESSION) return
        when (frame.kind) {
            FrameKind.STREAM -> {
                val event = runCatching { SessionEventCodec.decode(frame.payload) }.getOrElse {
                    runCatching { onDiagnostic("session.event.invalid", it) }
                    return
                }
                val mapped = if (identities == null) event else identities.event(event)
                if (mapped != null) publishEvent(mapped)
            }
            FrameKind.RESPONSE, FrameKind.ERROR -> {
                val pendingRequest = pending[frame.requestId] ?: return
                val waiting = pendingRequest.result
                val decoded = runCatching {
                    when (frame.kind) {
                        FrameKind.RESPONSE -> SessionResponseCodec.decode(frame.payload).let { identities?.response(it, pendingRequest.request, pendingRequest.wire) ?: it }
                        else -> throw GuestRequestException(ProtocolErrorCodec.decode(frame.payload))
                    }
                }
                val resize = resizeBarriers[frame.requestId]
                if (frame.kind == FrameKind.RESPONSE && resize != null) {
                    val (request, barrier) = resize
                    val response = runCatching {
                        val resized = decoded.getOrThrow() as? SessionResponse.Resized
                            ?: throw ProtocolRequestException("Expected resize response")
                        require(resized.session.id == request.sessionId && resized.session.rows == request.rows &&
                            resized.session.cols == request.cols) { "Resize response does not match request" }
                        resized
                    }.getOrElse { waiting.completeExceptionally(it); return }
                    // This marker occupies the response's exact place in the output
                    // stream, before any following output is handed to the parser.
                    publishEvent(SessionEvent.Resized(response.session.id, request.rows, request.cols, barrier))
                }
                waiting.complete(decoded)
                // CREATE/LIST callers can initialize terminal state before buffered
                // output fills their event queue. No second response codec pass.
                identities?.identifiedEvents()?.forEach(::publishEvent)
            }
            else -> Unit
        }
    }

    private inline fun <T> Result<SessionResponse>.mapResponse(
        transform: (SessionResponse) -> T,
    ): Result<T> = fold(onSuccess = { runCatching { transform(it) } }, onFailure = { Result.failure(it) })

}

enum class SessionConnectionState { CONNECTED, RECOVERING, DISCONNECTED }

class ProtocolRecoveringException : IOException("Session connection is recovering; request was not sent")

class GuestRequestException(val error: ProtocolErrorPayload) : Exception("${error.code}: ${error.message}")
class ProtocolRequestException(message: String) : Exception(message)

/** The guest may already have applied the request; never automatically resend it. */
class ProtocolRequestTimeoutException : IOException("Session request timed out; operation result is unknown")
