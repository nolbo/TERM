package dev.terminal.runtime.rootless

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootlessNativeMigrationDeviceTest {
    @Test fun nativeLinkErrorReasonSurvivesMigrationMessageOnAndroid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.terminal.runtime.test")
        val temporary = Files.createTempDirectory(context.filesDir.toPath(), "link-diagnostic-")
        try {
            val layout = RootlessDebianLayout(temporary.resolve("rootless/debian").toFile())
            layout.createHostDirectories()
            layout.rootfs.mkdirs()
            val sentinel = File(layout.rootfs, "keep").apply { writeText("unchanged") }
            // A directory hardlink must fail; obtain a real Android NIO error without
            // requiring the test device to deny regular-file hardlinks.
            val original = runCatching {
                Files.createLink(temporary.resolve("forbidden-link"), layout.rootfs.toPath())
            }.exceptionOrNull()!!
            val failure = runCatching {
                RootlessNativeMigration(layout, createLink = { _, _ -> throw original }).migrate()
            }.exceptionOrNull()!!
            assertTrue(failure.message!!.contains("native-migration/hardlink-probe"))
            assertTrue(failure.message!!.contains(original.javaClass.simpleName))
            val reason = (original as java.nio.file.FileSystemException).reason
            assertFalse(reason.isNullOrBlank())
            assertTrue(failure.message!!.contains(reason!!))
            assertEquals("unchanged", sentinel.readText())
            assertFalse(RootlessNativeMigration.pending(layout.root.parentFile.toPath()))
            assertFalse(RootlessStorageFormat.usesNativeLinks(layout.rootfs.toPath()))
        } finally { deleteRootlessTree(temporary) }
    }
    private class InterruptedMigration : Error()
    @Test fun restoresInterruptedDirectorySwapsOnAndroidStorage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.terminal.runtime.test")
        for (stop in listOf("directory-access", "staged", "backed-up-0", "published-0", "backed-up-1", "published-1", "backed-up-2", "published-2", "committed")) {
            val temporary = Files.createTempDirectory(context.filesDir.toPath(), "native-migration-")
            try {
                val layout = RootlessDebianLayout(temporary.resolve("rootless/debian").toFile())
                layout.createHostDirectories()
                layout.rootfs.mkdirs()
                val locked = Files.createDirectories(layout.rootfs.toPath().resolve("locked/nested"))
                Files.write(locked.resolve("kept"), "private".toByteArray())
                Files.setPosixFilePermissions(locked, emptySet())
                Files.setPosixFilePermissions(locked.parent, emptySet())
                val sticky = Files.createDirectory(layout.rootfs.toPath().resolve("sticky"))
                Files.setAttribute(sticky, "unix:mode", 1023) // 01777
                val intermediate = File(layout.rootfs, ".l2s.payload0001").toPath()
                val data = Files.write(intermediate.resolveSibling(".l2s.payload0001.0003"), "keep".toByteArray())
                Files.createSymbolicLink(intermediate, data)
                val aliases = listOf(layout.rootfs, layout.home, layout.workspace).map { File(it, "payload").toPath() }
                aliases.forEach { Files.createSymbolicLink(it, intermediate) }
                try {
                    RootlessNativeMigration(layout, { if (it == stop) throw InterruptedMigration() }).migrate()
                    fail("missing interruption")
                } catch (_: InterruptedMigration) { }
                assertTrue(RootlessNativeMigration.pending(layout.root.parentFile.toPath()))
                RootlessNativeMigration(layout).recover()
                assertFalse(RootlessNativeMigration.pending(layout.root.parentFile.toPath()))
                assertEquals(emptySet<java.nio.file.attribute.PosixFilePermission>(), Files.getPosixFilePermissions(locked.parent))
                assertEquals(stop == "committed", RootlessStorageFormat.usesNativeLinks(layout.rootfs.toPath()))
                aliases.forEach {
                    assertEquals(stop != "committed", Files.isSymbolicLink(it))
                    assertEquals("keep", String(Files.readAllBytes(it)))
                }
                if (stop != "committed") RootlessNativeMigration(layout).migrate()
                assertTrue(aliases.all { Files.isSameFile(it, aliases.first()) })
                assertTrue(aliases.none { Files.isSymbolicLink(it) })
                assertEquals(1023, (Files.getAttribute(sticky, "unix:mode") as Int) and 4095)
                assertEquals(emptySet<java.nio.file.attribute.PosixFilePermission>(), Files.getPosixFilePermissions(locked.parent))
                Files.setPosixFilePermissions(locked.parent, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
                assertEquals(emptySet<java.nio.file.attribute.PosixFilePermission>(), Files.getPosixFilePermissions(locked))
                Files.setPosixFilePermissions(locked, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
                assertEquals("private", String(Files.readAllBytes(locked.resolve("kept"))))
            } finally { deleteRootlessTree(temporary) }
        }
    }
}
