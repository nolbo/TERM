package dev.terminal.runtime.image

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import dev.terminal.image.AvfSparseFiles
import java.io.FileDescriptor
import java.io.IOException
import java.nio.file.Path

/** Android's public lseek API; no native library, root, or AVF privilege is needed. */
internal object AndroidAvfSparseFiles {
    fun copy(source: Path, destination: Path, checkCancellation: () -> Unit = {}): AvfSparseFiles.CopyResult {
        val result = AvfSparseFiles.copyFile(source, destination, checkCancellation = checkCancellation,
            extents = { descriptor -> object : AvfSparseFiles.DataExtents {
                override fun nextData(offset: Long): Long? = seek(descriptor, offset, 3) // Linux SEEK_DATA
                override fun nextHole(offset: Long): Long = seek(descriptor, offset, 4) // Linux SEEK_HOLE
                    ?: throw IOException("Image changed during extent query")
            } })
        Log.i("TermAvfImage", "image.copy bytes=${result.bytes} read_bytes=${result.readBytes} extents=${result.usedExtents}")
        return result
    }

    private fun seek(descriptor: FileDescriptor, offset: Long, whence: Int): Long? = try {
        Os.lseek(descriptor, offset, whence)
    } catch (error: ErrnoException) {
        when (error.errno) {
            OsConstants.ENXIO -> null
            OsConstants.EINVAL, OsConstants.EOPNOTSUPP, OsConstants.ENOSYS ->
                throw UnsupportedOperationException("Filesystem does not report sparse extents", error)
            else -> throw IOException("Cannot query image extents", error)
        }
    }
}
