package dev.terminal.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalSession
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscriptReplayDeliveryTest {
    @Test fun historicalQueriesNeverReachTheInputTransportButLiveQueriesStillDo() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val writes = mutableListOf<String>()
        val backend = RemoteTerminalBackend(write = { writes += it.decodeToString() }, resize = { _, _ -> })
        lateinit var terminal: TerminalSession
        instrumentation.runOnMainSync {
            terminal = TerminalSession("", "/", emptyArray(), emptyArray(), 1000, TerminalCallbacks {}, backend)
            terminal.initializeEmulator(80, 24, 10, 20)
        }
        // More than the normal 4 KiB input queue: the replay marker must survive delivery.
        val history = ("\u001b[6n\u001b]10;?\u0007".repeat(500) + "restored").toByteArray()
        backend.output(history, replay = true)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals("restored", terminal.emulator.screen.transcriptText)
            assertEquals(emptyList<String>(), writes)
        }
        backend.output("\u001b[6n".toByteArray())
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { assertEquals(listOf("\u001b[1;9R"), writes) }
        backend.close()
    }
}
