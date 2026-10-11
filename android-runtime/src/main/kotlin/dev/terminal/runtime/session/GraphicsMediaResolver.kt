package dev.terminal.runtime.session

import dev.terminal.emulator.GraphicsFileReader
import dev.terminal.emulator.KittyGraphicsTransport
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** The backend selects these mappings; terminal output cannot select host roots or another environment. */
internal class GraphicsMediaResolver(
    mappings: List<Mapping>,
    private val temporaryDirectories: Set<String>,
    private val sharedMemoryDirectory: String? = null,
    private val reader: (String, String, Long, Long, Boolean) -> ByteArray = GraphicsFileReader::read,
) : KittyGraphicsTransport.Resolver {
    data class Mapping(val guest: String, val host: File)
    // Resolve Android's trusted /data/user/0 alias once when the backend grants the roots.
    // Guest-controlled components below them are still walked without symlinks by JNI.
    private val roots = mappings.map { it.copy(host = it.host.canonicalFile) }.sortedByDescending { it.guest.length }

    override fun read(medium: Char, name: String, offset: Long, size: Long): ByteArray {
        var path = when (medium) {
            's' -> {
                if (!name.startsWith('/') || name.length <= 1 || name.drop(1).contains('/') || name.toByteArray().size > 255)
                    throw IOException("EINVAL: shared memory name")
                (sharedMemoryDirectory ?: throw IOException("ENOTSUP: shared memory")) + name
            }
            'f', 't' -> name
            else -> throw IOException("EINVAL: media type")
        }
        if (!path.startsWith('/') || path.contains('\u0000')) throw IOException("EINVAL: media path")
        path = normalize(path)
        var links = 0
        while (true) {
            val components = path.removePrefix("/").split('/').filter(String::isNotEmpty)
            var followed = false
            for (index in components.indices) {
                val prefix = "/" + components.take(index + 1).joinToString("/")
                // /dev itself is traversed only to reach the host-selected shm mapping.
                if ((prefix == "/proc" || prefix == "/sys" || prefix == "/dev") &&
                    !(prefix == "/dev" && sharedMemoryDirectory != null && inside(path, sharedMemoryDirectory)))
                    throw IOException("EACCES: sensitive path")
                if (prefix == "/dev" && sharedMemoryDirectory != null && inside(path, sharedMemoryDirectory)) continue
                val mapping = roots.firstOrNull { inside(prefix, it.guest) } ?: continue
                val relative = prefix.removePrefix(mapping.guest).removePrefix("/")
                val file = File(mapping.host, relative).toPath()
                if (Files.isSymbolicLink(file)) {
                    if (++links > 40) throw IOException("ELOOP: media symlink")
                    val target = Files.readSymbolicLink(file).toString()
                    val parent = prefix.substringBeforeLast('/', "")
                    val suffix = components.drop(index + 1).joinToString("/")
                    path = normalize((if (target.startsWith('/')) target else "$parent/$target") + "/" + suffix)
                    followed = true
                    break
                }
            }
            if (!followed) break
        }
        if (medium == 's' && (sharedMemoryDirectory == null || !inside(path, sharedMemoryDirectory)))
            throw IOException("EACCES: shared memory path")
        val mapping = roots.firstOrNull { inside(path, it.guest) } ?: throw IOException("EACCES: media path")
        val relative = path.removePrefix(mapping.guest).removePrefix("/")
        if (relative.isEmpty()) throw IOException("EACCES: regular files only")
        val remove = medium == 's' || (medium == 't' && path.contains("tty-graphics-protocol") &&
            temporaryDirectories.any { inside(path, it) })
        // JNI walks each component with openat/O_NOFOLLOW, pins the final directory,
        // verifies a regular file, bounds pread, and unlinks only that directory entry.
        return reader(mapping.host.path, relative, offset, size, remove)
    }

    private fun inside(path: String, prefix: String) = prefix == "/" || path == prefix || path.startsWith("$prefix/")
    private fun normalize(path: String): String {
        val parts = ArrayDeque<String>()
        path.split('/').forEach {
            when (it) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(it)
            }
        }
        return "/" + parts.joinToString("/")
    }
}
