package dev.terminal.image

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.util.Properties
import java.util.UUID

/** Called only while the service owns the storage lease and has proved all attached VMs stopped. */
object AvfImageTransitions {
    const val MAINTENANCE = ".avf-maintenance"
    private const val HISTORY = ".avf-versions"
    private const val JOURNAL = ".avf-transition"
    private const val PREVIOUS = ".avf-previous"
    val artifacts = listOf("manifest.properties", "vmlinuz", "initrd.img", "system.img", "home.img", "config.img")
    val payload = artifacts + listOf(".installed-manifest-sha256", ".apt-index-initialization",
        AvfPackageInitialization.FILE_NAME, AvfGuestComponents.MANIFEST, AvfGuestComponents.RECEIPT)

    fun interrupted(root: Path): Boolean = Files.exists(root.resolve(JOURNAL), NOFOLLOW_LINKS)

    fun previous(root: Path): Path? = runCatching {
        val file = root.resolve(PREVIOUS)
        require(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) <= 36)
        val id = Files.readAllBytes(file).toString(Charsets.US_ASCII)
        require(UUID.fromString(id).toString() == id)
        require(Files.isDirectory(root.resolve(HISTORY), NOFOLLOW_LINKS))
        root.resolve(HISTORY).resolve(id).takeIf { Files.isDirectory(it, NOFOLLOW_LINKS) }
    }.getOrNull()

    /** A journal always rolls back. No guest may boot until this method has returned. */
    fun recover(root: Path) {
        if (!interrupted(root)) return
        val journal = root.resolve(JOURNAL)
        require(Files.isRegularFile(journal, NOFOLLOW_LINKS) && Files.size(journal) <= 256)
        val fields = Properties().apply { Files.newInputStream(journal).use(::load) }
        val id = requireNotNull(fields.getProperty("backup"))
        require(UUID.fromString(id).toString() == id)
        val backup = root.resolve(HISTORY).resolve(id)
        require(Files.isDirectory(root.resolve(HISTORY), NOFOLLOW_LINKS))
        require(Files.isDirectory(backup, NOFOLLOW_LINKS))
        payload.forEach { name ->
            val old = backup.resolve(name)
            if (Files.exists(old, NOFOLLOW_LINKS)) Files.move(old, root.resolve(name), REPLACE_EXISTING)
            // Optional receipts absent from the original must not survive recovery.
            else if (fields.getProperty("absent", "").split(',').contains(name)) Files.deleteIfExists(root.resolve(name))
        }
        writePrevious(root, fields.getProperty("previous", ""))
        sync(root); sync(backup)
        Files.delete(journal); sync(root)
        Files.delete(backup)
    }

    /** Preserve complete disk generations, including writable state; never reuse an old upper on a new base. */
    fun activate(root: Path, candidate: Path, distribution: AvfDistribution,
        activate: (() -> Unit) -> Unit = { it() }, checkpoint: (String) -> Unit = {}) {
        check(!interrupted(root)) { "Recover the interrupted AVF transition first" }
        require(AvfImageVerifier.verifyInstalled(root, distribution) is ImageVerification.Ready)
        require(AvfImageVerifier.verifyInstalled(candidate, distribution) is ImageVerification.Ready)
        payload.forEach { name ->
            val path = candidate.resolve(name)
            if (Files.exists(path, NOFOLLOW_LINKS)) {
                require(Files.isRegularFile(path, NOFOLLOW_LINKS))
                force(path)
            }
        }
        require(root.toAbsolutePath().normalize() != candidate.toAbsolutePath().normalize())
        activate {
            val versions = root.resolve(HISTORY)
            if (Files.exists(versions, NOFOLLOW_LINKS)) require(Files.isDirectory(versions, NOFOLLOW_LINKS))
            else Files.createDirectory(versions)
            val id = UUID.randomUUID().toString()
            val backup = Files.createDirectory(versions.resolve(id))
            val oldPrevious = previous(root)?.fileName?.toString().orEmpty()
            val absent = payload.filter { !Files.exists(root.resolve(it), NOFOLLOW_LINKS) }
            val journal = root.resolve(JOURNAL)
            val journalTemp = root.resolve("$JOURNAL.tmp")
            Files.deleteIfExists(journalTemp)
            Files.write(journalTemp, "backup=$id\nprevious=$oldPrevious\nabsent=${absent.joinToString(",")}\n".toByteArray(),
                java.nio.file.StandardOpenOption.CREATE_NEW, WRITE)
            force(journalTemp)
            Files.move(journalTemp, journal)
            sync(versions); sync(root)
            try {
                checkpoint("journal")
                payload.forEach { name ->
                    if (Files.exists(root.resolve(name), NOFOLLOW_LINKS)) Files.move(root.resolve(name), backup.resolve(name))
                    checkpoint("backup:$name")
                }
                sync(backup); sync(root)
                payload.forEach { name ->
                    if (Files.exists(candidate.resolve(name), NOFOLLOW_LINKS)) Files.move(candidate.resolve(name), root.resolve(name))
                    checkpoint("activate:$name")
                }
                sync(candidate); sync(root)
                writePrevious(root, id)
                Files.delete(journal); sync(root)
            } catch (failure: Exception) {
                recover(root)
                throw failure
            }
        }
    }

    fun rollback(root: Path, distribution: AvfDistribution) {
        val old = previous(root) ?: error("복구할 이전 AVF 이미지가 없습니다.")
        activate(root, old, distribution)
        Files.delete(old)
    }

    /** Explicit user action only, after VM-stop proof. Does not touch the active generation. */
    fun discardHistory(root: Path) {
        check(!interrupted(root))
        val versions = root.resolve(HISTORY)
        if (Files.exists(versions, NOFOLLOW_LINKS)) deleteTree(versions)
        Files.deleteIfExists(root.resolve(PREVIOUS)); sync(root)
    }

    fun deleteTree(root: Path) {
        if (!Files.exists(root, NOFOLLOW_LINKS)) return
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private fun writePrevious(root: Path, id: String) {
        if (id.isEmpty()) Files.deleteIfExists(root.resolve(PREVIOUS))
        else {
            val temporary = root.resolve("$PREVIOUS.tmp")
            Files.deleteIfExists(temporary)
            Files.write(temporary, id.toByteArray(Charsets.US_ASCII), java.nio.file.StandardOpenOption.CREATE_NEW, WRITE)
            force(temporary)
            Files.move(temporary, root.resolve(PREVIOUS), REPLACE_EXISTING)
        }
    }
    private fun force(file: Path) { FileChannel.open(file, WRITE).use { it.force(true) } }
    private fun sync(directory: Path) { FileChannel.open(directory).use { it.force(true) } }
}
