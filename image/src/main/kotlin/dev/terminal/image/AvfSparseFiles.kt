package dev.terminal.image

import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path

/** Writes raw image bytes without allocating blocks for zero-filled regions. */
object AvfSparseFiles {
    private const val BUFFER_BYTES = 64 * 1024
    private const val BLOCK_BYTES = 4096

    /** The destination must be new; the caller owns the input and staging cleanup. */
    fun copy(input: InputStream, destination: Path, maxBytes: Long = Long.MAX_VALUE,
        checkCancellation: () -> Unit = {}): Long {
        require(maxBytes >= 0) { "Invalid image size limit" }
        checkCancellation()
        Files.createFile(destination)
        try {
            RandomAccessFile(destination.toFile(), "rw").use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                var total = 0L
                while (true) {
                    val start = total
                    var count = 0
                    while (count < buffer.size) {
                        checkCancellation()
                        var read = input.read(buffer, count, buffer.size - count)
                        if (read < 0) break
                        if (read == 0) {
                            val value = input.read()
                            if (value < 0) break
                            buffer[count] = value.toByte()
                            read = 1
                        }
                        require(read.toLong() <= maxBytes - total) { "Expanded image exceeds approved size limit" }
                        total += read
                        count += read
                    }
                    var runStart = -1
                    var offset = 0
                    while (offset < count) {
                        checkCancellation()
                        val end = minOf(offset + BLOCK_BYTES, count)
                        val zero = (offset until end).all { buffer[it] == 0.toByte() }
                        if (!zero && runStart < 0) runStart = offset
                        if (zero && runStart >= 0) {
                            output.seek(start + runStart)
                            output.write(buffer, runStart, offset - runStart)
                            runStart = -1
                        }
                        offset = end
                    }
                    if (runStart >= 0) {
                        output.seek(start + runStart)
                        output.write(buffer, runStart, count - runStart)
                    }
                    if (count < buffer.size) break
                }
                checkCancellation()
                // Seeking alone does not extend the file, including all-zero images.
                output.setLength(total)
                output.fd.sync()
                return total
            }
        } catch (error: Exception) {
            try { Files.deleteIfExists(destination) } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
    }
}
