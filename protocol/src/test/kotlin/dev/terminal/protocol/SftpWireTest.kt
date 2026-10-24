package dev.terminal.protocol

import dev.terminal.protocol.SftpWire.bytes
import java.io.*
import org.junit.Assert.*
import org.junit.Test

class SftpWireTest {
    private class RecordingOutput : OutputStream() {
        val packets = mutableListOf<ByteArray>()
        var flushes = 0
        override fun write(value: Int) { packets += byteArrayOf(value.toByte()) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { packets += bytes.copyOfRange(offset, offset + length) }
        override fun flush() { flushes++ }
    }

    @Test fun eachPacketIncludesItsLengthInOneWriteAndFlushesImmediately() {
        val target = RecordingOutput()
        val output = DataOutputStream(target)
        SftpWire.send(output) { writeByte(1); writeInt(3) }
        assertEquals(1, target.packets.size)
        assertEquals(1, target.flushes)
        assertArrayEquals(byteArrayOf(0, 0, 0, 5, 1, 0, 0, 0, 3), target.packets.single())
        val data = ByteArray(SftpWire.MAX_DATA) { it.toByte() }
        SftpWire.send(output) { writeByte(103); writeInt(7); bytes(data) }
        assertEquals(2, target.packets.size)
        assertEquals(2, target.flushes)
        val packet = requireNotNull(SftpWire.read(DataInputStream(target.packets.last().inputStream())))
        assertEquals(103, packet.input.readUnsignedByte()); assertEquals(7, packet.input.readInt())
        assertArrayEquals(data, packet.bytes(SftpWire.MAX_DATA)); packet.end()
    }

    @Test fun maximumBodyStillFitsTheSameWireLimit() {
        val target = RecordingOutput()
        val data = ByteArray(SftpWire.MAX_PACKET - 5) { it.toByte() }
        SftpWire.send(DataOutputStream(target)) { writeByte(104); writeInt(9); write(data) }
        assertEquals(1, target.packets.size)
        val wire = target.packets.single()
        assertEquals(SftpWire.MAX_PACKET + 4, wire.size)
        assertEquals(SftpWire.MAX_PACKET, DataInputStream(wire.inputStream()).readInt())
        val packet = requireNotNull(SftpWire.read(DataInputStream(wire.inputStream())))
        assertEquals(104, packet.input.readUnsignedByte()); assertEquals(9, packet.input.readInt())
        val actual = ByteArray(data.size).also(packet.input::readFully)
        assertArrayEquals(data, actual); packet.end()
    }

    @Test fun rejectedOrFailedBodiesNeverSendAPartialPacket() {
        val target = RecordingOutput()
        val output = DataOutputStream(target)
        for (length in listOf(4, SftpWire.MAX_PACKET + 1)) {
            assertThrows(IllegalArgumentException::class.java) { SftpWire.send(output) { write(ByteArray(length)) } }
        }
        assertThrows(IOException::class.java) { SftpWire.send(output) { writeByte(1); throw IOException("body failed") } }
        assertTrue(target.packets.isEmpty()); assertEquals(0, target.flushes)
    }

    @Test fun failedSocketWritePropagatesWithoutRetryOrFlush() {
        var writes = 0
        var flushes = 0
        val output = DataOutputStream(object : OutputStream() {
            override fun write(value: Int) { error("Packet must use one bulk write") }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { writes++; throw IOException("socket failed") }
            override fun flush() { flushes++ }
        })
        assertThrows(IOException::class.java) { SftpWire.send(output) { writeByte(1); writeInt(3) } }
        assertEquals(1, writes); assertEquals(0, flushes)
    }

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
