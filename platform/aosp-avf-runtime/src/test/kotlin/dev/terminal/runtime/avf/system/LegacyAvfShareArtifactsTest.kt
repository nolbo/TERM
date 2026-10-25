package dev.terminal.runtime.avf.system

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyAvfShareArtifactsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun cleanupRemovesOnlyArtifactsAndPreservesFolderData() {
        val files = temporary.newFolder().canonicalFile
        val data = File(files, "shared-folders/project/data").apply { parentFile.mkdirs(); writeText("keep") }
        val root = File(files, ".virtiofs/v-old").apply { mkdirs() }
        File(root, "s0").writeText("")
        File(root, "index").mkdir()
        File(root, "index/mounts").writeText("old index")
        LegacyAvfShareArtifacts.cleanup(files, "v-old")
        assertFalse(root.exists())
        assertEquals("keep", data.readText())
    }

    @Test fun cleanupRejectsSymlinkRootsWithoutTouchingTheirTargets() {
        val files = temporary.newFolder().canonicalFile
        val outside = temporary.newFolder().canonicalFile
        File(outside, "s0").writeText("keep")
        File(files, ".virtiofs").mkdir()
        Files.createSymbolicLink(File(files, ".virtiofs/v-old").toPath(), outside.toPath())
        assertTrue(runCatching { LegacyAvfShareArtifacts.cleanup(files, "v-old") }.isFailure)
        assertEquals("keep", File(outside, "s0").readText())
    }
}
