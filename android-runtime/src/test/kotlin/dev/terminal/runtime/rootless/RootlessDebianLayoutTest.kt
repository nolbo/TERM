package dev.terminal.runtime.rootless

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootlessDebianLayoutTest {
    @Test fun independentEnvironmentsUseIndependentDownloadCachesAndLegacyKeepsItsCache() {
        val cache = File("/tmp/cache")
        val first = RootlessDebianLayout(File("/tmp/profile-storage/ROOTLESS/${java.util.UUID.randomUUID()}"))
        val second = RootlessDebianLayout(File("/tmp/profile-storage/ROOTLESS/${java.util.UUID.randomUUID()}"))
        assertNotEquals(first.downloadDirectory(cache), second.downloadDirectory(cache))
        assertEquals(File(cache, "rootless-downloads/environments/debian/${first.root.name}"), first.downloadDirectory(cache))
        val legacy = RootlessDebianLayout(File("/tmp/rootless/debian")).downloadDirectory(cache)
        assertEquals(File(cache, "rootless-downloads/debian"), legacy)
        assertFalse(first.downloadDirectory(cache).path.startsWith(legacy.path + File.separator))
    }
    @get:Rule val temporary = TemporaryFolder()

    @Test fun requiresShellAndAptBeforeMarkingRootfsUsable() {
        val layout = RootlessDebianLayout(File(temporary.root, "rootless/debian"))
        layout.createHostDirectories()
        assertTrue(layout.validateInstalledRootfs().isFailure)

        File(layout.rootfs, "bin").mkdirs()
        File(layout.rootfs, "usr/bin").mkdirs()
        File(layout.rootfs, "bin/sh").apply {
            writeText("placeholder")
            setExecutable(true)
        }
        assertTrue(layout.validateInstalledRootfs().isFailure)

        File(layout.rootfs, "usr/bin/apt").apply {
            writeText("placeholder")
            setExecutable(true)
        }
        assertTrue(layout.validateInstalledRootfs().isSuccess)
    }

    @Test fun rejectsRootfsWhoseShellLostItsExecutableMode() {
        val layout = RootlessDebianLayout(File(temporary.root, "rootless/debian"))
        File(layout.rootfs, "bin").mkdirs()
        File(layout.rootfs, "usr/bin").mkdirs()
        File(layout.rootfs, "bin/sh").writeText("not executable")
        File(layout.rootfs, "usr/bin/apt").apply { writeText("apt"); setExecutable(true) }

        assertTrue(layout.validateInstalledRootfs().isFailure)
    }

    @Test fun workspaceLivesOutsideTheDebianRootFilesystem() {
        val layout = RootlessDebianLayout(File(temporary.root, "rootless/debian"))
        assertFalseInside(layout.workspace, layout.rootfs)
    }

    private fun assertFalseInside(child: File, parent: File) {
        assertTrue(!child.canonicalPath.startsWith(parent.canonicalPath + File.separator))
    }
}
