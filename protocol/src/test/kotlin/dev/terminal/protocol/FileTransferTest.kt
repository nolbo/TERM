package dev.terminal.protocol

import java.io.*
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class FileTransferTest {
    @Test fun streamingCopyHashesAllBytesAndChecksCancellation() {
        val bytes = ByteArray(200_000) { it.toByte() }; val output = ByteArrayOutputStream()
        var checks = 0
        val digest = FileTransfer.copy(bytes.inputStream(), output, bytes.size.toLong(), { checks++ })
        assertArrayEquals(bytes, output.toByteArray()); assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes), digest)
        assertTrue(checks >= 4)
        assertTrue(runCatching { FileTransfer.copy(bytes.inputStream(), ByteArrayOutputStream(), bytes.size.toLong(), { error("cancelled") }) }.isFailure)
    }
    @Test fun oversizedTruncatedAndCorruptDataAreRejected() {
        assertTrue(runCatching { FileTransfer.copy(byteArrayOf().inputStream(), ByteArrayOutputStream(), FileTransfer.MAX_BYTES + 1) }.isFailure)
        assertTrue(runCatching { FileTransfer.copy(byteArrayOf(1).inputStream(), ByteArrayOutputStream(), 2) }.isFailure)
        assertTrue(runCatching { FileTransfer.verify(ByteArray(32), ByteArray(32) { 1 }) }.isFailure)
    }
    @Test fun requestUsesFixedProtocolAndRejectsHostPaths() {
        val out = ByteArrayOutputStream()
        FileTransfer.request(DataOutputStream(out), FileTransfer.TO_VM, "Code/file.txt")
        DataInputStream(out.toByteArray().inputStream()).use { input ->
            assertEquals(FileTransfer.MAGIC, input.readInt()); assertEquals(1, input.readUnsignedByte()); assertEquals(13, input.readUnsignedShort())
        }
        for (path in listOf("/data/private", "../secret", "a//b", "a/./b", List(66) { "a" }.joinToString("/"), "a\u0000b"))
            assertTrue(path, runCatching { FileTransfer.request(DataOutputStream(ByteArrayOutputStream()), 1, path) }.isFailure)
    }
}
