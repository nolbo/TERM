package dev.terminal.runtime.storage

import android.content.Context
import android.os.Environment
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Host-selected primary FUSE storage. Never accept this root from Binder or a guest. */
object AndroidSharedFolderStorage {
    fun root(context: Context): File = File(Environment.getExternalStorageDirectory().canonicalFile,
        "TERM/${context.applicationContext.packageName}")

    fun ensureRoot(root: File) {
        // Check every component, including existing ancestors; mkdirs would follow symlinks.
        val path = root.absoluteFile.toPath()
        var current = path.root
        path.forEach { component ->
            current = current.resolve(component)
            if (Files.notExists(current, NOFOLLOW_LINKS)) Files.createDirectory(current)
            require(Files.isDirectory(current, NOFOLLOW_LINKS)) { "Unsafe Android shared-storage directory" }
        }
        require(root.canonicalFile == root.absoluteFile) { "Unsafe Android shared-storage root" }
    }
}
