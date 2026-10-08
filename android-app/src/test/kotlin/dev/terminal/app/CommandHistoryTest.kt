package dev.terminal.app

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import org.junit.Assert.*
import org.junit.Test

class CommandHistoryTest {
    private val output = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) = Unit
        override fun titleChanged(old: String?, new: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }
    private fun terminal() = TerminalEmulator(output, 40, 4, 1, 1, 100, null)
    private fun TerminalEmulator.append(text: String) = text.toByteArray().let { append(it, it.size) }
    private fun TerminalEmulator.command(text: String, result: String, exit: Int? = 0) {
        append("\u001b]133;A\u0007$ \u001b]133;B\u0007$text\r\n\u001b]133;C\u0007$result\r\n")
        if (exit != null) append("\u001b]133;D;$exit\u0007")
    }

    @Test fun graphAlwaysReadsAllCommandsWithTheirStatus() {
        val terminal = terminal()
        terminal.command("echo needle", "alpha")
        terminal.command("printf beta", "needle", 3)
        terminal.command("sleep 1", "working", null)
        val all = readCommandHistory(terminal)
        assertEquals(listOf(HistoryCommandStatus.Completed, HistoryCommandStatus.Failed, HistoryCommandStatus.Running), all.map { it.status })
        assertEquals(listOf("echo needle", "printf beta", "sleep 1"), all.map { it.marker.commandText })
        assertEquals(listOf(0, 3, null), all.map { it.exitCode })
    }

    @Test fun navigationChangesToEdgesAndUsesIdentityForCommandsSharingTheViewport() {
        val terminal = terminal()
        repeat(3) { terminal.command("command-$it", "output") }
        val commands = readCommandHistory(terminal)
        val first = commands.first()
        val last = commands.last()
        assertNull(commandHistoryTarget(commands, first.viewportRow, false, first.marker).command)
        assertEquals(commands[1], commandHistoryTarget(commands, first.viewportRow, true, first.marker).command)
        assertNull(commandHistoryTarget(commands, last.viewportRow, true, last.marker).command)
        assertTrue(commandHistoryTarget(commands, last.viewportRow, true, last.marker).bottom)
        assertEquals(commands[1], commandHistoryTarget(commands, last.viewportRow, false, last.marker).command)
        assertEquals(last.marker, currentHistoryCommand(commands, 0, null))
        assertEquals(commands[1].marker, currentHistoryCommand(commands, commands[1].viewportRow, commands[1].marker))
        assertEquals(first, commandHistoryTarget(commands, first.viewportRow - 1, true).command)
        assertNull(commandHistoryTarget(emptyList(), -5, false).command)
    }

    @Test fun trimRemovesGraphCommandsWithTheirHistory() {
        val terminal = terminal()
        terminal.command("first", "match")
        terminal.command("ignored", "other")
        terminal.command("third", "match")
        val commands = readCommandHistory(terminal)
        assertEquals(3, commands.size)
        assertEquals(commands[1], commandHistoryTarget(commands, commands.first().viewportRow, true, commands.first().marker).command)
        repeat(150) { terminal.append("trim\r\n") }
        assertTrue(readCommandHistory(terminal).isEmpty())
    }
}
