package dev.terminal.runtime.storage

import android.os.ParcelFileDescriptor
import android.system.Os
import dev.terminal.protocol.FileTransfer
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Shared bounded, no-follow copy path for imports and Android 17 storage conversion. */
internal object SharedFolderTree {
    fun copyVerified(source: File, destination: File) {
        val expected = ScopedDirectory.open(source).use { origin ->
            ScopedDirectory.open(destination).use { copyTree(origin, it, {}, {}) }
        }
        val original = ScopedDirectory.open(source).use { copyTree(it, null, {}, {}) }
        val copied = ScopedDirectory.open(destination).use { copyTree(it, null, {}, {}) }
        FileTransfer.verify(expected, original)
        FileTransfer.verify(expected, copied)
    }

    /** Reject links/special files, preserve every source byte, and verify the full tree before activation. */
    fun copyTree(source: ScopedDirectory, destination: ScopedDirectory?, check: () -> Unit,
        progress: (Long) -> Unit, zip: ZipOutputStream? = null): ByteArray {
        val tree = MessageDigest.getInstance("SHA-256")
        var total = 0L
        var nodes = 0
        fun walk(origin: ScopedDirectory, target: ScopedDirectory?, prefix: String, depth: Int) {
            require(depth <= 64) { "Legacy workspace directory depth exceeds limit" }
            val names = origin.names()
            for (name in names) {
                check(); require(++nodes <= 100_000 && name.none(Char::isISOControl)) { "Legacy workspace exceeds safe import limits" }
                val path = prefix + name
                val directory = runCatching { origin.child(name) }.getOrNull()
                val encoded = path.toByteArray()
                tree.update(java.nio.ByteBuffer.allocate(4).putInt(encoded.size).array()); tree.update(encoded)
                if (directory != null) {
                    tree.update(1)
                    zip?.putNextEntry(ZipEntry("data/$path/")); zip?.closeEntry()
                    directory.use { child ->
                        val next = target?.child(name, true)
                        try { walk(child, next, "$path/", depth + 1); next?.sync() } finally { next?.close() }
                    }
                } else {
                    tree.update(2)
                    val descriptor = origin.file(name)
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                        val size = Os.fstat(descriptor.fileDescriptor).st_size
                        tree.update(java.nio.ByteBuffer.allocate(8).putLong(size).array())
                        require(size >= 0 && size <= MAX_LEGACY_BYTES - total) { "Legacy workspace exceeds 2 GiB import limit" }
                        zip?.putNextEntry(ZipEntry("data/$path"))
                        val output = target?.create(name)
                        try {
                            val buffer = ByteArray(64 * 1024)
                            var remaining = size
                            while (remaining > 0) {
                                check()
                                val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                                require(n > 0) { "Source changed during copy" }
                                tree.update(buffer, 0, n); output?.write(buffer, 0, n); zip?.write(buffer, 0, n)
                                remaining -= n; total += n; progress(total)
                            }
                            require(input.read() == -1) { "Source changed during copy" }
                            (output as? FileOutputStream)?.let { it.flush(); it.fd.sync() }
                        } finally { output?.close() }
                    }
                    tree.update(0); zip?.closeEntry()
                }
            }
            require(origin.names() == names) { "Source changed during copy" }
        }
        walk(source, destination, "", 0)
        destination?.sync()
        return tree.digest()
    }
    private const val MAX_LEGACY_BYTES = 2L * 1024 * 1024 * 1024
}
