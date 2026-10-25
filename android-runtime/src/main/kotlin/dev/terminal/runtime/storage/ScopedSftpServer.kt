package dev.terminal.runtime.storage

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants.*
import android.system.StructStat
import dev.terminal.emulator.ScopedFileAccess
import dev.terminal.protocol.SftpWire
import dev.terminal.protocol.SftpWire.bytes
import dev.terminal.protocol.SftpWire.text
import java.io.*
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.concurrent.Semaphore

/** One stream, one pinned folder capability. Guest paths cannot select a host root or another share. */
class ScopedSftpServer(directory: File) : Closeable {
    private val root = ScopedDirectory.open(directory)
    private class Handle(val descriptor: ParcelFileDescriptor, val writable: Boolean,
        val listing: DirectoryStream<java.nio.file.Path>? = null) : Closeable {
        val iterator = listing?.iterator()
        override fun close() { try { listing?.close() } finally { descriptor.close(); HANDLES.release() } }
    }
    private val handles = mutableMapOf<String, Handle>()
    private data class Attributes(val size: Long?, val uid: Int?, val gid: Int?, val mode: Int?, val accessed: Long?, val modified: Long?)

    fun serve(input: InputStream, output: OutputStream) {
        val source = DataInputStream(input); val sink = DataOutputStream(output)
        val init = SftpWire.read(source) ?: throw EOFException("Missing SFTP INIT")
        require(init.input.readUnsignedByte() == 1 && init.input.readInt() >= 3)
        init.end()
        SftpWire.send(sink) {
            writeByte(2); writeInt(3)
            text("posix-rename@openssh.com"); text("1")
            text("fsync@openssh.com"); text("1")
            text("statvfs@openssh.com"); text("2")
        }
        while (true) {
            val packet = SftpWire.read(source) ?: return
            val operation = packet.input.readUnsignedByte(); val id = packet.input.readInt()
            OPERATIONS.acquire()
            try {
                val reply = runCatching { request(operation, packet) }.getOrElse { error ->
                    val status = when (error) {
                        is ErrnoException -> when (error.errno) {
                            ENOENT, ENOTDIR -> 2
                            EACCES, EPERM, ELOOP -> 3
                            else -> 4
                        }
                        is IllegalArgumentException, is EOFException -> 5
                        is UnsupportedOperationException -> 8
                        else -> 4
                    }
                    // Never disclose app-private paths, native exceptions or host process details.
                    status(status)
                }
                SftpWire.send(sink) { writeByte(reply.first); writeInt(id); write(reply.second) }
            } finally { OPERATIONS.release() }
        }
    }

