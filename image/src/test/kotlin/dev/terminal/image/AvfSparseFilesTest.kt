package dev.terminal.image

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class AvfSparseFilesTest {
    @Test fun fragmentedReadsPreserveDataAndLeadingMiddleAndTrailingZeros() {
        val root = Files.createTempDirectory("avf-sparse-fragments")
        try {
            val expected = ByteArray(3 * 64 * 1024 + 731)
            for (offset in listOf(4095, 4096, 65535, 65536, 131073)) expected[offset] = (offset % 127 + 1).toByte()
            val source = object : ByteArrayInputStream(expected) {
                var reads = 0
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    if (++reads % 11 == 0) 0 else super.read(b, off, minOf(len, 1003))
            }
            val target = root.resolve("disk.img")
            assertEquals(expected.size.toLong(), AvfSparseFiles.copy(source, target, expected.size.toLong()))
            assertArrayEquals(expected, Files.readAllBytes(target))
            val digest = MessageDigest.getInstance("SHA-256").digest(expected).joinToString("") { "%02x".format(it) }
            assertEquals(digest, AvfImageVerifier.sha256(target))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun eightGiBDiskKeepsLongOffsetsAndCapacityWithSmallPhysicalAllocation() {
        val root = Files.createTempDirectory("avf-sparse-eight-gib")
        try {
            val capacity = 8L * 1024 * 1024 * 1024
            val offsets = listOf(0L, 2L * 1024 * 1024 * 1024 + 17, capacity - 8193)
            val source = object : InputStream() {
                var position = 0L
                override fun read(): Int = if (position >= capacity) -1 else
                    (if (position++ in offsets) 73 else 0)
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (position >= capacity) return -1
                    val count = minOf(len.toLong(), capacity - position).toInt()
                    java.util.Arrays.fill(b, off, off + count, 0.toByte())
                    for (marker in offsets) if (marker >= position && marker < position + count)
                        b[off + (marker - position).toInt()] = 73
                    position += count
                    return count
                }
            }
            val target = root.resolve("home.img")
            assertEquals(capacity, AvfSparseFiles.copy(source, target, capacity))
            assertEquals(capacity, Files.size(target))
            RandomAccessFile(target.toFile(), "r").use { file ->
                for (offset in offsets) { file.seek(offset); assertEquals(73, file.read()); assertEquals(0, file.read()) }
                file.seek(capacity - 1); assertEquals(0, file.read()); assertEquals(-1, file.read())
            }
            if (System.getProperty("os.name") == "Linux") {
                val stat = ProcessBuilder("stat", "-c", "%b", target.toString()).start()
                val allocated = stat.inputStream.bufferedReader().use { it.readText().trim().toLong() } * 512
                assertEquals(0, stat.waitFor())
                assertTrue("Allocated $allocated bytes for an 8 GiB disk", allocated < 128 * 1024)
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun rejectsOversizedExpandedZerosAndRemovesPartialFile() {
        val root = Files.createTempDirectory("avf-sparse-size-limit")
        try {
            val target = root.resolve("disk.img")
            assertThrows(IllegalArgumentException::class.java) {
                AvfSparseFiles.copy(ByteArrayInputStream(ByteArray(65537)), target, 65536)
            }
            assertFalse(Files.exists(target))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun cancellationAfterWritingCleansPartialFileAndAllowsRetry() {
        val root = Files.createTempDirectory("avf-sparse-cancel")
        try {
            val target = root.resolve("disk.img")
            var checks = 0
            assertThrows(CancellationException::class.java) {
                AvfSparseFiles.copy(ByteArrayInputStream(ByteArray(200000) { 1 }), target) {
                    if (++checks == 25) throw CancellationException("cancelled")
                }
            }
            assertFalse(Files.exists(target))
            AvfSparseFiles.copy(ByteArrayInputStream(byteArrayOf(1, 0, 2)), target)
            assertArrayEquals(byteArrayOf(1, 0, 2), Files.readAllBytes(target))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun doesNotOverwriteAnExistingFileOrSymlink() {
        val root = Files.createTempDirectory("avf-sparse-existing")
        try {
            val existing = Files.write(root.resolve("existing.img"), byteArrayOf(1, 2, 3))
            val link = Files.createSymbolicLink(root.resolve("link.img"), existing)
            for (target in listOf(existing, link)) {
                assertThrows(java.nio.file.FileAlreadyExistsException::class.java) {
                    AvfSparseFiles.copy(ByteArrayInputStream(byteArrayOf(4)), target)
                }
                assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(existing))
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun emptyAndAllZeroInputsRetainTheirExactLengths() {
        val root = Files.createTempDirectory("avf-sparse-zeros")
        try {
            for (length in listOf(0, 1, 4096, 65536, 65537)) {
                val target = root.resolve("disk-$length.img")
                assertEquals(length.toLong(), AvfSparseFiles.copy(ByteArrayInputStream(ByteArray(length)), target))
                assertEquals(length.toLong(), Files.size(target))
                assertArrayEquals(ByteArray(length), Files.readAllBytes(target))
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
