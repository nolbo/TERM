package dev.terminal.app

import org.junit.Assert.*
import org.junit.Test

class ToolbarCommandsTest {
    @Test fun multilineCommandsRoundTripWithoutExecutingOrChangingText() {
        val commands = listOf(ToolbarCommand("one", "Build", "cd ~/project\n./gradlew test"),
            ToolbarCommand("two", "Status", "printf '%s\\n' \"\$HOME\""))
        assertEquals(commands, parseToolbarCommands(serializeToolbarCommands(commands)))
    }
    @Test fun rejectsEmptyNamesOversizedTextAndControlSequences() {
        assertFalse(validToolbarCommand(ToolbarCommand(name = "", text = "ls")))
        assertFalse(validToolbarCommand(ToolbarCommand(name = "x", text = "a".repeat(MAX_TOOLBAR_TEXT + 1))))
        assertFalse(validToolbarCommand(ToolbarCommand(name = "x", text = "ls\u001b[31m")))
        assertTrue(validToolbarCommand(ToolbarCommand(name = "x", text = "echo\tOK\npwd")))
        assertEquals(emptyList<ToolbarCommand>(), parseToolbarCommands("broken"))
    }
    @Test fun duplicateIdsAndTooManyCommandsCannotBeSaved() {
        val command = ToolbarCommand("same", "x", "true")
        assertTrue(runCatching { serializeToolbarCommands(listOf(command, command)) }.isFailure)
        assertTrue(runCatching { serializeToolbarCommands((1..65).map { command.copy(id = "$it") }) }.isFailure)
    }
}