    private fun request(type: Int, p: SftpWire.Packet): Pair<Int, ByteArray> = when (type) {
        3 -> { // OPEN: parse everything before opening or truncating anything.
            val path = p.text(); val flags = p.input.readInt(); val attributes = attributes(p); p.end()
            require(flags and 0x3f.inv() == 0 && flags and 3 != 0 && (flags and 0x38 == 0 || flags and 2 != 0))
            val writable = flags and 2 != 0
            newHandle {
                parent(path) { parent, name ->
                    var nativeFlags = O_NOFOLLOW or O_CLOEXEC or O_NONBLOCK
                    nativeFlags = nativeFlags or if (flags and 3 == 3) O_RDWR else if (writable) O_WRONLY else O_RDONLY
                    if (flags and 8 != 0) nativeFlags = nativeFlags or O_CREAT
                    if (flags and 32 != 0) nativeFlags = nativeFlags or O_EXCL
                    if (flags and 4 != 0) nativeFlags = nativeFlags or O_APPEND
                    val fd = Os.open(childPath(parent, name), nativeFlags, (attributes.mode ?: 0x180) and 0x1ff)
                    try {
                        val stat = Os.fstat(fd)
                        require(S_ISREG(stat.st_mode) && stat.st_nlink == 1L) { "Regular single-link files only" }
                        if (flags and 16 != 0) Os.ftruncate(fd, 0)
                        Handle(ParcelFileDescriptor.dup(fd), writable)
                    } finally { Os.close(fd) }
                }
            }
        }
        4 -> {
            val key = p.text(64); p.end()
            (handles.remove(key) ?: error("Unknown handle")).close(); status(0)
        }
        5 -> {
            val key = p.text(64); val offset = p.input.readLong(); val size = p.input.readInt(); p.end()
            require(offset >= 0 && size in 1..SftpWire.MAX_DATA)
            val handle = fileHandle(key)
            val data = ByteArray(size)
            val count = Os.pread(handle.descriptor.fileDescriptor, data, 0, size, offset)
            if (count == 0) status(1) else reply(103) { writeInt(count); write(data, 0, count) }
        }
        6 -> {
            val key = p.text(64); val offset = p.input.readLong(); val data = p.bytes(SftpWire.MAX_DATA); p.end()
            require(offset >= 0 && offset <= Long.MAX_VALUE - data.size)
            val handle = fileHandle(key); check(handle.writable)
            var position = 0
            while (position < data.size) {
                val written = Os.pwrite(handle.descriptor.fileDescriptor, data, position, data.size - position, offset + position)
                check(written > 0); position += written
            }
            status(0)
        }
        7, 17 -> { val path = p.text(); p.end(); reply(105) { attr(stat(path)) } }
        8 -> { val key = p.text(64); p.end(); reply(105) { attr(Os.fstat(handle(key).descriptor.fileDescriptor)) } }
        9 -> {
            val path = p.text(); val attributes = attributes(p); p.end()
            withNode(path, write = attributes.size != null) { setAttributes(it, attributes) }; status(0)
        }
        10 -> {
            val key = p.text(64); val attributes = attributes(p); p.end()
            val handle = handle(key); check(handle.writable || handle.listing != null)
            setAttributes(handle.descriptor, attributes); status(0)
        }
        11 -> {
            val path = p.text(); p.end()
            newHandle {
                directory(path).use { dir ->
                    val descriptor = ParcelFileDescriptor.fromFd(dir.fd)
                    try { Handle(descriptor, false, Files.newDirectoryStream(Paths.get("/proc/self/fd/${descriptor.fd}"))) }
                    catch (e: Exception) { descriptor.close(); throw e }
                }
            }
        }
        12 -> {
            val key = p.text(64); p.end(); val handle = handle(key); val iterator = checkNotNull(handle.iterator)
            val entries = mutableListOf<Pair<String, StructStat>>()
            while (entries.size < 128 && iterator.hasNext()) {
                val name = iterator.next().fileName.toString()
                // Concurrent deletions are ordinary directory mutations, not permission failures.
                try { entries += name to Os.lstat("/proc/self/fd/${handle.descriptor.fd}/$name") }
                catch (e: ErrnoException) { if (e.errno != ENOENT) throw e }
            }
            if (entries.isEmpty()) status(1) else reply(104) {
                writeInt(entries.size); entries.forEach { (name, stat) -> text(name); text(name); attr(stat) }
            }
        }
        13, 15 -> {
            val path = p.text(); p.end()
            parent(path) { dir, name -> ScopedFileAccess.unlink(dir.fd, name, type == 15) }; status(0)
        }
        14 -> {
            val path = p.text(); val attributes = attributes(p); p.end()
            require(attributes.size == null)
            parent(path) { dir, name -> Os.mkdir(childPath(dir, name), (attributes.mode ?: 0x1c0) and 0x1ff) }; status(0)
        }
        16 -> {
            val path = p.text(); p.end(); val normalized = "/" + SftpWire.components(path).joinToString("/")
            reply(104) { writeInt(1); text(normalized); text(normalized); writeInt(0) }
        }
        18 -> {
            val source = p.text(); val target = p.text(); p.end(); rename(source, target, false); status(0)
        }
        19 -> {
            val path = p.text(); p.end(); val link = parent(path) { dir, name -> Os.readlink(childPath(dir, name)) }
            reply(104) { writeInt(1); text(link); text(link); writeInt(0) }
        }
        20 -> { // OpenSSH's v3 ordering: target first, new link name second.
            val target = p.text(); val path = p.text(); p.end()
            parent(path) { dir, name -> Os.symlink(target, childPath(dir, name)) }; status(0)
        }
        200 -> when (val extension = p.text()) {
            "posix-rename@openssh.com" -> {
                val source = p.text(); val target = p.text(); p.end(); rename(source, target, true); status(0)
            }
            "fsync@openssh.com" -> { val key = p.text(64); p.end(); Os.fsync(fileHandle(key).descriptor.fileDescriptor); status(0) }
            "statvfs@openssh.com" -> {
                val path = p.text(); p.end()
                withNode(path) { node ->
                    val stat = Os.fstatvfs(node.fileDescriptor)
                    reply(201) { listOf(stat.f_bsize, stat.f_frsize, stat.f_blocks, stat.f_bfree, stat.f_bavail,
                        stat.f_files, stat.f_ffree, stat.f_favail, 0L, 0L, stat.f_namemax).forEach(::writeLong) }
                }
            }
            else -> throw UnsupportedOperationException(extension)
        }
        else -> throw UnsupportedOperationException("SFTP operation")
    }

