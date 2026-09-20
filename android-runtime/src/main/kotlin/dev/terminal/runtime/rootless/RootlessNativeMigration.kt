package dev.terminal.runtime.rootless

import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.util.Base64

/** Offline transaction. Caller must exclude ALL sessions/writers to the shared workspace.
 * Regular data is hardlinked into staging, never copied or modified. Only directory
 * entries are changed; inaccessible directory modes are journaled before temporary
 * owner read/search access. Original trees remain available until commit.
 */
internal class RootlessNativeMigration(
    private val layout: RootlessDebianLayout,
    private val checkpoint: (String) -> Unit = {},
    private val createLink: (Path, Path) -> Path = Files::createLink,
) {
    private val parent = layout.root.parentFile.toPath()
    private val journal = parent.resolve(JOURNAL)
    private val transaction = parent.resolve(TRANSACTION)
    private val roots = listOf(layout.rootfs, layout.home, layout.workspace).map {
        val parent = it.parentFile.toPath()
        require(Files.isDirectory(parent)) { "이전 대상의 부모가 실제 디렉터리가 아닙니다" }
        parent.toRealPath().resolve(it.name)
    }
    private val permissionJournal = transaction.resolve("permissions")
    private val accessible = 0b101000000 // Owner read/search, without changing write or special bits.
    private val originalModes = linkedMapOf<Path, Int>()
    private fun stage(index: Int) = transaction.resolve("new-$index")
    private fun backup(index: Int) = transaction.resolve("old-$index")
    private fun exists(path: Path) = Files.exists(path, NOFOLLOW_LINKS)

    fun migrate() {
        recover()
        if (RootlessStorageFormat.resolveForLaunch(roots[0]) == RootlessStorageFormat.LaunchFormat.NATIVE) return
        roots.forEach { require(Files.isDirectory(it, NOFOLLOW_LINKS) && it.toRealPath() == it) { "이전 대상 경로가 실제 디렉터리가 아닙니다: $it" } }
        state("STAGING")
        try {
            Files.createDirectory(transaction)
            // Detect Android hardlink restrictions before traversing any user data.
            val probe = Files.write(transaction.resolve("probe"), byteArrayOf(1))
            try { createLink(transaction.resolve("probe-link"), probe) }
            catch (error: Exception) { throw IllegalStateException("이 기기에서는 네이티브 하드링크를 만들 수 없어 가속 형식으로 이전할 수 없습니다. 기존 설치는 유지됩니다", error) }
            Files.delete(transaction.resolve("probe-link")); Files.delete(probe)
            val entries = linkedMapOf<Path, BasicFileAttributes>()
            fun scan(path: Path) {
                val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                entries[path] = attrs
                if (attrs.isDirectory) {
                    val mode = directoryMode(path)
                    if (mode and accessible != accessible) {
                        originalModes[path] = mode
                        savePermissions() // Durable BEFORE changing an original directory.
                        setDirectoryMode(path, mode or accessible)
                        checkpoint("directory-access")
                    }
                    Files.newDirectoryStream(path).use { children -> children.forEach { scan(it) } }
                }
            }
            roots.forEach { scan(it) }
            val links = entries.filterValues { it.isSymbolicLink }.keys.associateWith { Files.readSymbolicLink(it) }
            fun special(path: Path) = path.fileName?.toString().orEmpty().let { it.startsWith(".l2s.") || it.startsWith(".proot.l2s.") }
            fun target(path: Path): Path {
                val target = links.getValue(path).let { if (it.isAbsolute) it else path.parent.resolve(it) }.normalize()
                // Android /data/user/0 and /data/data may name the same app directory.
                // Canonicalize the parent only: dereferencing the last component
                // would erase the intermediate-link structure being verified.
                return if (special(target)) target.parent.toRealPath().resolve(target.fileName) else target
            }
            val groups = linkedMapOf<Path, MutableList<Path>>()
            val finals = mutableMapOf<Path, Path>()
            links.keys.filter { special(target(it)) && entries[target(it)]?.isSymbolicLink == true }.forEach { member ->
                val intermediate = target(member)
                val data = target(intermediate)
                require(entries[data]?.isRegularFile == true && data.parent == intermediate.parent &&
                    data.fileName.toString().matches(Regex(Regex.escape(intermediate.fileName.toString()) + "\\.[0-9]{4}"))) {
                    "손상되거나 범위 밖에 있는 에뮬레이션 링크입니다: $member"
                }
                groups.getOrPut(intermediate) { mutableListOf() }.add(member)
                finals[intermediate] = data
            }
            groups.forEach { (intermediate, members) ->
                require(finals.getValue(intermediate).fileName.toString().takeLast(4).toInt() == members.size) {
                    "에뮬레이션 링크 수가 맞지 않습니다. 다른 배포판 또는 외부 저장소를 향한 링크인지 확인하세요: $intermediate"
                }
            }
            val members = groups.values.flatten().toSet()
            links.keys.filter { special(target(it)) }.forEach { path ->
                require(path in groups || path in members) {
                    "인식할 수 없거나 외부 저장소를 향한 에뮬레이션 링크입니다: $path"
                }
            }
            val replacements = groups.flatMap { (intermediate, members) -> members.map { it to finals.getValue(intermediate) } }.toMap()
            val omitted = groups.keys + finals.values + roots[0].resolve(".terminal-storage-format")
            roots.forEachIndexed { index, root ->
                entries.filterKeys { it.startsWith(root) }.forEach { (source, attrs) ->
                    if (source !in omitted) {
                        val destination = stage(index).resolve(root.relativize(source))
                        when {
                            source in replacements -> createLink(destination, replacements.getValue(source))
                            attrs.isDirectory -> {
                                Files.copy(source, destination, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES, NOFOLLOW_LINKS)
                                Files.setPosixFilePermissions(destination, Files.getPosixFilePermissions(source, NOFOLLOW_LINKS) +
                                    setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                                        java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE))
                            }
                            attrs.isRegularFile -> createLink(destination, source)
                            attrs.isSymbolicLink -> Files.createSymbolicLink(destination, links.getValue(source))
                            else -> error("특수 파일은 자동 이전할 수 없습니다. 기존 설치는 유지됩니다: $source")
                        }
                    }
                }
                entries.filter { (path, attrs) -> path.startsWith(root) && attrs.isDirectory }.keys
                    .sortedByDescending { it.nameCount }.forEach { source ->
                        val destination = stage(index).resolve(root.relativize(source))
                        java.nio.channels.FileChannel.open(destination, java.nio.file.StandardOpenOption.READ).use { channel ->
                            Files.setLastModifiedTime(destination, entries.getValue(source).lastModifiedTime())
                            setDirectoryMode(destination, originalModes[source] ?: directoryMode(source))
                            channel.force(true)
                        }
                    }
            }
            RootlessStorageFormat.prepareNewInstallation(stage(0), createLink)
            check(RootlessStorageFormat.usesNativeLinks(stage(0))) { "네이티브 하드링크 검증에 실패했습니다" }
            java.nio.channels.FileChannel.open(stage(0).resolve(".terminal-storage-format"),
                java.nio.file.StandardOpenOption.WRITE).use { it.force(true) }
            syncDirectory(stage(0))
            syncDirectory(transaction)
            restorePermissions()
            checkpoint("staged")
            state("PUBLISHING")
            roots.forEachIndexed { index, root ->
                Files.move(root, backup(index), ATOMIC_MOVE)
                syncDirectory(root.parent)
                syncDirectory(transaction)
                checkpoint("backed-up-$index")
                Files.move(stage(index), root, ATOMIC_MOVE)
                syncDirectory(root.parent)
                syncDirectory(transaction)
                checkpoint("published-$index")
            }
            state("COMMITTED")
            checkpoint("committed")
            cleanup()
        } catch (error: Exception) {
            try { recover() } catch (recovery: Exception) { error.addSuppressed(recovery) }
            throw error
        }
    }

    /** Restore a transaction interrupted during publication; complete committed cleanup.
     * Paths are fixed by the distribution, never taken from journal input.
     */
    fun recover() {
        if (!exists(journal)) {
            require(!exists(transaction)) { "이전 임시 디렉터리를 확인해야 합니다: $transaction" }
            return
        }
        require(Files.isRegularFile(journal, NOFOLLOW_LINKS) && Files.size(journal) <= 128)
        val parts = Files.newInputStream(journal, NOFOLLOW_LINKS).use { input ->
            val bytes = ByteArray(129)
            var length = 0
            while (length < bytes.size) {
                val next = input.read(bytes, length, bytes.size - length)
                if (next < 0) break
                length += next
            }
            require(length <= 128)
            String(bytes, 0, length, Charsets.UTF_8).split('\n')
        }
        require(parts.size == 2 && parts[0] == layout.distribution.name &&
            parts[1] in setOf("STAGING", "PUBLISHING", "COMMITTED")) {
            "다른 배포판 또는 알 수 없는 이전 기록입니다. 해당 배포판에서 이전을 다시 실행하세요"
        }
        require(!exists(transaction) || Files.isDirectory(transaction, NOFOLLOW_LINKS))
        if (parts[1] == "PUBLISHING") roots.indices.reversed().forEach { index ->
            if (exists(backup(index))) {
                if (exists(roots[index])) {
                    if (exists(stage(index))) deleteRootlessTree(stage(index))
                    Files.move(roots[index], stage(index), ATOMIC_MOVE)
                }
                Files.move(backup(index), roots[index], ATOMIC_MOVE)
            }
        }
        if (parts[1] == "STAGING") require(roots.indices.none { exists(backup(it)) })
        if (parts[1] == "COMMITTED") require(roots.all { Files.isDirectory(it, NOFOLLOW_LINKS) } &&
            RootlessStorageFormat.usesNativeLinks(roots[0]))
        restorePermissions()
        roots.forEach { syncDirectory(it.parent) }
        cleanup()
    }

    private fun directoryMode(path: Path) = (Files.getAttribute(path, "unix:mode", NOFOLLOW_LINKS) as Int) and 4095
    private fun setDirectoryMode(path: Path, mode: Int) {
        require(Files.isDirectory(path, NOFOLLOW_LINKS))
        // NOFOLLOW chmod opens the directory on some providers and fails for mode 000.
        // The caller excludes writers; verify the node before using path-based chmod.
        Files.setAttribute(path, "unix:mode", mode)
    }

    private fun savePermissions() {
        val text = originalModes.entries.joinToString("\n") { (path, mode) ->
            val index = roots.indexOfFirst { path.startsWith(it) }
            val relative = roots[index].relativize(path).toString()
            "$index\t${Base64.getEncoder().encodeToString(relative.toByteArray(Charsets.UTF_8))}\t${mode}"
        }
        val temporary = transaction.resolve("permissions.tmp")
        FileOutputStream(temporary.toFile()).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        Files.move(temporary, permissionJournal, ATOMIC_MOVE, REPLACE_EXISTING)
        syncDirectory(transaction)
    }

    private fun restorePermissions() {
        if (!exists(permissionJournal)) return
        require(Files.isRegularFile(permissionJournal, NOFOLLOW_LINKS))
        val modes = Files.readAllLines(permissionJournal).map { line ->
            val fields = line.split('\t')
            require(fields.size == 3)
            val root = roots[fields[0].toInt()]
            val relative = java.nio.file.Paths.get(String(Base64.getDecoder().decode(fields[1]), Charsets.UTF_8))
            require(!relative.isAbsolute && relative.none { it.toString() == ".." })
            root.resolve(relative) to fields[2].toInt().also { require(it in 0..4095) }
        }.sortedBy { it.first.nameCount }
        // Reopen parents first: a previous recovery may itself have been interrupted.
        modes.forEach { (path, mode) ->
            val root = roots.first { path.startsWith(it) }
            var component = root
            require(Files.isDirectory(component, NOFOLLOW_LINKS))
            root.relativize(path).forEach { segment ->
                component = component.resolve(segment)
                require(Files.isDirectory(component, NOFOLLOW_LINKS))
            }
            setDirectoryMode(path, mode or accessible)
        }
        modes.asReversed().forEach { (path, mode) ->
            java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ).use { channel ->
                setDirectoryMode(path, mode)
                channel.force(true)
            }
        }
    }

    private fun state(value: String) {
        val temporary = parent.resolve("$JOURNAL.tmp")
        Files.deleteIfExists(temporary)
        Files.newOutputStream(temporary, java.nio.file.StandardOpenOption.CREATE_NEW).use {
            it.write("${layout.distribution.name}\n$value".toByteArray())
        }
        FileOutputStream(temporary.toFile(), true).use { it.fd.sync() }
        Files.move(temporary, journal, ATOMIC_MOVE, REPLACE_EXISTING)
        syncDirectory(parent)
    }

    private fun cleanup() {
        if (exists(transaction)) deleteRootlessTree(transaction)
        Files.deleteIfExists(journal)
        syncDirectory(parent)
    }

    private fun syncDirectory(path: Path) {
        java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ).use { it.force(true) }
    }

    companion object {
        private const val JOURNAL = ".native-migration-state"
        private const val TRANSACTION = ".native-migration-trees"
        fun pendingOwner(rootlessParent: Path): RootlessDistribution? = runCatching {
            val journal = rootlessParent.resolve(JOURNAL)
            require(Files.isRegularFile(journal, NOFOLLOW_LINKS) && Files.size(journal) <= 128)
            val name = Files.newInputStream(journal, NOFOLLOW_LINKS).bufferedReader().use { reader ->
                val text = CharArray(128)
                val length = reader.read(text)
                if (length < 0) "" else String(text, 0, length).substringBefore('\n')
            }
            RootlessDistribution.entries.firstOrNull { it.name == name }
        }.getOrNull()

        fun pending(rootlessParent: Path) = Files.exists(rootlessParent.resolve(JOURNAL), NOFOLLOW_LINKS) ||
            Files.exists(rootlessParent.resolve(TRANSACTION), NOFOLLOW_LINKS)
    }
}
