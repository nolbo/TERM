package dev.terminal.runtime.rootless

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest

/** Pure, fixture-testable verifier and extractor for the catalog-pinned rootfs. */
internal class RootlessArchiveExtractor(
    private val archiveRoot: String,
    private val maxExtractedBytes: Long,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    fun verifySha256(archive: Path, expected: String) {
        val actual = Files.newInputStream(archive).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        require(actual == expected) { "Debian archive SHA-256 mismatch" }
    }

    fun extract(archive: Path, destination: Path) {
        val directoryModes = linkedMapOf<Path, Int>()
        val pendingHardLinks = mutableListOf<PendingHardLink>()
        var extractedBytes = 0L
        var entryCount = 0
        BufferedInputStream(Files.newInputStream(archive)).use { compressed ->
            XZCompressorInputStream(compressed).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        entryCount++
                        require(entryCount <= maxEntries) { "Debian archive contains too many entries" }
                        require(entry.name.length <= MAX_PATH_CHARACTERS) { "Debian archive path is too long" }
                        val target = safeTarget(destination, entry.name)
                        when {
                            entry.isCharacterDevice && isGuestDevPath(destination, target) -> {
                                // The catalog contains inert /dev placeholders. The live host /dev is
                                // explicitly bound by PRoot, so never materialize archive device nodes.
                            }
                            entry.isBlockDevice || entry.isFIFO || entry.isCharacterDevice ->
                                error("Debian archive contains an unsupported special file: ${entry.name}")
                            entry.isDirectory -> createDirectory(destination, target, entry.mode, directoryModes)
                            entry.isSymbolicLink -> createSafeSymbolicLink(destination, target, entry.linkName)
                            entry.isLink -> pendingHardLinks += PendingHardLink(target, safeTarget(destination, entry.linkName))
                            entry.isFile -> {
                                require(entry.size in 0..maxExtractedBytes) { "Invalid Debian archive entry size" }
                                extractedBytes += entry.size
                                require(extractedBytes <= maxExtractedBytes) { "Debian rootfs exceeds the approved size" }
                                createFile(destination, target, entry, tar)
                            }
                            else -> error("Debian archive contains an unsupported special file: ${entry.name}")
                        }
                    }
                }
            }
        }
        resolveHardLinks(destination, pendingHardLinks)
        directoryModes.entries.sortedByDescending { it.key.nameCount }
            .forEach { (path, mode) -> setPermissions(path, mode) }
    }

    private fun createDirectory(root: Path, target: Path, mode: Int, modes: MutableMap<Path, Int>) {
        ensureNoSymlinkAncestors(root, target)
        if (Files.exists(target, NOFOLLOW_LINKS)) {
            require(Files.isDirectory(target, NOFOLLOW_LINKS)) { "Duplicate Debian archive path" }
        } else {
            Files.createDirectories(target)
        }
        modes[target] = mode
    }

    private fun createFile(root: Path, target: Path, entry: TarArchiveEntry, tar: TarArchiveInputStream) {
        require(!Files.exists(target, NOFOLLOW_LINKS)) { "Duplicate Debian archive path" }
        val parent = requireNotNull(target.parent)
        ensureNoSymlinkAncestors(root, parent)
        Files.createDirectories(parent)
        BufferedOutputStream(Files.newOutputStream(target, CREATE_NEW)).use { output ->
            val copied = tar.copyTo(output)
            require(copied == entry.size) { "Truncated Debian archive entry" }
        }
        setPermissions(target, entry.mode)
    }

    private fun createSafeSymbolicLink(root: Path, target: Path, linkName: String) {
        require(!Files.exists(target, NOFOLLOW_LINKS)) { "Duplicate Debian archive path" }
        require(linkName.isNotEmpty()) { "Debian archive contains an empty symbolic link" }
        val parent = requireNotNull(target.parent)
        ensureNoSymlinkAncestors(root, parent)
        val link = Path.of(linkName)
        val resolved = if (link.isAbsolute) {
            root.resolve(link.normalize().toString().removePrefix("/")).normalize()
        } else {
            parent.resolve(link).normalize()
        }
        require(resolved.startsWith(root)) { "Debian archive contains an unsafe symbolic link" }
        Files.createDirectories(parent)
        Files.createSymbolicLink(target, parent.relativize(resolved))
    }

    private fun resolveHardLinks(root: Path, links: MutableList<PendingHardLink>) {
        while (links.isNotEmpty()) {
            var progress = false
            val iterator = links.iterator()
            while (iterator.hasNext()) {
                val link = iterator.next()
                if (!Files.isRegularFile(link.source, NOFOLLOW_LINKS)) continue
                require(!Files.exists(link.target, NOFOLLOW_LINKS)) { "Duplicate Debian archive path" }
                val parent = requireNotNull(link.target.parent)
                ensureNoSymlinkAncestors(root, parent)
                Files.createDirectories(parent)
                Files.createLink(link.target, link.source)
                iterator.remove()
                progress = true
            }
            require(progress) { "Debian archive contains an unresolved hard link" }
        }
    }

    private fun safeTarget(root: Path, name: String): Path {
        require(name == archiveRoot || name.startsWith("$archiveRoot/")) { "Unexpected Debian archive layout" }
        val relativeText = name.removePrefix(archiveRoot).removePrefix("/")
        val relative = checkedRelative(relativeText, "path")
        val target = root.resolve(relative).normalize()
        require(target.startsWith(root)) { "Debian archive escaped its rootfs" }
        return target
    }

    private fun isGuestDevPath(root: Path, target: Path): Boolean =
        root.relativize(target).firstOrNull()?.toString() == "dev"

    private fun checkedRelative(value: String, kind: String): Path {
        val path = Path.of(value)
        require(!path.isAbsolute && path.none { it.toString() == ".." }) { "Debian archive contains an unsafe $kind" }
        return path.normalize()
    }

    private fun ensureNoSymlinkAncestors(root: Path, target: Path) {
        require(target.normalize().startsWith(root)) { "Debian archive escaped its rootfs" }
        var current = root
        root.relativize(target).forEach { part ->
            current = current.resolve(part)
            require(!Files.isSymbolicLink(current)) { "Debian archive path traverses a symbolic link" }
        }
    }

    private fun setPermissions(path: Path, mode: Int) {
        val permissions = mutableSetOf<PosixFilePermission>()
        MODE_PERMISSIONS.forEach { (bit, permission) -> if (mode and bit != 0) permissions += permission }
        Files.setPosixFilePermissions(path, permissions)
    }

    private data class PendingHardLink(val target: Path, val source: Path)

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val DEFAULT_MAX_ENTRIES = 100_000
        const val MAX_PATH_CHARACTERS = 4096
        val MODE_PERMISSIONS = listOf(
            0b100_000_000 to PosixFilePermission.OWNER_READ,
            0b010_000_000 to PosixFilePermission.OWNER_WRITE,
            0b001_000_000 to PosixFilePermission.OWNER_EXECUTE,
            0b000_100_000 to PosixFilePermission.GROUP_READ,
            0b000_010_000 to PosixFilePermission.GROUP_WRITE,
            0b000_001_000 to PosixFilePermission.GROUP_EXECUTE,
            0b000_000_100 to PosixFilePermission.OTHERS_READ,
            0b000_000_010 to PosixFilePermission.OTHERS_WRITE,
            0b000_000_001 to PosixFilePermission.OTHERS_EXECUTE,
        )
    }
}
