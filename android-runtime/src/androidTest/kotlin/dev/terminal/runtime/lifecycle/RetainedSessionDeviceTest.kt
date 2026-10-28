package dev.terminal.runtime.lifecycle

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.emulator.TerminalEmulator
import dev.terminal.runtime.api.RuntimeConfig
import dev.terminal.runtime.api.StopMode
import dev.terminal.runtime.native.NativeBackend
import dev.terminal.runtime.session.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RetainedSessionDeviceTest {
    @Test fun realPtyStopRetainsOutputAcrossOwnerRecreationAndExplicitCloseRemovesHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "retained-device-${UUID.randomUUID()}")
        val native = NativeBackend(context, File(root, "native"))
        val archive = RetainedSessionArchive(File(root, "history")) { throw AssertionError(it) }
        val environment = Environment.create(SessionRuntime.NATIVE, "history fixture")
        var registry = UnifiedSessionRegistry(archive = archive)
        try {
            native.prepare(RuntimeConfig(imageDirectory = root.path)).getOrThrow()
            native.start().getOrThrow()
            registry.register(environment, requireNotNull(native.sessionController()))
            val session = registry.create(SessionRuntime.NATIVE, "/system/bin/sh", "", rows = 24u, cols = 80u,
                storageId = environment.storage.id).getOrThrow()
            suspend fun text(): String {
                var value = ""
                val sink = object : RuntimeTerminalState.Sink {
                    override fun checkpoint(emulator: TerminalEmulator, viewport: RuntimeTerminalState.Viewport?) { value = emulator.screen.transcriptText }
                    override fun output(bytes: ByteArray) = Unit
                    override fun resized(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) = Unit
                    override fun exited(code: Int) = Unit
                }
                registry.terminalStates.withSession(session.id) {
                    registry.terminalStates.subscribe(session.id, sink)
                    registry.terminalStates.unsubscribe(session.id, sink)
                }
                return value
            }
            registry.write(session.id, "printf 'HISTORY_%s\\n' RETAINED\n".toByteArray()).getOrThrow()
            withTimeout(5_000) { while (!text().contains("HISTORY_RETAINED")) delay(20) }
            registry.retainStoppedEnvironment(environment) { native.stop(StopMode.GRACEFUL).getOrThrow() }
            assertFalse(registry.list().getOrThrow().single().state.isLive)
            assertTrue(registry.write(session.id, byteArrayOf(1)).isFailure)
            registry.close()
            registry = UnifiedSessionRegistry(archive = archive)
            assertEquals(session.id, registry.list().getOrThrow().single().id)
            registry.configureTerminal(session.id, 30, 100, 0, 0, java.util.Properties())
            assertTrue(text().contains("HISTORY_RETAINED"))
            registry.terminate(session.id, 9).getOrThrow()
            registry.close()
            registry = UnifiedSessionRegistry(archive = archive)
            assertTrue(registry.list().getOrThrow().isEmpty())
        } finally { registry.close(); native.stop(StopMode.GRACEFUL); root.deleteRecursively() }
    }
}
