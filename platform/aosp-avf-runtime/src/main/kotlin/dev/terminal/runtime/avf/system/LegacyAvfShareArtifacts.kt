package dev.terminal.runtime.avf.system

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Upgrade cleanup only, after a legacy VM is proven stopped. Never removes folder data. */
internal object LegacyAvfShareArtifacts {
    fun cleanup(files: File, artifact: String, publicRoot: File? = null) {
        require(Regex("v-[A-Za-z0-9_-]{1,64}").matches(artifact))
        cleanupRoot(File(File(files, ".virtiofs"), artifact).absoluteFile)
        if (artifact.startsWith("v-p-")) {
            cleanupRoot(File(File(checkNotNull(publicRoot), ".virtiofs"), artifact).absoluteFile)
        }
    }

    private fun cleanupRoot(root: File) {
        if (Files.notExists(root.toPath(), NOFOLLOW_LINKS)) return
        require(Files.isDirectory(root.toPath(), NOFOLLOW_LINKS) && root.canonicalFile == root)
        // Remove only our own names. Never traverse guest-created entries or user folders.
        for (number in 0..129) Files.deleteIfExists(File(root, "s$number").toPath())
        val index = File(root, "index")
        if (Files.isDirectory(index.toPath(), NOFOLLOW_LINKS)) Files.deleteIfExists(File(index, "mounts").toPath())
        index.delete()
        root.delete()
    }
}
