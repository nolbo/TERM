package dev.terminal.image

import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class AvfSparseFileCopyTest {
    private val capacity = 8L * 1024 * 1024
    private fun ranges() = object : AvfSparseFiles.DataExtents {
        override fun nextData(offset: Long): Long? = when {
            offset < 4096 -> offset
            offset < capacity - 4096 -> capacity - 4096
            offset < capacity -> offset
            else -> null
        }
        override fun nextHole(offset: Long) = if (offset < 4096) 4096L else capacity
    }

    @Test fun readsOnlyAllocatedExtentsAndKeepsTheWritableCopyIndependent() {
        val root = Files.createTempDirectory("avf-extents")
        try {
            val source = root.resolve("source")
            RandomAccessFile(source.toFile(), "rw").use { f ->
                f.setLength(capacity); f.write(73); f.seek(capacity - 1); f.write(91)
            }
            val target = root.resolve("target")
            val result = AvfSparseFiles.copyFile(source, target, extents = { ranges() })
            assertEquals(capacity, result.bytes)
            assertEquals(8192L, result.readBytes)
            assertTrue(result.usedExtents)
            assertEquals(AvfImageVerifier.sha256(source), AvfImageVerifier.sha256(target))
            RandomAccessFile(target.toFile(), "rw").use { it.write(12) }
            assertEquals(73, RandomAccessFile(source.toFile(), "r").use { it.read() })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun unsupportedQueriesAfterPartialCopyRestartUsingTheSparseFallback() {
        val root = Files.createTempDirectory("avf-extents-fallback")
        try {
            val source = Files.write(root.resolve("source"), ByteArray(65536) { if (it < 4096) 1 else 0 })
            val result = AvfSparseFiles.copyFile(source, root.resolve("target"), extents = { object : AvfSparseFiles.DataExtents {
                override fun nextData(offset: Long): Long? {
                    if (offset > 0) throw UnsupportedOperationException("unsupported")
                    return 0
                }
                override fun nextHole(offset: Long) = 4096L
            } })
            assertFalse(result.usedExtents)
            assertEquals(65536L, result.readBytes)
            assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(root.resolve("target")))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun allHoleFilesKeepTheirLogicalLengthWithoutReadingData() {
        val root = Files.createTempDirectory("avf-extents-empty")
        try {
            val source = root.resolve("source")
            RandomAccessFile(source.toFile(), "rw").use { it.setLength(capacity) }
            val target = root.resolve("target")
            val result = AvfSparseFiles.copyFile(source, target, extents = { object : AvfSparseFiles.DataExtents {
                override fun nextData(offset: Long): Long? = null
                override fun nextHole(offset: Long): Long = error("No data")
            } })
            assertEquals(capacity, Files.size(target))
            assertEquals(0L, result.readBytes)
            assertEquals(AvfImageVerifier.sha256(source), AvfImageVerifier.sha256(target))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun invalidExtentsAndCancellationCannotLeavePartialCopiesOrOverwriteFiles() {
        val root = Files.createTempDirectory("avf-extents-failure")
        try {
            val source = Files.write(root.resolve("source"), ByteArray(65536) { 1 })
            val target = root.resolve("target")
            for (end in listOf(0L, 65537L)) {
                assertThrows(IllegalArgumentException::class.java) {
                    AvfSparseFiles.copyFile(source, target, extents = { object : AvfSparseFiles.DataExtents {
                        override fun nextData(offset: Long) = 0L
                        override fun nextHole(offset: Long) = end
                    } })
                }
                assertFalse(Files.exists(target))
            }
            var calls = 0
            assertThrows(CancellationException::class.java) {
                AvfSparseFiles.copyFile(source, target, checkCancellation = { if (++calls > 3) throw CancellationException() },
                    extents = { object : AvfSparseFiles.DataExtents {
                        override fun nextData(offset: Long) = offset
                        override fun nextHole(offset: Long) = 65536L
                    } })
            }
            assertFalse(Files.exists(target))
            Files.writeString(target, "keep")
            assertThrows(java.nio.file.FileAlreadyExistsException::class.java) { AvfSparseFiles.copyFile(source, target) }
            assertEquals("keep", Files.readString(target))
        } finally { root.toFile().deleteRecursively() }
    }
}
