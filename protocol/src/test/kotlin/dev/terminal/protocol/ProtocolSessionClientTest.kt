package dev.terminal.protocol

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolSessionClientTest {
    @Test fun requestDiagnosticsCorrelateMutationResponseWithoutShellPathsOrEnvironmentValues() = runBlocking {
        val transport = FakeTransport()
        val records = mutableListOf<Pair<String, Map<String, String>>>()
        ProtocolSessionClient(transport, scope = this, onRequestDiagnostic = { event, fields, _ ->
            records += event to fields
            throw IllegalStateException("diagnostic observers cannot fail requests")
        }).use { client ->
            val pending = async { client.create("/private/shell", "/private/cwd", mapOf("TOKEN" to "secret"), 24u, 80u) }
            val frame = transport.sent.receive()
            transport.receive(frame.copy(kind = FrameKind.RESPONSE, payload = SessionResponseCodec.encode(SessionResponse.Created(
                SessionDescriptor("session-1", "Title", "/private/shell", "/private/cwd", 0, 24u, 80u, SessionStatus.RUNNING)))))
            assertTrue(pending.await().isSuccess)
            assertEquals(listOf("session.rpc.start", "session.rpc.completed"), records.map { it.first })
            records.forEach { (_, fields) ->
                assertEquals(frame.requestId.toString(), fields["request_id"])
                assertEquals("create", fields["operation"])
                assertEquals("80", fields["cols"])
                assertFalse(fields.values.any { it.contains("private") || it.contains("secret") })
            }
        }
    }

    @Test fun healthyTypingIsQuietButPartialInputAcceptanceIsRecordedWithByteCounts() = runBlocking {
        val transport = FakeTransport()
        val records = mutableListOf<Map<String, String>>()
        ProtocolSessionClient(transport, scope = this, onRequestDiagnostic = { _, fields, _ -> records += fields }).use { client ->
            for (accepted in listOf(3, 1)) {
                val sending = async { client.write("session-1", "abc".toByteArray()) }
                val frame = transport.sent.receive()
                transport.receive(frame.copy(kind = FrameKind.RESPONSE,
                    payload = SessionResponseCodec.encode(SessionResponse.Written("session-1", accepted))))
                assertEquals(accepted, sending.await().getOrThrow())
                if (accepted == 3) assertTrue(records.isEmpty())
            }
            val partial = records.single()
            assertEquals("write", partial["operation"])
            assertEquals("3", partial["bytes"])
            assertEquals("1", partial["accepted_bytes"])
            assertEquals("session-1", partial["session"])
        }
    }

    @Test
    fun mapsAResponseToTheMatchingRequestId() = runBlocking {
        val transport = FakeTransport()
        ProtocolSessionClient(transport, scope = this).use { client ->
            val pending = async { client.list() }
            val request = transport.sent.receive()
            transport.receive(
                ProtocolFrame(
                    service = Service.SESSION,
                    kind = FrameKind.RESPONSE,
                    requestId = request.requestId,
                    payload = SessionResponseCodec.encode(SessionResponse.Listed(emptyList())),
                ),
            )

            assertEquals(emptyList<SessionDescriptor>(), pending.await().getOrThrow())
        }
    }

    @Test
    fun forwardsStreamOutputWithoutCompletingPendingRequests() = runBlocking {
        val transport = FakeTransport()
        ProtocolSessionClient(transport, scope = this).use { client ->
            val output = async<SessionEvent> { withTimeout(1_000) { client.events.first() } }
            yield()
            transport.receive(
                ProtocolFrame(
                    service = Service.SESSION,
                    kind = FrameKind.STREAM,
                    requestId = 0,
                    payload = SessionEventCodec.encode(SessionEvent.Output("session-1", "hello".encodeToByteArray())),
                ),
            )

            assertEquals("hello", (output.await() as SessionEvent.Output).bytes.decodeToString())
        }
    }

    @Test
    fun marksTheClientDisconnectedWhenItsTransportEnds() = runBlocking {
        ProtocolSessionClient(EndingTransport, scope = this).use { client ->
            assertEquals(
                SessionConnectionState.DISCONNECTED,
                withTimeout(1_000) { client.connectionState.first { it == SessionConnectionState.DISCONNECTED } },
            )
        }
    }

    @Test
    fun transportReadFailureFailsPendingRequestsWithoutCancellingTheOwner() = runBlocking {
        val transport = ClosingTransport()
        ProtocolSessionClient(transport, scope = this).use { client ->
            val pending = async { client.list() }
            transport.sent.receive()
            val failure = IOException("AVF vsock connection reset during stop")
            transport.incoming.close(failure)

            withTimeout(1_000) {
                client.connectionState.first { it == SessionConnectionState.DISCONNECTED }
                val error = pending.await().exceptionOrNull()
                assertTrue(error is IOException)
                assertEquals(failure.message, error?.message)
            }
            assertTrue("A failed transport must not cancel its owning scope", isActive)
        }
    }

    @Test
    fun malformedTransportFramesDisconnectWithoutCancellingTheOwner() = runBlocking {
        val transport = ClosingTransport()
        ProtocolSessionClient(transport, scope = this).use { client ->
            transport.incoming.close(IllegalArgumentException("Invalid guest frame length"))
            withTimeout(1_000) {
                client.connectionState.first { it == SessionConnectionState.DISCONNECTED }
            }
            assertTrue(isActive)
        }
    }

    @Test
    fun disconnectedClientRejectsNewRequestsWithoutSendingOrWaitingForTimeout() = runBlocking {
        val transport = ClosingTransport()
        ProtocolSessionClient(transport, scope = this).use { client ->
            transport.incoming.close()
            withTimeout(1_000) {
                client.connectionState.first { it == SessionConnectionState.DISCONNECTED }
                val result = client.list()
                assertTrue(result.isFailure)
                assertFalse(result.exceptionOrNull() is kotlinx.coroutines.TimeoutCancellationException)
            }
            assertTrue(transport.sent.tryReceive().isFailure)
        }
    }

    @Test
    fun transportCleanupFailureDoesNotEscapeToTheOwner() = runBlocking {
        val transport = ClosingTransport(closeFailure = IOException("Closed vsock descriptor"))
        ProtocolSessionClient(transport, scope = this).use { client ->
            transport.incoming.close()
            withTimeout(1_000) {
                client.connectionState.first { it == SessionConnectionState.DISCONNECTED }
            }
            client.close()
            assertTrue(isActive)
        }
    }

    @Test fun readFailureRecordsItsOriginalStageOnceBeforeExplicitCleanup() = runBlocking {
        val transport = ClosingTransport()
        val records = mutableListOf<Pair<String, Throwable>>()
        val client = ProtocolSessionClient(transport, scope = this, onDiagnostic = { event, error -> records += event to error })
        val original = IOException("original reset")
        transport.incoming.close(original)
        withTimeout(1_000) { client.connectionState.first { it == SessionConnectionState.DISCONNECTED } }
        client.close(); client.close()
        assertEquals(1, records.size)
        assertEquals("session.read.failed", records.single().first)
        assertTrue(records.single().second is IOException)
        assertEquals(original.message, records.single().second.message)
    }

    @Test fun explicitCloseIsDistinctFromEofAndDiagnosticFailureCannotCancelOwner() = runBlocking {
        val transport = ClosingTransport()
        val events = mutableListOf<String>()
        val client = ProtocolSessionClient(transport, scope = this, onDiagnostic = { event, _ ->
            events += event
            throw IOException("diagnostic sink unavailable")
        })
        client.close()
        yield()
        assertEquals(listOf("session.closed"), events)
        assertTrue(isActive)
        assertEquals(SessionConnectionState.DISCONNECTED, client.connectionState.value)
    }

    @Test fun cleanEofIsRecordedWithoutInventingAGuestExit() = runBlocking {
        val events = mutableListOf<String>()
        ProtocolSessionClient(EndingTransport, scope = this, onDiagnostic = { event, _ -> events += event }).use { client ->
            withTimeout(1_000) { client.connectionState.first { it == SessionConnectionState.DISCONNECTED } }
            assertEquals(listOf("session.eof"), events)
        }
        assertEquals(listOf("session.eof"), events)
    }

    @Test fun sendFailureDisconnectsAnOtherwiseOpenReaderWithoutRetryingInput() = runBlocking {
        val calls = AtomicInteger()
        val transport = object : FrameTransport {
            override suspend fun send(frame: ProtocolFrame): Result<Unit> {
                calls.incrementAndGet()
                return Result.failure(IOException("write failed"))
            }
            override fun incomingFrames() = MutableSharedFlow<ProtocolFrame>()
            override fun close() = Unit
        }
        ProtocolSessionClient(transport, scope = this).use { client ->
            assertTrue(client.write("session-1", byteArrayOf(1)).isFailure)
            assertEquals(SessionConnectionState.DISCONNECTED, client.connectionState.value)
            assertTrue(client.write("session-1", byteArrayOf(1)).isFailure)
            assertEquals(1, calls.get())
            assertTrue(isActive)
        }
    }

    @Test fun responseDeadlineLeavesTheConnectionUsableAndNeverRepeatsTheRequest() = runBlocking {
        val transport = ClosingTransport()
        val diagnostics = mutableListOf<String>()
        ProtocolSessionClient(transport, requestTimeoutMillis = 100, scope = this,
            onDiagnostic = { event, _ -> diagnostics += event }).use { client ->
            val first = async { client.list() }
            withTimeout(1_000) {
                assertTrue(first.await().exceptionOrNull() is ProtocolRequestTimeoutException)
            }
            assertEquals(SessionConnectionState.CONNECTED, client.connectionState.value)
            assertTrue(diagnostics.isEmpty())
            val abandoned = transport.sent.receive()
            val terminate = async { client.terminate("session-2", 9) }
            val next = transport.sent.receive()
            assertTrue(next.requestId > abandoned.requestId)
            transport.incoming.send(ProtocolFrame(service = Service.SESSION, kind = FrameKind.RESPONSE,
                requestId = next.requestId, payload = SessionResponseCodec.encode(SessionResponse.Terminating("session-2"))))
            withTimeout(1_000) { terminate.await().getOrThrow() }
            assertTrue(isActive)
        }
    }

    @Test fun unconfirmedCreationRequiresReconciliationWithoutResendingCreate() = runBlocking {
        val transport = ClosingTransport()
        ProtocolSessionClient(transport, requestTimeoutMillis = 100, scope = this).use { client ->
            val create = async { client.create("/bin/sh", "/", rows = 24u, cols = 80u) }
            transport.sent.receive()
            assertTrue(withTimeout(1_000) { create.await() }.exceptionOrNull() is ProtocolRequestTimeoutException)
            assertEquals(SessionConnectionState.DISCONNECTED, client.connectionState.value)
            assertTrue(transport.sent.tryReceive().isFailure)
        }
    }

    @Test fun cancellingACompleteWriteKeepsSiblingCallsAndIgnoresItsLateResponse() = runBlocking {
        val transport = ClosingTransport()
        ProtocolSessionClient(transport, scope = this).use { client ->
            val write = async { client.write("session-1", byteArrayOf(1)) }
            val abandoned = transport.sent.receive()
            // The sender runs on IO; let it publish completion before cancelling
            // the caller waiting for the missing response.
            delay(50)
            withTimeout(1_000) { write.cancelAndJoin() }
            assertEquals(SessionConnectionState.CONNECTED, client.connectionState.value)
            transport.incoming.send(abandoned.copy(kind = FrameKind.RESPONSE,
                payload = SessionResponseCodec.encode(SessionResponse.Written("session-1", 1))))
            val terminate = async { client.terminate("session-2", 9) }
            val next = transport.sent.receive()
            transport.incoming.send(next.copy(kind = FrameKind.RESPONSE,
                payload = SessionResponseCodec.encode(SessionResponse.Terminating("session-2"))))
            withTimeout(1_000) { terminate.await().getOrThrow() }
        }
    }

    @Test fun deadlineClosesAndUnblocksATransportStillSending() = runBlocking {
        val transport = BlockedTransport()
        ProtocolSessionClient(transport, requestTimeoutMillis = 100, scope = this).use { client ->
            val request = async { client.write("session-1", ByteArray(1024)) }
            yield()
            assertTrue(transport.entered.await(1, TimeUnit.SECONDS))
            withTimeout(1_000) {
                assertTrue(request.await().exceptionOrNull() is ProtocolRequestTimeoutException)
            }
            assertTrue(transport.finished.await(1, TimeUnit.SECONDS))
            assertEquals(SessionConnectionState.DISCONNECTED, client.connectionState.value)
        }
    }

    @Test fun recoveringTransportRejectsNewRequestsWithoutEnqueueingOrClosingIt() = runBlocking {
        val transport = ClosingTransport()
        ProtocolSessionClient(transport, scope = this).use { client ->
            transport.linkState.value = SessionConnectionState.RECOVERING
            assertTrue(client.terminate("session-1", 9).exceptionOrNull() is ProtocolRecoveringException)
            assertTrue(transport.sent.tryReceive().isFailure)
            assertFalse(transport.incoming.isClosedForSend)
            transport.linkState.value = SessionConnectionState.CONNECTED
            val request = async { client.list() }
            val sent = transport.sent.receive()
            transport.incoming.send(ProtocolFrame(service = Service.SESSION, kind = FrameKind.RESPONSE,
                requestId = sent.requestId, payload = SessionResponseCodec.encode(SessionResponse.Listed(emptyList()))))
            withTimeout(1_000) { request.await().getOrThrow() }
            Unit
        }
    }

    @Test fun cancellingACallClosesBeforeJoiningItsBlockedSender() = runBlocking {
        val transport = BlockedTransport()
        ProtocolSessionClient(transport, scope = this).use { client ->
            val request = async { client.list() }
            // Let the request coroutine dispatch its IO sender before waiting on the latch.
            yield()
            assertTrue(transport.entered.await(1, TimeUnit.SECONDS))
            withTimeout(1_000) { request.cancelAndJoin() }
            assertTrue(transport.finished.await(1, TimeUnit.SECONDS))
            assertEquals(SessionConnectionState.DISCONNECTED, client.connectionState.value)
            assertTrue(isActive)
        }
    }

    private class BlockedTransport : FrameTransport {
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        private val released = CountDownLatch(1)
        override suspend fun send(frame: ProtocolFrame): Result<Unit> {
            entered.countDown()
            try { released.await(); return Result.failure(IOException("closed while sending")) }
            finally { finished.countDown() }
        }
        override fun incomingFrames() = MutableSharedFlow<ProtocolFrame>()
        override fun close() { released.countDown() }
    }

    private class ClosingTransport(private val closeFailure: IOException? = null) : FrameTransport {
        override val linkState = kotlinx.coroutines.flow.MutableStateFlow(SessionConnectionState.CONNECTED)
        val sent = Channel<ProtocolFrame>(Channel.UNLIMITED)
        val incoming = Channel<ProtocolFrame>(Channel.UNLIMITED)
        override suspend fun send(frame: ProtocolFrame): Result<Unit> {
            sent.send(frame)
            return Result.success(Unit)
        }
        override fun incomingFrames(): Flow<ProtocolFrame> = incoming.receiveAsFlow()
        override fun close() {
            incoming.close()
            sent.close()
            closeFailure?.let { throw it }
        }
    }

    private class FakeTransport : FrameTransport {
        val sent = Channel<ProtocolFrame>(Channel.UNLIMITED)
        private val incoming = MutableSharedFlow<ProtocolFrame>(extraBufferCapacity = 8)

        override suspend fun send(frame: ProtocolFrame): Result<Unit> {
            sent.send(frame)
            return Result.success(Unit)
        }

        override fun incomingFrames(): Flow<ProtocolFrame> = incoming
        fun receive(frame: ProtocolFrame) { check(incoming.tryEmit(frame)) }
        override fun close() { sent.close() }
    }

    private object EndingTransport : FrameTransport {
        override suspend fun send(frame: ProtocolFrame): Result<Unit> = Result.failure(IllegalStateException("closed"))
        override fun incomingFrames(): Flow<ProtocolFrame> = emptyFlow()
        override fun close() = Unit
    }
}
