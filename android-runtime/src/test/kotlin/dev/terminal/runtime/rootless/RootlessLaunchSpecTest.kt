package dev.terminal.runtime.rootless

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootlessLaunchSpecTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun suppliesExecArgvLoaderBindingsAndSanitizedGuestEnvironment() {
        val layout = RootlessDebianLayout(File(temporary.root, "rootless/debian"))
        layout.createHostDirectories()
        val proot = temporary.newFile("proot")
        val loader = temporary.newFile("loader")

        val launch = buildRootlessLaunchSpec(
            layout,
            proot,
            loader,
            shell = "/bin/bash",
            cwd = "/workspace",
            env = mapOf(
                "TERM" to "xterm-256color",
                "DROID_LOGIN_SHELL" to "true",
                "SAFE_NAME" to "value",
                "PATH" to "/host/path",
                "INVALID-NAME" to "secret",
            ),
        )

        assertEquals(proot.absolutePath, launch.command)
        assertEquals(proot.absolutePath, launch.arguments.first())
        assertTrue(launch.arguments.toList().windowed(2).contains(listOf("-r", layout.rootfs.absolutePath)))
        assertTrue(launch.arguments.toList().windowed(2).contains(listOf("-w", "/workspace")))
        assertTrue(launch.arguments.contains("${layout.home.absolutePath}:/root"))
        assertTrue(launch.arguments.contains("${layout.workspace.absolutePath}:/workspace"))
        assertTrue(launch.arguments.contains("SAFE_NAME=value"))
        assertFalse(launch.arguments.contains("PATH=/host/path"))
        assertFalse(launch.arguments.any { it.startsWith("INVALID-NAME=") })
        assertEquals(listOf("/bin/bash", "-l"), launch.arguments.takeLast(2))
        assertTrue(launch.hostEnvironment.contains("PROOT_LOADER=${loader.absolutePath}"))
        assertTrue(launch.hostEnvironment.contains("PROOT_TMP_DIR=${layout.tmp.absolutePath}"))
    }
}
