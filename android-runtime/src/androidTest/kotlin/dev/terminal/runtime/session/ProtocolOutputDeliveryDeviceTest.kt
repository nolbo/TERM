package dev.terminal.runtime.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.terminal.emulator.TerminalEmulator
import dev.terminal.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProtocolOutputDeliveryDeviceTest {
    @Test fun pressureKeepsResizeReplyAndParserCheckpointOrdered() = runBlocking {
        withTimeout(10_000) {
            val incoming = Channel<ProtocolFrame>(Channel.UNLIMITED)
            val requests = Channel<ProtocolFrame>(Channel.UNLIMITED)
            val transport = object : FrameTransport {
                override suspend fun send(frame: ProtocolFrame): Result<Unit> { requests.send(frame); return Result.success(Unit) }
                override fun incomingFrames() = incoming.receiveAsFlow()
                override fun close() { incoming.close(); requests.close() }
            }
            val state = RuntimeTerminalState(100)
            state.create("a", 40, 8)
            state.output("a", "BEFORE\u001b]0;partial".toByteArray())
            val calls = mutableListOf<String>()
            var finalText = ""
            val sink = object : RuntimeTerminalState.Sink {
                override fun checkpoint(emulator: TerminalEmulator, viewport: RuntimeTerminalState.Viewport?) {
                    finalText = emulator.screen.transcriptText
                    calls += "checkpoint"
                }
                override fun output(bytes: ByteArray) { calls += if (bytes.decodeToString().contains("AFTER")) "AFTER" else "output" }
                override fun resized(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) { calls += "resize:$columns" }
                override fun exited(code: Int) { calls += "exit:$code" }
            }
            state.subscribe("a", sink)
            val stalled = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            ProtocolSessionClient(transport, requestTimeoutMillis = 2_000, scope = this).use { client ->
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    client.events.collect { event ->
                        state.withSession("a") {
                            when (event) {
                                is SessionEvent.Output -> state.output("a", event.bytes)
                                is SessionEvent.OutputGap -> state.outputGap("a")
                                is SessionEvent.Resized -> state.resize("a", event.cols.toInt(), event.rows.toInt())
                                is SessionEvent.Exited -> { state.exited("a", event.exitCode); finished.complete(Unit) }
                            }
                        }
                        stalled.complete(Unit)
                        release.await()
                    }
                }
                fun output(event: SessionEvent) = ProtocolFrame(service = Service.SESSION, kind = FrameKind.STREAM,
                    requestId = 0, payload = SessionEventCodec.encode(event))
                val packet = output(SessionEvent.Output("a", byteArrayOf(120)))
                incoming.send(packet)
                stalled.await()
                repeat(1024) { incoming.send(packet) }
                val resize = async { client.resizeOrdered("a", 8u, 41u, Any()) }
                val request = requests.receive()
                incoming.send(request.copy(kind = FrameKind.RESPONSE, payload = SessionResponseCodec.encode(
                    SessionResponse.Resized(SessionDescriptor("a", "Shell", "/bin/sh", "/", 0, 8u, 41u, SessionStatus.RUNNING)))))
                incoming.send(output(SessionEvent.Output("a", "\r\nAFTER".toByteArray())))
                incoming.send(output(SessionEvent.Exited("a", 7)))
                resize.await().getOrThrow()
                assertEquals(SessionConnectionState.CONNECTED, client.connectionState.value)
                release.complete(Unit)
                finished.await()
                val boundary = calls.indexOf("resize:41")
                assertTrue(calls.take(boundary).count { it == "checkpoint" } >= 2)
                assertTrue(boundary in 1 until calls.indexOf("AFTER"))
                assertEquals("exit:7", calls.last())
                state.unsubscribe("a", sink)
                state.subscribe("a", sink) // Capture the actual Android emulator after delivery.
                assertTrue(finalText.contains("BEFORE"))
                assertTrue(finalText.contains("AFTER"))
                collector.cancelAndJoin()
            }
            state.clear()
        }
    }
}
