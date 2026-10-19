package dev.terminal.protocol

import dev.terminal.protocol.SftpWire.bytes
import java.io.*
import org.junit.Assert.*
import org.junit.Test

class SftpWireTest {
    @Test fun packetLengthsAndTruncationAreBoundedBeforeAllocation() {
        for (size in listOf(-1, 0, 4, SftpWire.MAX_PACKET + 1, Int.MAX_VALUE)) {
            val wire = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(size) }.toByteArray()
            assertTrue(runCatching { SftpWire.read(DataInputStream(wire.inputStream())) }.isFailure)
        }
        assertTrue(runCatching { SftpWire.read(DataInputStream(byteArrayOf(0, 0, 0, 5, 1).inputStream())) }.isFailure)
        assertNull(SftpWire.read(DataInputStream(byteArrayOf().inputStream())))
    }
    @Test fun pathsAreAnchoredWithinOneExportAndNeverTraverseParents() {
        assertEquals(listOf("project", "file"), SftpWire.components("/project/./file"))
        assertTrue(SftpWire.components("/.").isEmpty())
        for (path in listOf("..", "/../files", "a/../b", "a\u0000b", "a/".repeat(65), "a".repeat(256)))
            assertTrue(path, runCatching { SftpWire.components(path) }.isFailure)
    }
    @Test fun invalidUtf8AndTrailingDataCannotBecomeFilesystemOperations() {
        val raw = ByteArrayOutputStream().also { DataOutputStream(it).bytes(byteArrayOf(0xc3.toByte(), 0x28)) }.toByteArray()
        assertTrue(runCatching { SftpWire.Packet(raw).text() }.isFailure)
        assertTrue(runCatching { SftpWire.Packet(byteArrayOf(1)).end() }.isFailure)
    }
}
