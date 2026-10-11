package dev.terminal.runtime.storage

import android.os.ParcelFileDescriptor
import android.system.Os
import dev.terminal.emulator.ScopedFileAccess
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Pinned directory FDs prevent checked parents from being swapped for links during a transfer. */
class ScopedDirectory private constructor(private val descriptor: ParcelFileDescriptor) : Closeable {
    val fd get() = descriptor.fd
    fun child(name: String, create: Boolean = false) = ScopedDirectory(ParcelFileDescriptor.adoptFd(ScopedFileAccess.child(fd, name, if (create) 3 else 1)))
    fun names(): List<String> = (File("/proc/self/fd/$fd").list() ?: error("Cannot list source directory")).toList().sorted()
    fun file(name: String, create: Boolean = false): ParcelFileDescriptor = ParcelFileDescriptor.adoptFd(ScopedFileAccess.child(fd, name, if (create) 2 else 0))
    fun read(name: String): InputStream = ParcelFileDescriptor.AutoCloseInputStream(file(name))
    fun create(name: String): OutputStream = ParcelFileDescriptor.AutoCloseOutputStream(file(name, true))
    fun sync() = Os.fsync(descriptor.fileDescriptor)
    fun parent(parts: List<String>, create: Boolean = false): ScopedDirectory {
        var current = ScopedDirectory(ParcelFileDescriptor.dup(descriptor.fileDescriptor))
        try {
            for (part in parts) {
                val next = if (create) runCatching { current.child(part) }.getOrElse { current.child(part, true) } else current.child(part)
                current.close(); current = next
            }
            return current
        } catch (failure: Exception) { current.close(); throw failure }
    }
    fun clear() {
        names().forEach { name ->
            val child = runCatching { child(name) }.getOrNull()
            if (child != null) { child.use { it.clear() }; ScopedFileAccess.unlink(fd, name, true) }
            else ScopedFileAccess.unlink(fd, name, false) // unlink a leaf; never follow it, including symlinks.
        }
    }
    override fun close() = descriptor.close()
    companion object {
        fun open(root: File) = ScopedDirectory(ParcelFileDescriptor.adoptFd(ScopedFileAccess.root(root.absolutePath)))
    }
}
