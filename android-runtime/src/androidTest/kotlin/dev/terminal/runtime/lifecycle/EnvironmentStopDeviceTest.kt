package dev.terminal.runtime.lifecycle

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.api.RuntimeConfig
import dev.terminal.runtime.api.StopMode
import dev.terminal.runtime.native.NativeBackend
import dev.terminal.runtime.session.Environment
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.session.UnifiedSessionRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnvironmentStopDeviceTest {
    @Test fun stoppedOwnerRemovesItsSessionWhileSiblingPtyKeepsWorking() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "stop-device-${UUID.randomUUID()}")
        val backends = List(2) { NativeBackend(context, File(root, "owner-$it")) }
        val environments = List(2) { Environment.create(SessionRuntime.NATIVE, "owner-$it") }
        val removed = mutableListOf<String>()
        val registry = UnifiedSessionRegistry(onRemoved = { removed.addAll(it) })
        try {
            val sessions = backends.mapIndexed { index, backend ->
                backend.prepare(RuntimeConfig(imageDirectory = root.path)).getOrThrow()
                backend.start().getOrThrow()
                registry.register(environments[index], requireNotNull(backend.sessionController()))
                registry.create(SessionRuntime.NATIVE, "/system/bin/sh", "", rows = 24u, cols = 80u,
                    storageId = environments[index].storage.id).getOrThrow()
            }
            registry.forgetStoppedEnvironment(environments[0]) { backends[0].stop(StopMode.GRACEFUL).getOrThrow() }
            assertEquals(listOf(sessions[1].id), registry.list().getOrThrow().map { it.id })
            assertEquals(listOf(sessions[0].id), removed)
            assertFalse(registry.hasSessionOwner(sessions[0].id))
            assertNull(registry.inputTarget(sessions[0].id))
            assertTrue(registry.write(sessions[0].id, byteArrayOf(1)).isFailure)
            val marker = File(root, "sibling-response")
            registry.write(sessions[1].id, "printf ALIVE > '${marker.path}'\n".toByteArray()).getOrThrow()
            withTimeout(5_000) { while (!marker.isFile || marker.readText() != "ALIVE") delay(20) }
        } finally {
            registry.close()
            backends.forEach { it.stop(StopMode.GRACEFUL) }
            root.deleteRecursively()
        }
    }
}
