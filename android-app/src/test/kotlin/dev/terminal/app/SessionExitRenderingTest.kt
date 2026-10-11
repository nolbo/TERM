package dev.terminal.app

import dev.terminal.emulator.TerminalSessionBackend
import dev.terminal.app.ui.parseSessions
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionExitRenderingTest {
    @Test fun attachingLiveNativeAndRootlessSessionsKeepsInputAndOutputWorking() {
        for (runtime in listOf("NATIVE", "ROOTLESS")) {
            // Older service versions included the binary descriptor's -1 default
            // in the JSON for a live shell. Exercise the renderer attach path.
            val session = parseSessions("""[{"id":"shell-1","runtime":"$runtime","state":"RUNNING","exitCode":-1}]""").single()
            val writes = mutableListOf<ByteArray>()
            val backend = RemoteTerminalBackend(write = { writes += it }, resize = { _, _ -> })
            val callback = RecordingCallback()
            session.exitCode?.let(backend::exit)
            backend.start(80, 24, 0, 0, callback)

            assertTrue("$runtime should remain interactive", backend.isRunning)
            assertTrue(callback.exits.isEmpty())
            val command = "echo ready\n".encodeToByteArray()
            backend.write(command, 0, command.size)
            backend.output("ready\r\n".encodeToByteArray())
            assertArrayEquals(command, writes.single())
            assertArrayEquals("ready\r\n".encodeToByteArray(), callback.outputs.single())
            backend.close()
        }
    }

    @Test fun attachingAnExitedSessionStillReportsActualSighup() {
        val session = parseSessions("""[{"id":"shell-1","state":"EXITED","exitCode":-1}]""").single()
        val backend = RemoteTerminalBackend(write = {}, resize = { _, _ -> })
        val callback = RecordingCallback()
        session.exitCode?.let(backend::exit)
        backend.start(80, 24, 0, 0, callback)
        assertFalse(backend.isRunning)
        assertEquals(listOf(-1), callback.exits)
        backend.close()
    }

    private class RecordingCallback : TerminalSessionBackend.Callback {
        val outputs = mutableListOf<ByteArray>()
        val exits = mutableListOf<Int>()
        override fun onOutput(data: ByteArray, offset: Int, count: Int) {
            outputs += data.copyOfRange(offset, offset + count)
        }
        override fun onExit(exitCode: Int) { exits += exitCode }
    }
}
