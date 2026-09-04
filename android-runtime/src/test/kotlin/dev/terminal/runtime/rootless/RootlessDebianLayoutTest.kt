package dev.terminal.runtime.rootless

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootlessDebianLayoutTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun requiresShellAndAptBeforeMarkingRootfsUsable() {
        val layout = RootlessDebianLayout(File(temporary.root, "rootless/debian"))
        layout.createHostDirectories()
        assertTrue(layout.validateInstalledRootfs().isFailure)

        File(layout.rootfs, "bin").mkdirs()
        File(layout.rootfs, "usr/bin").mkdirs()
        File(layout.rootfs, "bin/sh").writeText("placeholder")
        assertTrue(layout.validateInstalledRootfs().isFailure)

        File(layout.rootfs, "usr/bin/apt").writeText("placeholder")
        assertTrue(layout.validateInstalledRootfs().isSuccess)
    }

    @Test fun workspaceLivesOutsideTheDebianRootFilesystem() {
        val layout = RootlessDebianLayout(File(temporary.root, "rootless/debian"))
        assertFalseInside(layout.workspace, layout.rootfs)
    }

    private fun assertFalseInside(child: File, parent: File) {
        assertTrue(!child.canonicalPath.startsWith(parent.canonicalPath + File.separator))
    }
}
