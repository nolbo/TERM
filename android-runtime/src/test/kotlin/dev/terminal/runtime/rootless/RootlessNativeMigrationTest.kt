package dev.terminal.runtime.rootless

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootlessNativeMigrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture(name: String): RootlessDebianLayout {
        val layout = RootlessDebianLayout(File(temporary.root, "$name/rootless/debian"))
        layout.createHostDirectories()
        layout.rootfs.mkdirs()
        val intermediate = layout.rootfs.toPath().resolve(".l2s.data0001")
        val data = Files.write(intermediate.resolveSibling(".l2s.data0001.0003"), "payload".toByteArray())
        Files.createSymbolicLink(intermediate, data)
        for (root in listOf(layout.rootfs, layout.home, layout.workspace)) {
            Files.createSymbolicLink(root.toPath().resolve("data"), intermediate)
            File(root, "ordinary").writeText("keep")
            Files.createSymbolicLink(root.toPath().resolve("ordinary-link"), java.nio.file.Path.of("ordinary"))
        }
        return layout
    }
    private fun preserved(layout: RootlessDebianLayout, native: Boolean) {
        assertEquals(native, RootlessStorageFormat.usesNativeLinks(layout.rootfs.toPath()))
        for (root in listOf(layout.rootfs, layout.home, layout.workspace)) {
            val data = File(root, "data").toPath()
            assertEquals(!native, Files.isSymbolicLink(data))
            assertEquals("payload", Files.readString(data))
            assertEquals("keep", File(root, "ordinary").readText())
            assertEquals(java.nio.file.Path.of("ordinary"), Files.readSymbolicLink(File(root, "ordinary-link").toPath()))
        }
        assertFalse(RootlessNativeMigration.pending(layout.root.parentFile.toPath()))
    }
    @Test fun convertsAcrossRootHomeAndWorkspaceWithoutCopyingOrBreakingAliases() {
        val layout = fixture("success")
        RootlessNativeMigration(layout).migrate()
        preserved(layout, true)
        assertTrue(Files.isSameFile(File(layout.rootfs, "data").toPath(), File(layout.home, "data").toPath()))
        assertEquals(3, Files.getAttribute(File(layout.rootfs, "data").toPath(), "unix:nlink"))
        File(layout.workspace, "data").writeText("updated")
        assertEquals("updated", File(layout.rootfs, "data").readText())
        RootlessNativeMigration(layout).migrate() // Already native is idempotent.
    }
    @Test fun storageAncestorAliasesLikeAndroidUserZeroAreSupported() {
        val original = fixture("aliased")
        val alias = temporary.root.toPath().resolve("alias")
        Files.createSymbolicLink(alias, File(temporary.root, "aliased").toPath())
        val layout = RootlessDebianLayout(alias.resolve("rootless/debian").toFile())
        RootlessNativeMigration(layout).migrate()
        preserved(original, true)
    }
    @Test fun preservesReadOnlyDirectoriesAndOrdinaryRootSymlinks() {
        val layout = fixture("permissions")
        val directory = Files.createDirectory(layout.rootfs.toPath().resolve("read-only"))
        val file = Files.write(directory.resolve("file"), byteArrayOf(4, 2))
        val directoryMode = java.nio.file.attribute.PosixFilePermissions.fromString("r-x------")
        val fileMode = java.nio.file.attribute.PosixFilePermissions.fromString("r--------")
        Files.setPosixFilePermissions(file, fileMode)
        Files.setPosixFilePermissions(directory, directoryMode)
        Files.setAttribute(directory, "unix:mode", 1864) // 03510: setgid + sticky + owner r-x + group x
        val modified = Files.getLastModifiedTime(directory)
        Files.createSymbolicLink(layout.home.toPath().resolve("guest-root"), java.nio.file.Path.of("/"))
        try {
            RootlessNativeMigration(layout).migrate()
            assertEquals(1864, (Files.getAttribute(directory, "unix:mode") as Int) and 4095)
            assertEquals(fileMode, Files.getPosixFilePermissions(file))
            assertEquals(modified, Files.getLastModifiedTime(directory))
            assertArrayEquals(byteArrayOf(4, 2), Files.readAllBytes(file))
            assertEquals(java.nio.file.Path.of("/"), Files.readSymbolicLink(layout.home.toPath().resolve("guest-root")))
        } finally {
            Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        }
    }
    @Test fun exceptionsAtEveryPublicationBoundaryRollback() {
        for (point in listOf("staged", "backed-up-0", "published-0", "backed-up-1", "published-1", "backed-up-2", "published-2")) {
            val layout = fixture(point)
            assertTrue(runCatching { RootlessNativeMigration(layout, { if (it == point) error("injected") }).migrate() }.isFailure)
            preserved(layout, false)
        }
    }
    @Test fun deniedProbeIncludesOriginalReasonAndPreservesInstallation() {
        val layout = fixture("denied-diagnostic")
        val original = java.nio.file.FileSystemException("/private/probe-link", "/private/probe", "Operation not permitted")
        original.initCause(java.io.IOException("link failed: EPERM (Operation not permitted)"))
        val failure = runCatching {
            RootlessNativeMigration(layout, createLink = { _, _ -> throw original }).migrate()
        }.exceptionOrNull()!!
        assertSame(original, failure.cause)
        assertTrue(failure.message!!.contains("native-migration/hardlink-probe"))
        assertTrue(failure.message!!.contains("FileSystemException: Operation not permitted"))
        assertTrue(failure.message!!.contains("EPERM"))
        assertFalse(failure.message!!.contains("/private/"))
        preserved(layout, false)
    }
    @Test fun missingReasonAndCyclicCausesRemainReadableAndBounded() {
        val first = java.nio.file.FileSystemException("/private/probe")
        val second = java.io.IOException("x".repeat(2000))
        first.initCause(second)
        second.initCause(first)
        val details = describeLinkFailure(first)
        assertTrue(details.contains("상세 이유를 제공하지 않았습니다"))
        assertFalse(details.contains("/private/"))
        assertTrue(details.length < 800)
    }
    private class Crash : Error()
    @Test fun processDeathRecoveryRestoresOldTreesOrFinishesCommittedCleanup() {
        for (point in listOf("staged", "backed-up-0", "published-0", "backed-up-1", "published-1", "backed-up-2", "published-2", "committed")) {
            val layout = fixture("crash-$point")
            try { RootlessNativeMigration(layout, { if (it == point) throw Crash() }).migrate(); fail() } catch (_: Crash) {}
            assertTrue(RootlessNativeMigration.pending(layout.root.parentFile.toPath()))
            assertEquals(RootlessDistribution.DEBIAN, RootlessNativeMigration.pendingOwner(layout.root.parentFile.toPath()))
            RootlessNativeMigration(layout).recover()
            preserved(layout, point == "committed")
        }
    }
    @Test fun inaccessibleDirectoriesRecoverPermissionsAfterInterruptedTraversal() {
        val layout = fixture("locked")
        val directory = Files.createDirectories(layout.rootfs.toPath().resolve("locked/nested"))
        Files.write(directory.resolve("keep"), byteArrayOf(42))
        Files.setPosixFilePermissions(directory, emptySet())
        Files.setPosixFilePermissions(directory.parent, emptySet())
        try {
            try {
                RootlessNativeMigration(layout, { if (it == "directory-access") throw Crash() }).migrate()
                fail()
            } catch (_: Crash) { }
            RootlessNativeMigration(layout).recover()
            assertEquals(emptySet<java.nio.file.attribute.PosixFilePermission>(), Files.getPosixFilePermissions(directory.parent))
            RootlessNativeMigration(layout).migrate()
            preserved(layout, true)
            assertEquals(emptySet<java.nio.file.attribute.PosixFilePermission>(), Files.getPosixFilePermissions(directory.parent))
            Files.setPosixFilePermissions(directory.parent, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
            assertEquals(emptySet<java.nio.file.attribute.PosixFilePermission>(), Files.getPosixFilePermissions(directory))
            Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
            assertArrayEquals(byteArrayOf(42), Files.readAllBytes(directory.resolve("keep")))
        } finally {
            Files.setPosixFilePermissions(directory.parent, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
            Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        }
    }
    @Test fun deniedHardlinksAndMissingMembersLeaveOriginalUntouched() {
        val denied = fixture("denied")
        assertTrue(runCatching { RootlessNativeMigration(denied, createLink = { _, _ -> throw java.nio.file.AccessDeniedException("probe") }).migrate() }.isFailure)
        preserved(denied, false)
        val missing = fixture("missing")
        Files.delete(File(missing.home, "data").toPath())
        assertTrue(runCatching { RootlessNativeMigration(missing).migrate() }.isFailure)
        assertEquals("payload", File(missing.rootfs, "data").readText())
        assertTrue(Files.isSymbolicLink(File(missing.rootfs, "data").toPath()))
        assertFalse(RootlessNativeMigration.pending(missing.root.parentFile.toPath()))
    }
}
