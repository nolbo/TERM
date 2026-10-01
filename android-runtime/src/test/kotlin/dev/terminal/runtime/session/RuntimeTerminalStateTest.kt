package dev.terminal.runtime.session

import com.termux.terminal.TerminalCheckpoint
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class RuntimeTerminalStateTest {
    private class Output : TerminalOutput() {
        val replies = mutableListOf<String>()
        override fun write(bytes: ByteArray, offset: Int, count: Int) { replies += String(bytes, offset, count) }
        override fun titleChanged(old: String?, new: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }
    private class Mirror : RuntimeTerminalState.Sink {
        val output = Output()
        val emulator = TerminalEmulator(output, 8, 4, 1, 1, 100, null)
        val order = mutableListOf<String>()
        var viewport: RuntimeTerminalState.Viewport? = null
        override fun checkpoint(state: TerminalEmulator, viewport: RuntimeTerminalState.Viewport?) {
            val bytes = ByteArrayOutputStream()
            TerminalCheckpoint.write(state, bytes)
            emulator.restoreState(TerminalCheckpoint.read(ByteArrayInputStream(bytes.toByteArray())))
            this.viewport = viewport
            order += "checkpoint"
        }
        override fun output(bytes: ByteArray) { emulator.appendMirror(bytes, bytes.size); order += "output" }
        override fun resized(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
            emulator.resize(columns, rows, cellWidth, cellHeight); order += "resize"
        }
        override fun exited(code: Int) { order += "exit:$code" }
    }

    @Test fun freshUiRestoresBothBuffersParserModesAndHistoryAfterMoreThanRawReplayLimit() {
        val state = RuntimeTerminalState()
        state.create("id", 40, 8)
        state.output("id", (0..150).joinToString("\r\n") { "history-$it" }.toByteArray())
        state.output("id", "\u001b[?1049h\u001b[?1003h\u001b[?1006h\u001b[?1007l\u001b[?2004hALT".toByteArray())
        repeat(144) { state.output("id", ByteArray(64 * 1024)) } // Nine MiB: initial modes no longer exist in raw replay.
        state.output("id", "\u001b[4;".toByteArray())
        val mirror = Mirror(); state.subscribe("id", mirror)
        assertTrue(mirror.emulator.isAlternateBufferActive)
        assertTrue(mirror.emulator.isMouseMotionTrackingActive)
        assertFalse(mirror.emulator.isAlternateScrollEnabled)
        state.output("id", "1HRESUMED".toByteArray())
        assertEquals("RESUMED", mirror.emulator.screen.getSelectedText(0, 3, 6, 3))
        state.output("id", "\u001b[?1049l".toByteArray())
        assertTrue(mirror.emulator.screen.transcriptText.contains("history-0"))
        assertTrue(mirror.output.replies.isEmpty())
    }

    @Test fun terminalQueriesAreAnsweredWithoutUiAndNeverRepeatedByAReceiver() {
        val state = RuntimeTerminalState(); state.create("id", 40, 8)
        assertEquals(listOf("\u001b[1;1R"), state.output("id", "\u001b[6n".toByteArray()).map { String(it) })
        val mirror = Mirror(); state.subscribe("id", mirror)
        assertTrue(mirror.output.replies.isEmpty())
        assertEquals(listOf("\u001b[1;1R"), state.output("id", "\u001b[6n".toByteArray()).map { String(it) })
        assertTrue(mirror.output.replies.isEmpty())
        state.output("id", "\u001b[?1003h\u001b[?1006h".toByteArray())
        mirror.emulator.sendMouseEvent(TerminalEmulator.MOUSE_WHEELUP_BUTTON, 1, 1, true)
        assertEquals(listOf("\u001b[<64;1;1M"), mirror.output.replies)
    }

    @Test fun checkpointOutputResizeExitAndSavedViewportHaveOneOrder() {
        val state = RuntimeTerminalState(); state.create("id", 40, 8)
        val anchor = RuntimeTerminalState.Viewport(-40, .25f, 150)
        state.viewport("id", anchor)
        val first = Mirror(); state.subscribe("id", first)
        state.output("id", "first".toByteArray()); state.resize("id", 30, 10, 12, 24)
        state.unsubscribe("id", first)
        state.output("id", "after detach".toByteArray())
        val second = Mirror(); state.subscribe("id", second)
        assertEquals(anchor, second.viewport)
        state.exited("id", 7)
        assertEquals(listOf("checkpoint", "output", "resize"), first.order)
        assertEquals(listOf("checkpoint", "exit:7"), second.order)
        assertTrue(second.emulator.screen.transcriptText.contains("after detach"))
        state.remove("id")
        assertTrue(runCatching { state.subscribe("id", Mirror()) }.isFailure)
        assertTrue(state.output("id", "queued after termination".toByteArray()).isEmpty())
    }

    @Test fun initialAndRestoredPaletteQueriesUseTheSameDefaultsAndKeepOscChanges() {
        val state = RuntimeTerminalState(); state.create("id", 40, 8)
        val colors = TerminalPalette.resolve({ key, fallback -> if (key == "palette") "SOLARIZED_DARK" else fallback })
        val properties = TerminalPalette.asProperties(colors)
        state.palette("id", properties)
        val reply = state.output("id", "\u001b]11;?\u001b\\".toByteArray()).single().decodeToString()
        assertTrue(reply.contains("rgb:0000/2b2b/3636"))
        state.output("id", "\u001b]11;#123456\u001b\\".toByteArray())
        state.palette("id", properties)
        val mirror = Mirror(); state.subscribe("id", mirror)
        assertEquals(0xff123456.toInt(), mirror.emulator.mColors.mCurrentColors[257])
        assertEquals(colors, TerminalPalette.resolve({ _, _ -> "invalid" }, colors))
    }
}
