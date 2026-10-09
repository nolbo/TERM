package dev.terminal.runtime.backup

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Inflater

/** Exactly one canonical gzip member. JDK GZIPInputStream may silently discard trailing junk
 * already buffered beyond its trailer, so it cannot establish this container's integrity boundary.
 */
internal class BackupGzipInputStream(private val source: InputStream) : InputStream() {
    private val inflater: Inflater
    private val buffer = ByteArray(BackupLimits.BUFFER)
    private val crc = CRC32()
    private var inputSize = 0
    private var bytes = 0L
    private var ended = false
    init {
        val header = ByteArray(10).also { DataInputStream(source).readFully(it) }
        require(header[0] == 31.toByte() && header[1] == 139.toByte() && header[2] == 8.toByte() && header[3] == 0.toByte()) {
            "Invalid backup compression header"
        }
        inflater = Inflater(true)
    }
    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
    }
    override fun read(output: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (ended) return -1
        while (true) {
            val count = try { inflater.inflate(output, offset, length) }
                catch (failure: java.util.zip.DataFormatException) { throw IOException("Damaged backup compression", failure) }
            if (count > 0) {
                crc.update(output, offset, count); bytes += count
                return count
            }
            if (inflater.finished()) {
                var remaining = inflater.remaining
                var at = inputSize - remaining
                fun next(): Int = if (remaining > 0) { remaining--; buffer[at++].toInt() and 255 } else source.read()
                val trailer = ByteArray(8) { next().also { if (it < 0) throw EOFException("Missing gzip trailer") }.toByte() }
                val expected = ByteBuffer.wrap(trailer).order(ByteOrder.LITTLE_ENDIAN)
                require((expected.int.toLong() and 0xffffffffL) == crc.value &&
                    (expected.int.toLong() and 0xffffffffL) == (bytes and 0xffffffffL)) { "Backup compression checksum mismatch" }
                require(next() == -1) { "Trailing compressed backup data" }
                ended = true
                return -1
            }
            require(!inflater.needsDictionary()) { "Unexpected backup compression dictionary" }
            if (inflater.needsInput()) {
                inputSize = source.read(buffer)
                if (inputSize < 0) throw EOFException("Truncated backup compression")
                if (inputSize == 0) continue
                inflater.setInput(buffer, 0, inputSize)
            } else throw IOException("Invalid backup compression state")
        }
    }
    override fun close() { inflater.end(); buffer.fill(0) }
}
