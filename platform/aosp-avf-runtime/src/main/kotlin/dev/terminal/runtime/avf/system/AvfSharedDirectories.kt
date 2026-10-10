package dev.terminal.runtime.avf.system

import dev.terminal.runtime.api.SharedDirectoryExport
import dev.terminal.runtime.storage.SharedFolder
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.UUID

/** One boot's private sockets and tag/name table. No parent of a user folder is ever exported. */
internal class AvfSharedDirectories private constructor(
    private val files: File,
    private val root: File,
    val exports: List<Export>,
) : Closeable {
    data class Export(val directory: File, val tag: String, val socket: File)
    val artifact get() = root.name

    override fun close() {
        cleanup(files, artifact)
    }

    companion object {
        const val INDEX_TAG = "term-shares"
        const val BOOT_PARAM = "term.virtiofs=1"
        const val GUEST_UID = 1000
        const val GUEST_GID = 1000

        fun cleanup(files: File, artifact: String) {
            require(Regex("v-[A-Za-z0-9_-]{1,64}").matches(artifact))
            val root = File(File(files, ".virtiofs"), artifact).absoluteFile
            if (Files.notExists(root.toPath(), NOFOLLOW_LINKS)) return
            require(Files.isDirectory(root.toPath(), NOFOLLOW_LINKS) && root.canonicalFile == root)
            // Remove only our own names. Never traverse guest-created entries or user folders.
            for (number in 0..128) Files.deleteIfExists(File(root, "s$number").toPath())
            val index = File(root, "index")
            if (Files.isDirectory(index.toPath(), NOFOLLOW_LINKS)) Files.deleteIfExists(File(index, "mounts").toPath())
            index.delete()
            root.delete()
        }

        fun validate(files: File, folders: List<SharedDirectoryExport>) {
            require(folders.size <= 128 && folders.map { it.id }.distinct().size == folders.size &&
                folders.map { it.name }.distinct().size == folders.size) { "Invalid AVF shared folder snapshot" }
            folders.forEach { folder ->
                require(UUID.fromString(folder.id).toString() == folder.id && SharedFolder.NAME.matches(folder.name))
                val expected = File(File(files, "shared-folders"), folder.id).absoluteFile
                require(File(folder.directory).absoluteFile == expected && expected.canonicalFile == expected &&
                    Files.isDirectory(expected.toPath(), NOFOLLOW_LINKS)) { "Unsafe AVF shared directory" }
            }
        }

        fun create(files: File, folders: List<SharedDirectoryExport>): AvfSharedDirectories? {
            validate(files, folders)
            if (folders.isEmpty()) return null
            val parent = File(files, ".virtiofs")
            if (Files.notExists(parent.toPath(), NOFOLLOW_LINKS)) Files.createDirectory(parent.toPath())
            require(Files.isDirectory(parent.toPath(), NOFOLLOW_LINKS) && parent.canonicalFile == parent.absoluteFile) {
                "Unsafe virtiofs socket root"
            }
            val root = Files.createTempDirectory(parent.toPath(), "v-").toFile()
            val index = File(root, "index")
            val exports = listOf(Export(index, INDEX_TAG, File(root, "s0"))) + folders.mapIndexed { number, folder ->
                // The virtio-fs wire tag has only 36 bytes; folder names may contain 64 ASCII characters.
                Export(File(folder.directory), "ts-" + folder.id.replace("-", ""), File(root, "s${number + 1}"))
            }
            val plan = AvfSharedDirectories(files, root, exports)
            try {
                require(exports.all { it.socket.path.toByteArray().size <= 107 }) { "virtiofs Unix socket path is too long" }
                Files.createDirectory(index.toPath())
                val table = "1\n" + folders.joinToString("") { "ts-${it.id.replace("-", "")}\t${it.name}\n" }
                Files.write(File(index, "mounts").toPath(), table.toByteArray(Charsets.US_ASCII), CREATE_NEW)
                return plan
            } catch (failure: Throwable) {
                runCatching { plan.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }
    }
}
