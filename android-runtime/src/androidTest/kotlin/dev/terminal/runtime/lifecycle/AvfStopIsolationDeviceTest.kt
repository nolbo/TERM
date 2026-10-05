package dev.terminal.runtime.lifecycle

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.protocol.FrameKind
import dev.terminal.protocol.FrameTransport
import dev.terminal.protocol.ProtocolFrame
import dev.terminal.protocol.ProtocolSessionClient
import dev.terminal.protocol.Service
import dev.terminal.protocol.SessionConnectionState
import dev.terminal.protocol.SessionResponse
import dev.terminal.protocol.SessionResponseCodec
import dev.terminal.protocol.SessionStatus
import dev.terminal.runtime.api.RuntimeConfig
import dev.terminal.runtime.native.NativeBackend
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.session.UnifiedSessionRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises Android's real uncaught-exception handling and a live sibling PTY,
 * with an injected AVF transport reset; no AVF feature or privileged image is needed.
 */
@RunWith(AndroidJUnit4::class)
class AvfStopIsolationDeviceTest {
    @Test fun avfReadFailureDuringStopKeepsTheOwnerProcessAndSiblingShellAlive() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "avf-stop-isolation-${UUID.randomUUID()}")
        val native = NativeBackend(context, root)
        val registry = UnifiedSessionRegistry()
        val transport = IdleAvfTransport()
        // Use the production default scope: an unhandled receive error here
        // invokes Android's crash handler and kills the shared PTY owner.
        val avf = ProtocolSessionClient(transport)
        try {
            native.prepare(RuntimeConfig(imageDirectory = root.absolutePath)).getOrThrow()
            native.start().getOrThrow()
            registry.register(SessionRuntime.NATIVE, requireNotNull(native.sessionController()))
            registry.register(SessionRuntime.AVF, avf)
            val session = registry.create(SessionRuntime.NATIVE, "/system/bin/sh", "",
                rows = 24u, cols = 80u).getOrThrow()
            val marker = File(root, "home/response")
            suspend fun execute(value: String) {
                registry.write(session.id, "printf $value > '${marker.absolutePath}'\n".toByteArray()).getOrThrow()
                withTimeout(5_000) {
                    while (!marker.isFile || marker.readText() != value) delay(25)
                }
            }
            execute("before-stop")

            registry.deleteStorageIfIdle(SessionRuntime.AVF, "legacy") { release ->
                transport.incoming.close(IOException("AVF vsock reset during stop"))
                withTimeout(5_000) {
                    avf.connectionState.first { it == SessionConnectionState.DISCONNECTED }
                }
                release()
            }

            assertFalse(registry.isRegistered(SessionRuntime.AVF))
            assertEquals(session.id, registry.list().getOrThrow().single().id)
            assertEquals(SessionStatus.RUNNING, registry.list().getOrThrow().single().state)
            execute("after-stop")
        } finally {
            avf.close()
            registry.close()
            root.deleteRecursively()
        }
    }

    private class IdleAvfTransport : FrameTransport {
        val incoming = Channel<ProtocolFrame>(Channel.UNLIMITED)
        override suspend fun send(frame: ProtocolFrame): Result<Unit> = runCatching {
            incoming.send(ProtocolFrame(service = Service.SESSION, kind = FrameKind.RESPONSE,
                requestId = frame.requestId, payload = SessionResponseCodec.encode(SessionResponse.Listed(emptyList()))))
        }
        override fun incomingFrames(): Flow<ProtocolFrame> = incoming.receiveAsFlow()
        override fun close() { incoming.close() }
    }
}