    private fun handle(key: String) = handles[key] ?: error("Unknown handle")
    private fun fileHandle(key: String) = handle(key).also { require(it.listing == null) }
    private fun newHandle(open: () -> Handle): Pair<Int, ByteArray> {
        check(handles.size < 128 && HANDLES.tryAcquire()) { "Share handle limit" }
        val handle = try { open() } catch (e: Exception) { HANDLES.release(); throw e }
        val key = UUID.randomUUID().toString(); handles[key] = handle
        return reply(102) { text(key) }
    }
    private fun directory(path: String) = root.parent(SftpWire.components(path))
    private fun <T> parent(path: String, block: (ScopedDirectory, String) -> T): T {
        val parts = SftpWire.components(path); require(parts.isNotEmpty()) { "Cannot replace export root" }
        return root.parent(parts.dropLast(1)).use { block(it, parts.last()) }
    }
    private fun childPath(dir: ScopedDirectory, name: String) = "/proc/self/fd/${dir.fd}/$name"
    private fun stat(path: String): StructStat = if (SftpWire.components(path).isEmpty())
        ParcelFileDescriptor.fromFd(root.fd).use { Os.fstat(it.fileDescriptor) }
        else parent(path) { dir, name -> Os.lstat(childPath(dir, name)) }
    private fun <T> withNode(path: String, write: Boolean = false, block: (ParcelFileDescriptor) -> T): T {
        if (SftpWire.components(path).isEmpty()) return ParcelFileDescriptor.fromFd(root.fd).use(block)
        return parent(path) { dir, name ->
            // Linux O_PATH is already used by ScopedFileAccess's component walker. A metadata
            // capability permits the owner to chmod a mode-000 file without opening its contents.
            val fd = Os.open(childPath(dir, name), (if (write) O_RDWR else 0x200000) or O_NOFOLLOW or O_NONBLOCK or O_CLOEXEC, 0)
            try {
                val stat = Os.fstat(fd)
                require(S_ISDIR(stat.st_mode) || (S_ISREG(stat.st_mode) && stat.st_nlink == 1L))
                ParcelFileDescriptor.dup(fd).use(block)
            } finally { Os.close(fd) }
        }
    }
    private fun rename(source: String, target: String, replace: Boolean) = parent(source) { from, old ->
        parent(target) { to, name ->
            if (replace) Os.rename(childPath(from, old), childPath(to, name))
            else ScopedFileAccess.rename(from.fd, old, to.fd, name)
        }
    }
    private fun attributes(p: SftpWire.Packet): Attributes {
        val flags = p.input.readInt(); require(flags and 15.inv() == 0)
        val size = if (flags and 1 != 0) p.input.readLong().also { require(it >= 0) } else null
        val uid = if (flags and 2 != 0) p.input.readInt() else null
        val gid = if (flags and 2 != 0) p.input.readInt() else null
        val mode = if (flags and 4 != 0) p.input.readInt() else null
        val accessed = if (flags and 8 != 0) p.input.readInt().toLong() and 0xffff_ffffL else null
        val modified = if (flags and 8 != 0) p.input.readInt().toLong() and 0xffff_ffffL else null
        require(uid == null || uid == 1000); require(gid == null || gid == 1000)
        return Attributes(size, uid, gid, mode, accessed, modified)
    }
    private fun setAttributes(node: ParcelFileDescriptor, attributes: Attributes) {
        attributes.size?.let { Os.ftruncate(node.fileDescriptor, it) }
        val path = "/proc/self/fd/${node.fd}"
        attributes.mode?.let { Os.chmod(path, it and 0x1ff) }
        if (attributes.modified != null) Files.getFileAttributeView(Paths.get(path), BasicFileAttributeView::class.java)
            .setTimes(FileTime.from(attributes.modified, TimeUnit.SECONDS),
                attributes.accessed?.let { FileTime.from(it, TimeUnit.SECONDS) }, null)
    }
    private fun DataOutputStream.attr(stat: StructStat) {
        writeInt(15); writeLong(stat.st_size); writeInt(1000); writeInt(1000)
        writeInt(stat.st_mode and 0xf1ff); writeInt(stat.st_atime.toInt()); writeInt(stat.st_mtime.toInt())
    }
    private fun reply(type: Int, body: DataOutputStream.() -> Unit): Pair<Int, ByteArray> {
        val raw = ByteArrayOutputStream(); DataOutputStream(raw).use(body); return type to raw.toByteArray()
    }
    private fun status(code: Int) = reply(101) { writeInt(code); text(""); text("") }
    override fun close() { handles.values.forEach { runCatching { it.close() } }; handles.clear(); root.close() }
    private companion object {
        val HANDLES = Semaphore(256)
        val OPERATIONS = Semaphore(4)
    }
}
