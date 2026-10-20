package dev.terminal.image

import java.io.InputStream
import java.io.FileDescriptor
import java.io.EOFException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path

/** Writes raw image bytes without allocating blocks for zero-filled regions. */
object AvfSparseFiles {
    private const val BUFFER_BYTES = 64 * 1024
    private const val BLOCK_BYTES = 4096

    interface DataExtents {
        /** Null means the remaining range is a hole. Unsupported queries throw UnsupportedOperationException. */
        fun nextData(offset: Long): Long?
        fun nextHole(offset: Long): Long
    }

    data class CopyResult(val bytes: Long, val readBytes: Long, val usedExtents: Boolean)

    /** Copies into a new independent inode. The provider queries the same descriptor used for reads. */
    fun copyFile(source: Path, destination: Path, maxBytes: Long = Long.MAX_VALUE,
        checkCancellation: () -> Unit = {}, extents: ((FileDescriptor) -> DataExtents)? = null): CopyResult {
        require(Files.isRegularFile(source, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "Source is not a regular file" }
        if (extents == null) return Files.newInputStream(source).use {
            val bytes = copy(it, destination, maxBytes, checkCancellation)
            CopyResult(bytes, bytes, false)
        }
        checkCancellation()
        Files.createFile(destination)
        try {
            RandomAccessFile(source.toFile(), "r").use { input ->
                val size = input.length()
                require(size in 0..maxBytes) { "Image exceeds approved size limit" }
                val ranges = extents(input.fd)
                RandomAccessFile(destination.toFile(), "rw").use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var position = 0L
                    var readBytes = 0L
                    while (position < size) {
                        checkCancellation()
                        val data = ranges.nextData(position) ?: break
                        require(data in position until size) { "Invalid data extent" }
                        val end = ranges.nextHole(data)
                        require(end > data && end <= size) { "Invalid hole extent" }
                        input.seek(data)
                        position = data
                        while (position < end) {
                            checkCancellation()
                            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), end - position).toInt())
                            if (count < 0) throw EOFException("Image changed during copy")
                            writeBlocks(output, buffer, count, position, checkCancellation)
                            position += count
                            readBytes += count
                        }
                    }
                    checkCancellation()
                    check(input.length() == size) { "Image changed during copy" }
                    output.setLength(size)
                    output.fd.sync()
                    return CopyResult(size, readBytes, true)
                }
            }
        } catch (unsupported: UnsupportedOperationException) {
            Files.delete(destination)
            return copyFile(source, destination, maxBytes, checkCancellation)
        } catch (error: Exception) {
            try { Files.deleteIfExists(destination) } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
    }

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
                    writeBlocks(output, buffer, count, start, checkCancellation)
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

    private fun writeBlocks(output: RandomAccessFile, buffer: ByteArray, count: Int, start: Long,
        checkCancellation: () -> Unit) {
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
    }
}
