package dev.terminal.image

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.*
import org.junit.Test

class AvfImageTransitionsTest {
    private class Crash : Error()
    private fun image(path: Path, version: String): Path {
        Files.createDirectories(path)
        val artifacts = mapOf("kernel" to "vmlinuz", "initrd" to "initrd.img", "system" to "system.img", "home" to "home.img", "config" to "config.img")
        artifacts.forEach { (_, file) -> Files.writeString(path.resolve(file), "$file:$version") }
        Files.writeString(path.resolve("manifest.properties"), "formatVersion=1\ndistribution=debian\narchitecture=arm64\n" +
            artifacts.entries.joinToString("\n") { (key, file) -> "$key.sha256=${AvfImageVerifier.sha256(path.resolve(file))}" })
        AvfImageVerifier.recordInstallation(path)
        return path
    }

    @Test fun rollbackRestoresMatchingBaseAndStateAndRetainsLaterWrites() {
        val temp = Files.createTempDirectory("avf-transitions")
        try {
            val root = image(temp.resolve("installed"), "old")
            Files.writeString(root.resolve("home.img"), "old packages and home")
            AvfPackageInitialization.markComplete(root, 123)
            val candidate = image(temp.resolve("candidate"), "new")
            val outside = Files.writeString(temp.resolve("unrelated"), "keep")
            Files.createSymbolicLink(root.resolve(".avf-previous.tmp"), outside)
            Files.writeString(candidate.resolve("home.img"), "migrated home")
            AvfImageTransitions.activate(root, candidate, AvfDistribution.DEBIAN)
            assertEquals("keep", Files.readString(outside))
            assertEquals("system.img:new", Files.readString(root.resolve("system.img")))
            assertNull(AvfPackageInitialization.completedAt(root))
            Files.writeString(root.resolve("home.img"), "new writes")
            AvfImageTransitions.rollback(root, AvfDistribution.DEBIAN)
            assertEquals("system.img:old", Files.readString(root.resolve("system.img")))
            assertEquals("old packages and home", Files.readString(root.resolve("home.img")))
            assertEquals(123L, AvfPackageInitialization.completedAt(root))
            AvfImageTransitions.rollback(root, AvfDistribution.DEBIAN)
            assertEquals("new writes", Files.readString(root.resolve("home.img")))
            AvfImageTransitions.discardHistory(root)
            assertNull(AvfImageTransitions.previous(root))
            assertTrue(AvfImageVerifier.verifyInstalled(root) is ImageVerification.Ready)
        } finally { temp.toFile().deleteRecursively() }
    }

    @Test fun everyInterruptedMoveRecoversTheEntireOldGeneration() {
        val points = listOf("journal") + AvfImageTransitions.payload.flatMap { listOf("backup:$it", "activate:$it") }
        points.forEach { point ->
            val temp = Files.createTempDirectory("avf-crash")
            try {
                val root = image(temp.resolve("installed"), "old")
                val candidate = image(temp.resolve("candidate"), "new")
                AvfPackageInitialization.markComplete(root, 123)
                AvfGuestComponents.record(candidate, candidate)
                assertThrows(Crash::class.java) {
                    AvfImageTransitions.activate(root, candidate, AvfDistribution.DEBIAN, checkpoint = { if (it == point) throw Crash() })
                }
                assertTrue(AvfImageTransitions.interrupted(root))
                assertTrue(AvfImageVerifier.verifyInstalled(root) is ImageVerification.Invalid)
                AvfImageTransitions.recover(root)
                assertTrue(AvfImageVerifier.verifyInstalled(root) is ImageVerification.Ready)
                assertEquals("home.img:old", Files.readString(root.resolve("home.img")))
                assertEquals("system.img:old", Files.readString(root.resolve("system.img")))
                assertEquals(123L, AvfPackageInitialization.completedAt(root))
                assertFalse(Files.exists(root.resolve(AvfGuestComponents.RECEIPT)))
            } finally { temp.toFile().deleteRecursively() }
        }
    }

    @Test fun corruptCandidateAndRejectedGenerationCannotMoveExistingDisks() {
        val temp = Files.createTempDirectory("avf-reject")
        try {
            val root = image(temp.resolve("installed"), "old")
            val candidate = image(temp.resolve("candidate"), "new")
            assertThrows(IllegalStateException::class.java) {
                AvfImageTransitions.activate(root, candidate, AvfDistribution.DEBIAN, activate = { error("Deleted generation") })
            }
            Files.writeString(candidate.resolve("system.img"), "tampered")
            assertThrows(IllegalArgumentException::class.java) {
                AvfImageTransitions.activate(root, candidate, AvfDistribution.DEBIAN)
            }
            assertEquals("home.img:old", Files.readString(root.resolve("home.img")))
            assertFalse(AvfImageTransitions.interrupted(root))
        } finally { temp.toFile().deleteRecursively() }
    }
}
