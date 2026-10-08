package dev.terminal.runtime.notifications

import org.junit.Assert.*
import org.junit.Test

class ShellActivityIntegrationTest {
    @Test fun bashReportsStartAndPreservesExitCodeForExistingPromptHook() {
        val env = shellActivityEnvironment("/bin/bash", mapOf("PROMPT_COMMAND" to "printf 'user-status=%s\\n' \"\$?\""))
        val process = ProcessBuilder("/bin/bash", "--noprofile", "--norc", "-i")
            .redirectErrorStream(true).apply { environment().putAll(env) }.start()
        process.outputStream.bufferedWriter().use { it.write("false\nexit\n") }
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        assertTrue(output, output.contains("\u001b]133;C\u0007"))
        assertTrue(output, output.contains("\u001b]133;D;1\u0007user-status=1"))
    }
    @Test fun integrationDoesNotExceedGuestEnvironmentBounds() {
        val full = (1..63).associate { "VAR_$it" to "value" }
        assertEquals(full, shellActivityEnvironment("/bin/bash", full))
        val long = mapOf("PROMPT_COMMAND" to "x".repeat(4096))
        assertEquals(long, shellActivityEnvironment("/bin/bash", long))
    }
    @Test fun otherShellsAndUserEnvironmentAreUnchanged() {
        val env = mapOf("CUSTOM" to "value", "PS0" to "custom prefix")
        assertEquals(env, shellActivityEnvironment("/bin/zsh", env))
        val bash = shellActivityEnvironment("/bin/bash", env)
        assertEquals("value", bash["CUSTOM"])
        assertTrue(bash.getValue("PS0").endsWith("custom prefix"))
    }
}
