package dev.terminal.runtime.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellPromptIntegrationTest {
    @Test fun `bash emits readiness after primary and continuation prompts`() {
        // A real PTY exercises readline, bracketed paste and PS2 rather than pipe-only parsing.
        val process = ProcessBuilder("/usr/bin/script", "-qefc", "/bin/bash --noprofile --norc -i", "/dev/null")
            .redirectErrorStream(true).apply {
                environment()["TERM"] = "xterm-256color"
                environment()["PS1"] = "test $ "
                environment()["PROMPT_COMMAND"] = bashPromptCommand("PASTE_HOOK=preserved")
            }.start()
        val input = process.outputStream.bufferedWriter()
        fun send(line: String) {
            input.write("\u001b[200~${line.removeSuffix("\n")}\u001b[201~\r")
            input.flush()
        }
        fun prompt(): String {
            val output = StringBuilder()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                while (process.inputStream.available() > 0) {
                    output.append(process.inputStream.read().toChar())
                    if (output.endsWith("\u001b]133;B\u0007")) return output.toString()
                }
                Thread.sleep(5)
            }
            throw AssertionError("Missing Bash prompt notification: $output")
        }
        try {
            prompt()
            send("echo PASTE_\\\n")
            assertTrue(prompt().contains("> "))
            send("JOINED\n")
            assertTrue(prompt().contains("PASTE_JOINED"))
            send("cat <<'END'\n")
            assertTrue(prompt().contains("> "))
            send("HEREDOC_VALUE\n")
            assertTrue(prompt().contains("> "))
            send("END\n")
            assertTrue(prompt().contains("HEREDOC_VALUE"))
            send("printf '%s\\n' \"\${PASTE_HOOK}\"\n")
            assertTrue(prompt().contains("preserved"))
            send("printf '%s\\n' 'literal\ttab'\n")
            assertTrue(prompt().contains("literal\ttab"))
            send("if read -t 0.2 stolen; then echo BAD_INPUT; fi\n")
            assertFalse(prompt().contains("\nBAD_INPUT\n"))
            send("exit\n")
        } finally {
            process.destroyForcibly()
        }
    }
}
