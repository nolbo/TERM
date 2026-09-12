package dev.terminal.app

import com.termux.terminal.TerminalSessionBackend
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteTerminalBackendTest {
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
