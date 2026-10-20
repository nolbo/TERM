package dev.terminal.runtime.image

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Comparator
import java.util.UUID

/** Work IDs distinguish resumable downloads from offline imports and other environments. */
internal object AvfImageDownloadStaging {
    fun path(destination: Path, workId: UUID): Path =
        destination.resolveSibling(".${destination.fileName}.install-work-$workId")

    fun cachePath(destination: Path, workId: UUID): Path =
        destination.resolveSibling(".${destination.fileName}.install-cache-work-$workId")

    fun finish(destination: Path, workId: UUID, retry: Boolean, stopped: Boolean) {
        if (retry && !stopped) return
        remove(path(destination, workId))
        remove(cachePath(destination, workId))
    }

    fun clean(destination: Path, currentWorkId: UUID, preserveCurrent: Boolean = false, isActive: (UUID) -> Boolean) {
        val parent = destination.parent
        if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) return
        val prefixes = listOf(".${destination.fileName}.install-work-", ".${destination.fileName}.install-cache-work-")
        Files.list(parent).use { children ->
            children.forEach { child ->
                val prefix = prefixes.firstOrNull { child.fileName.toString().startsWith(it) } ?: return@forEach
                val suffix = child.fileName.toString().removePrefix(prefix)
                val workId = runCatching { UUID.fromString(suffix) }.getOrNull() ?: return@forEach
                if (workId.toString() != suffix || (workId != currentWorkId && isActive(workId))) return@forEach
                if (workId == currentWorkId && preserveCurrent && Files.isDirectory(child, NOFOLLOW_LINKS)) return@forEach
                // A stopped/retried attempt cannot own the installed destination.
                remove(child)
            }
        }
    }

    private fun remove(root: Path) {
        try {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        } catch (_: java.nio.file.NoSuchFileException) {
            // An adopted staging directory or cancelled worker can already have removed it.
        }
    }
}
