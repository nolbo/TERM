package dev.terminal.protocol

import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** SFTP v3 framing for the private, host-initiated AVF share stream; no SSH or TCP listener. */
object SftpWire {
    const val MAX_PACKET = 256 * 1024
    const val MAX_DATA = 128 * 1024
    class Packet(bytes: ByteArray) {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        fun bytes(max: Int = 4096): ByteArray {
            val size = input.readInt()
            require(size in 0..max && size <= input.available()) { "Invalid SFTP string" }
            return ByteArray(size).also(input::readFully)
        }
        fun text(max: Int = 4096): String = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes(max))).toString().also { require('\u0000' !in it) }
        fun end() = require(input.available() == 0) { "Trailing SFTP data" }
    }
    fun read(input: DataInputStream): Packet? {
        val first = input.read()
        if (first < 0) return null
        val size = (first shl 24) or (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
        require(size in 5..MAX_PACKET) { "Invalid SFTP packet size" }
        return Packet(ByteArray(size).also(input::readFully))
    }
    fun send(output: DataOutputStream, body: DataOutputStream.() -> Unit) {
        val raw = PacketBuffer()
        DataOutputStream(raw).use(body)
        raw.send(output)
    }
    private class PacketBuffer : ByteArrayOutputStream() {
        init { count = Int.SIZE_BYTES }
        fun send(output: DataOutputStream) {
            val size = count - Int.SIZE_BYTES
            require(size in 5..MAX_PACKET)
            buf[0] = (size ushr 24).toByte(); buf[1] = (size ushr 16).toByte()
            buf[2] = (size ushr 8).toByte(); buf[3] = size.toByte()
            // One application write, with no payload clone or batching delay.
            writeTo(output)
            output.flush()
        }
    }
    fun DataOutputStream.bytes(value: ByteArray) { writeInt(value.size); write(value) }
    fun DataOutputStream.text(value: String) = bytes(value.toByteArray(Charsets.UTF_8))

    /** SFTP absolute paths are relative to this export, never Android paths. */
    fun components(path: String): List<String> {
        require(path.toByteArray().size <= 4096 && '\u0000' !in path)
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        require(parts.size <= 64 && parts.all { it != ".." && it.toByteArray().size <= 255 }) { "Path escapes share" }
        return parts
    }
}
