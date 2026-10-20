package dev.terminal.runtime.image

import android.os.Bundle
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.image.*
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.session.StorageRegistry
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real Android allocation and activation without requiring AVF hardware. */
@RunWith(AndroidJUnit4::class)
class AvfSparseImageDeviceTest {
    @Test fun extentCopyUsesRealAndroidLseekAndNeverSharesWritableState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.filesDir.toPath(), "avf-extents-device-")
        try {
            val source = root.resolve("source.img")
            val target = root.resolve("target.img")
            val capacity = 64L * 1024 * 1024
            RandomAccessFile(source.toFile(), "rw").use { f ->
                f.setLength(capacity); f.write(73); f.seek(capacity - 1); f.write(91)
            }
            val result = AndroidAvfSparseFiles.copy(source, target)
            assertEquals(capacity, result.bytes)
            assertEquals(AvfImageVerifier.sha256(source), AvfImageVerifier.sha256(target))
            assertNotEquals(Os.stat(source.toString()).st_ino, Os.stat(target.toString()).st_ino)
            assertTrue(Os.stat(target.toString()).st_blocks * 512 < 128 * 1024)
            if (result.usedExtents) assertTrue("Read ${result.readBytes} of $capacity bytes", result.readBytes < capacity)
            RandomAccessFile(target.toFile(), "rw").use { it.write(12) }
            assertEquals(73, RandomAccessFile(source.toFile(), "r").use { it.read() })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun preservesRawBytesCapacityAndWritableStateWhileReportingAllocatedSpace() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = Files.createTempDirectory(instrumentation.targetContext.filesDir.toPath(), "avf-sparse-test-")
        try {
            val registry = StorageRegistry(root.toFile())
            val key = StorageRegistry.Key(SessionRuntime.AVF, UUID.randomUUID().toString())
            val destination = registry.root(key).toPath()
            Files.createDirectories(destination.parent)
            val staged = Files.createDirectory(AvfImageInstaller.newStagingPath(destination))
            val homeBytes = 64L * 1024 * 1024
            val systemBytes = 32L * 1024 * 1024
            val artifacts = listOf("kernel" to "vmlinuz", "initrd" to "initrd.img", "system" to "system.img",
                "home" to "home.img", "config" to "config.img")
            for ((name, file) in artifacts) {
                val target = staged.resolve(file)
                if (name == "home" || name == "system") {
                    val capacity = if (name == "home") homeBytes else systemBytes
                    AvfSparseFiles.copy(disk(capacity), target, capacity)
                    val stat = Os.stat(target.toString())
                    assertEquals(capacity, stat.st_size)
                    assertTrue("Allocated ${stat.st_blocks * 512} bytes", stat.st_blocks * 512 < 128 * 1024)
                } else Files.write(target, name.toByteArray())
            }
            Files.write(staged.resolve("manifest.properties"),
                ("formatVersion=1\ndistribution=ubuntu\narchitecture=arm64\npackageInitializationVersion=1\n" +
                    artifacts.joinToString("\n") { (name, file) -> "$name.sha256=${AvfImageVerifier.sha256(staged.resolve(file))}" }).toByteArray())
            val originalInode = Os.stat(staged.resolve("home.img").toString()).st_ino
            // Compatibility copying also preserves holes and never shares writable state.
            val copied = destination.resolveSibling("copied")
            assertTrue(AvfImageInstaller.install(staged, copied, expectedDistribution = AvfDistribution.UBUNTU) is ImageInstallResult.Installed)
            assertNotEquals(originalInode, Os.stat(copied.resolve("home.img").toString()).st_ino)
            assertTrue(Os.stat(copied.resolve("home.img").toString()).st_blocks * 512 < 128 * 1024)
            assertTrue(AvfImageInstaller.install(staged, destination, consumeStagingDirectory = true,
                expectedDistribution = AvfDistribution.UBUNTU, initializePackageIndexes = true) is ImageInstallResult.Installed)
            assertFalse(Files.exists(staged))
            assertEquals(originalInode, Os.stat(destination.resolve("home.img").toString()).st_ino)
            assertTrue(AvfPackageInitialization.isRequired(destination))
            RandomAccessFile(destination.resolve("home.img").toFile(), "rw").use { file ->
                file.seek(homeBytes - 1); file.write(99)
            }
            RandomAccessFile(copied.resolve("home.img").toFile(), "r").use { file ->
                file.seek(homeBytes - 1); assertEquals(0, file.read())
            }
            assertTrue(AvfImageVerifier.verifyInstalled(destination, AvfDistribution.UBUNTU) is ImageVerification.Ready)
            val usage = registry.measureUsage(key)
            assertFalse(usage.incomplete)
            assertTrue(usage.bytes in 1 until 1024 * 1024)
            val actualHome = Os.stat(destination.resolve("home.img").toString()).st_blocks * 512
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "AVF sparse allocation: home logical=$homeBytes allocated=$actualHome; environment allocated=${usage.bytes}\n")
            })
        } finally { root.toFile().deleteRecursively() }
    }

    private fun disk(capacity: Long) = object : InputStream() {
        var position = 0L
        override fun read(): Int = if (position >= capacity) -1 else if (position++ == 4095L) 73 else 0
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= capacity) return -1
            val count = minOf(len.toLong(), capacity - position).toInt()
            java.util.Arrays.fill(b, off, off + count, 0.toByte())
            if (position <= 4095 && position + count > 4095) b[off + (4095 - position).toInt()] = 73
            position += count
            return count
        }
    }
}
