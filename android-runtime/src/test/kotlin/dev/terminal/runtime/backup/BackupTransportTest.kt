package dev.terminal.runtime.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Random
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test

class BackupTransportTest {
    @Test fun authenticatedFramesStreamLargeIncompressibleDataAndRejectMissingEndOrReordering() {
        val source = ByteArray(2 * 1024 * 1024).also { Random(42).nextBytes(it) }
        val bytes = ByteArrayOutputStream().also { output -> BackupTransport.Writer(output, "stream-password".toCharArray()).use {
            it.write(source); it.finish()
        } }.toByteArray()
        BackupTransport.Reader(ByteArrayInputStream(bytes), "stream-password".toCharArray()).use {
            assertArrayEquals(source, it.readBytes()); it.verifyEnd()
        }
        assertTrue(runCatching { BackupTransport.Reader(ByteArrayInputStream(bytes.copyOf(bytes.size - 20)), "stream-password".toCharArray())
            .use { it.readBytes() } }.isFailure)
        val headerSize = "TERMBACKUP\r\n".toByteArray().size + 4 + 1 + 16 + 8
        val frameSize = 4 + 64 * 1024 + 16
        val reordered = bytes.copyOf()
        bytes.copyInto(reordered, headerSize, headerSize + frameSize, headerSize + 2 * frameSize)
        bytes.copyInto(reordered, headerSize + frameSize, headerSize, headerSize + frameSize)
        assertTrue(runCatching { BackupTransport.Reader(ByteArrayInputStream(reordered), "stream-password".toCharArray()).use { it.readBytes() } }.isFailure)
    }

    @Test fun gzipRejectsCrcDamageConcatenationAndBufferedTrailingBytes() {
        val compressed = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write("payload".toByteArray()) } }.toByteArray()
        BackupGzipInputStream(compressed.inputStream()).use { assertEquals("payload", it.readBytes().toString(Charsets.UTF_8)) }
        for (bad in listOf(compressed + byteArrayOf(1), compressed + compressed,
            compressed.copyOf().also { it[it.size - 8] = (it[it.size - 8].toInt() xor 1).toByte() }, compressed.copyOf(compressed.size - 1))) {
            assertTrue(runCatching { BackupGzipInputStream(bad.inputStream()).use { it.readBytes() } }.isFailure)
        }
    }
}
