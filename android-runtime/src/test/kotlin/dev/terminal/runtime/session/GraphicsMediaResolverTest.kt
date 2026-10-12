package dev.terminal.runtime.session

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class GraphicsMediaResolverTest {
    @Test fun guestSymlinksResolveWithinSelectedRuntimeAndBindMappings() {
        val directory = Files.createTempDirectory("graphics-resolver").toFile()
        try {
            val rootfs = File(directory, "rootfs").apply { mkdirs() }
            val home = File(directory, "home").apply { mkdirs() }
            File(rootfs, "tmp").mkdirs()
            Files.createSymbolicLink(File(rootfs, "tmp/image").toPath(), java.nio.file.Path.of("/root/pixel"))
            var opened: Pair<String, String>? = null
            val resolver = GraphicsMediaResolver(
                listOf(GraphicsMediaResolver.Mapping("/", rootfs), GraphicsMediaResolver.Mapping("/root", home)),
                setOf("/tmp"), reader = { root, relative, _, _, unlink ->
                    opened = root to relative; assertFalse(unlink); byteArrayOf(1, 2, 3)
                },
            )
            assertArrayEquals(byteArrayOf(1, 2, 3), resolver.read('t', "/tmp/image", 0, 0))
            assertEquals(home.path to "pixel", opened)
        } finally { directory.deleteRecursively() }
    }

    @Test fun sensitivePathsAndSharedMemoryEscapesNeverReachReader() {
        val directory = Files.createTempDirectory("graphics-resolver").toFile()
        try {
            val shm = File(directory, "shm").apply { mkdirs() }
            Files.createSymbolicLink(File(shm, "escape").toPath(), java.nio.file.Path.of("/root/private"))
            val resolver = GraphicsMediaResolver(
                listOf(GraphicsMediaResolver.Mapping("/", directory), GraphicsMediaResolver.Mapping("/dev/shm", shm)),
                setOf("/tmp", "/dev/shm"), "/dev/shm", reader = { _, _, _, _, _ -> error("unexpected read") },
            )
            for ((medium, path) in listOf('f' to "/proc/self/fd/1", 'f' to "/sys/info", 'f' to "/dev/zero", 's' to "/escape", 's' to "/a/b")) {
                try { resolver.read(medium, path, 0, 0); fail(path) } catch (_: IOException) { }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun temporaryDeletionRequiresKnownDirectoryAndMarker() {
        val directory = Files.createTempDirectory("graphics-resolver").toFile()
        try {
            var unlink = false
            val resolver = GraphicsMediaResolver(
                listOf(GraphicsMediaResolver.Mapping("/", directory)), setOf("/tmp"),
                reader = { _, _, _, _, remove -> unlink = remove; byteArrayOf(1) },
            )
            resolver.read('t', "/tmp/tty-graphics-protocol-pixel", 0, 0); assertTrue(unlink)
            resolver.read('t', "/root/tty-graphics-protocol-pixel", 0, 0); assertFalse(unlink)
            resolver.read('t', "/tmp/pixel", 0, 0); assertFalse(unlink)
            resolver.read('f', "/tmp/tty-graphics-protocol-pixel", 0, 0); assertFalse(unlink)
        } finally { directory.deleteRecursively() }
    }
}
