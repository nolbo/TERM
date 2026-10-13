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

    fun clean(destination: Path, currentWorkId: UUID, isActive: (UUID) -> Boolean) {
        val parent = destination.parent
        if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) return
        val prefix = ".${destination.fileName}.install-work-"
        Files.list(parent).use { children ->
            children.filter { it.fileName.toString().startsWith(prefix) }.forEach { child ->
                val suffix = child.fileName.toString().removePrefix(prefix)
                val workId = runCatching { UUID.fromString(suffix) }.getOrNull() ?: return@forEach
                if (workId.toString() != suffix || (workId != currentWorkId && isActive(workId))) return@forEach
                // A stopped/retried attempt cannot own the installed destination.
                try {
                    Files.walk(child).use { paths ->
                        paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                    }
                } catch (_: java.nio.file.NoSuchFileException) {
                    // A cancelled worker may already have finished its own cleanup.
                }
            }
        }
    }
}
