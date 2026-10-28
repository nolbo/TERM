package dev.terminal.app

import dev.terminal.emulator.TerminalSessionBackend
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteTerminalBackendTest {
    @Test fun interruptedHistoryRejectsInputWithoutInventingAnExitAndStillAttaches() {
        var writes = 0
        var exits = 0
        var attaches = 0
        val backend = RemoteTerminalBackend(write = { writes++ }, resize = { _, _ -> }, authoritative = true,
            onStarted = { attaches++ })
        backend.interrupt()
        backend.start(80, 24, 0, 0, object : TerminalSessionBackend.Callback {
            override fun onOutput(data: ByteArray, offset: Int, count: Int) = Unit
            override fun onExit(exitCode: Int) { exits++ }
        })
        backend.write(byteArrayOf(1), 0, 1)
        assertEquals(false, backend.isRunning())
        assertEquals(0, writes)
        assertEquals(0, exits)
        assertEquals(1, attaches)
    }
    @Test fun paneResizesRejectZeroDimensionsAndCoalesceIdenticalPendingGeometryPerSession() {
        var firstResizes = 0
        var secondResizes = 0
        val first = RemoteTerminalBackend(write = {}, resize = { _, _ -> }, authoritative = true,
            onGeometry = { firstResizes++ })
        val second = RemoteTerminalBackend(write = {}, resize = { _, _ -> }, authoritative = true,
            onGeometry = { secondResizes++ })
        first.resize(40, 12, 10, 20)
        repeat(10) { first.resize(40, 12, 10, 20) }
        first.resize(0, 0, 0, 0)
        first.resize(80, 0, 10, 20)
        assertEquals(1, firstResizes)
        assertEquals(0, secondResizes)
        assertEquals(40, first.columns)
        assertEquals(12, first.rows)
        second.resize(40, 12, 10, 20)
        first.resize(40, 12, 11, 21)
        assertEquals(2, firstResizes)
        assertEquals(1, secondResizes)
    }
    @Test fun initialGeometryIsSentBeforeAttachmentAndLaterResizesStillWork() {
        val events = mutableListOf<String>()
        val backend = RemoteTerminalBackend(
            write = {}, resize = { columns, rows -> events += "$columns x $rows" },
            onStarted = { events += "attach" },
        )
        backend.start(42, 19, 10, 20, object : TerminalSessionBackend.Callback {
            override fun onOutput(data: ByteArray, offset: Int, count: Int) = Unit
            override fun onExit(exitCode: Int) = Unit
        })
        backend.resize(60, 25, 10, 20)
        assertEquals(listOf("42 x 19", "attach", "60 x 25"), events)
    }

    @Test fun initialTranscriptRequestedAfterOutputReceiverIsInstalled() {
        val received = StringBuilder()
        lateinit var backend: RemoteTerminalBackend
        backend = RemoteTerminalBackend(
            write = {}, resize = { _, _ -> },
            onStarted = { backend.output("~ $ ".toByteArray()) },
        )
        backend.start(80, 24, 0, 0, object : TerminalSessionBackend.Callback {
            override fun onOutput(data: ByteArray, offset: Int, count: Int) {
                received.append(String(data, offset, count))
            }
            override fun onExit(exitCode: Int) = Unit
        })
        assertEquals("~ $ ", received.toString())
    }
}
