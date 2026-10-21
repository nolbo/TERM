package dev.terminal.runtime.native

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeEnvironmentTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `owned environment and broker values override profile injection without unsafe PATH elements`() {
        val home = temporary.newFolder("home")
        val tmp = temporary.newFolder("tmp")
        val bin = temporary.newFolder("bin")
        val extra = temporary.newFolder("extra")
        val rc = prepareNativeShellRc(home)
        val result = nativeEnvironment(home, tmp, "/system/bin/sh", rc,
            mapOf("HOME" to "/", "ENV" to "/bad", "PATH" to ":.:relative:${extra.absolutePath}:",
                "TERMINAL_USB_TOKEN" to "injected", "TERM" to "xterm", "DROID_LOGIN_SHELL" to "false"),
            mapOf("PATH" to bin.absolutePath, "LD_LIBRARY_PATH" to "/apk"),
            mapOf("TERMINAL_USB_TOKEN" to "broker"))
        assertEquals(home.absolutePath, result["HOME"])
        assertEquals(tmp.absolutePath, result["TMPDIR"])
        assertEquals(rc.absolutePath, result["ENV"])
        assertEquals("${bin.absolutePath}:/system/bin:/system/xbin:/vendor/bin:${extra.absolutePath}", result["PATH"])
        assertEquals("broker", result["TERMINAL_USB_TOKEN"])
        assertEquals("xterm", result["TERM"])
        assertFalse(result.containsKey("DROID_LOGIN_SHELL"))
    }

    @Test fun `managed rc preserves legacy custom files and sources user configuration last`() {
        val home = temporary.newFolder("custom")
        val legacy = File(home, ".droid-shrc").apply { writeText("export LEGACY=yes\n") }
        val custom = File(home, ".termrc").apply { writeText("export USER_SETTING=yes\nPS1=custom\n") }
        val rc = prepareNativeShellRc(home)
        prepareNativeShellRc(home)
        assertEquals("export LEGACY=yes\n", legacy.readText())
        assertEquals("export USER_SETTING=yes\nPS1=custom\n", custom.readText())
        assertFalse(rc.toPath().startsWith(home.toPath()))
        val process = ProcessBuilder("/bin/sh", "-c", ". \"\$1\"; printf '%s' \"\$LEGACY/\$USER_SETTING/\$PS1\"", "test", rc.path)
            .apply { environment()["HOME"] = home.path; environment()["TERM"] = "dumb" }.start()
        assertEquals("yes/yes/custom", process.inputStream.bufferedReader().readText())
        assertEquals(0, process.waitFor())
    }
}
