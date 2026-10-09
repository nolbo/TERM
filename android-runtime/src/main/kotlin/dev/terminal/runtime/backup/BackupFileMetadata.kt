package dev.terminal.runtime.backup

import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.FileTime

/** Android's NIO provider cannot open a symlink for setTimes with O_NOFOLLOW.
 * Keep its relationship intact rather than changing the target's timestamp.
 * Regular-file/directory metadata remains mandatory; link time is best effort on this provider.
 */
internal fun setSymbolicLinkTime(path: Path, time: FileTime) {
    require(Files.isSymbolicLink(path))
    try {
        Files.getFileAttributeView(path, BasicFileAttributeView::class.java, NOFOLLOW_LINKS).setTimes(time, null, null)
    } catch (failure: FileSystemException) {
        if (failure.reason?.contains("unable to access attributes of symbolic link") != true) throw failure
    }
}
