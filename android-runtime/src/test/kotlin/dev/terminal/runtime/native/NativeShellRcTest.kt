package dev.terminal.runtime.native

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeShellRcTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `mksh colour escapes are delimited and reset before input`() {
        val home = temporary.newFolder("colours")
        val coloured = prompt(home, home, mapOf("TERM" to "xterm-256color", "KSH_VERSION" to "test-mksh"))
        val ready = "\u0001\u001b]133;B\u0007\u0001"
        assertEquals("\u0001\r\u0001\u001b[0;32m\u0001~\u0001\u001b[0m\u0001 $ $ready", coloured)
        assertEquals("\u0001~ $ $ready", prompt(home, home, mapOf("TERM" to "dumb", "KSH_VERSION" to "test-mksh")))
        assertEquals("\u0001~ $ $ready", prompt(home, home, mapOf("TERM" to "xterm", "KSH_VERSION" to "test-mksh", "NO_COLOR" to "1")))
    }

    @Test fun `home aliases and children use tilde but outside paths do not`() {
        val home = temporary.newFolder("home")
        val alias = java.io.File(temporary.root, "alias")
        java.nio.file.Files.createSymbolicLink(alias.toPath(), home.toPath())
        val child = java.io.File(home, "my work").apply { mkdir() }
        val outside = temporary.newFolder("home-other")
        assertEquals("~ $ ", prompt(home, home))
        assertEquals("~/my work $ ", prompt(home, child))
        assertEquals("~ $ ", prompt(alias, home))
        assertEquals("~/my work $ ", prompt(alias, child))
        assertEquals("${outside.absolutePath} $ ", prompt(home, outside))
    }

    private fun prompt(home: java.io.File, cwd: java.io.File, env: Map<String, String> = emptyMap()): String {
        val rc = prepareNativeShellRc(home)
        val script = """
            . "${'$'}1"
            eval "result=\"${'$'}PS1\""
            printf '%s' "${'$'}result"
        """.trimIndent()
        val process = ProcessBuilder("/bin/sh", "-c", script, "prompt-test", rc.absolutePath)
            .directory(cwd).apply {
                environment()["HOME"] = home.absolutePath
                environment()["TERM"] = "dumb"
                environment().remove("NO_COLOR")
                environment().putAll(env)
            }.start()
        val result = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()
        assertEquals(error, 0, process.waitFor())
        return result
    }
}
