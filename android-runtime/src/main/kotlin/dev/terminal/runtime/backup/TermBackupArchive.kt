package dev.terminal.runtime.backup

import dev.terminal.runtime.session.EnvironmentRuntime
import java.io.*
import java.nio.file.*
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import org.json.JSONObject

/** TERM v1: bounded binary records in gzip, inside authenticated (or plain) streaming frames.
 * No tar/PAX paths, device nodes, serialized objects, executables or archive-selected host roots.
 * Extraction never follows links; all links are created after file data and checksum verification.
 */
class TermBackupArchive(
    private val checkCancellation: () -> Unit = {},
    private val progress: (BackupProgress) -> Unit = {},
    // Android's Unix provider rejects getFileStore(); File delegates to the supported statvfs path.
    private val availableBytes: (Path) -> Long = { it.toFile().usableSpace },
    private val metadataLimit: Long = BackupLimits.MAX_METADATA,
) {
    data class Source(val roots: Map<String, Path>, val included: Set<String>, val rootless: Boolean,
        /** Only old-format fixtures/sources had a special guest /workspace mount. */
        val legacyWorkspaceBinding: Boolean = false) {
        init {
            require(roots.keys == setOf("home", "rootfs", "workspace"))
            require("home" in included && included.all { it in roots })
        }
    }
    data class Inventory(val bytes: Long, val entries: Int)

    fun inventory(source: Source): Inventory {
        var bytes = 0L
        var entries = 0
        val inodes = hashSetOf<Any>()
        walk(source) { path, _, attrs ->
            require(++entries <= BackupLimits.MAX_ENTRIES) { "Too many backup entries" }
            if (attrs.isRegularFile && inodes.add(identity(path, attrs))) bytes = Math.addExact(bytes, attrs.size())
            require(bytes <= BackupLimits.MAX_BYTES) { "Backup exceeds the size limit" }
            progress(BackupProgress("SCANNING", bytes, entries = entries))
        }
        return Inventory(bytes, entries)
    }

    fun write(output: OutputStream, password: CharArray, manifest: BackupManifest, source: Source,
        beforeFinish: () -> Unit = {}) {
        require(("rootfs" in source.included) == (manifest.kind == BackupKind.FULL))
        require(("workspace" in source.included) == (manifest.workspace != BackupWorkspace.NONE))
        BackupTransport.Writer(output, password).use { transport ->
            val gzip = GZIPOutputStream(transport, BackupLimits.BUFFER)
            val hash = MessageDigest.getInstance("SHA-256").apply { update(transport.fingerprint) }
            val digest = DigestOutputStream(gzip, hash)
            val data = DataOutputStream(digest)
            writeText(data, manifest.toJson().toString(), BackupLimits.MAX_MANIFEST)
            val inodes = hashMapOf<Any, String>()
            val emittedFiles = hashSetOf<String>()
            val symbolic = hashMapOf<String, String>()
            val budget = MetadataBudget(metadataLimit)
            var completed = 0L
            var entries = 0
            walk(source) { path, name, attrs ->
                checkCancellation()
                require(++entries <= manifest.entries) { "Environment changed during backup" }
                budget.entry(name)
                val mode = (Files.getAttribute(path, "unix:mode", NOFOLLOW_LINKS) as Int) and 4095
                val existing = if (attrs.isRegularFile) inodes.putIfAbsent(identity(path, attrs), name) else null
                val type = when {
                    attrs.isDirectory -> DIRECTORY
                    attrs.isSymbolicLink -> SYMLINK
                    attrs.isRegularFile -> if (existing == null) FILE else HARDLINK
                    else -> error("Unsupported special file in backup: $name")
                }
                data.writeByte(type); writeText(data, name)
                data.writeInt(mode); data.writeLong(attrs.lastModifiedTime().toMillis())
                when (type) {
                    SYMLINK -> {
                        val target = relocateLink(source, path)
                        budget.text(target)
                        symbolic[name] = target
                        writeText(data, target)
                    }
                    HARDLINK -> { emittedFiles += name; budget.text(requireNotNull(existing)); writeText(data, existing) }
                    FILE -> {
                        emittedFiles += name
                        val size = attrs.size()
                        require(size >= 0 && size <= manifest.bytes - completed) { "Environment grew during backup" }
                        data.writeLong(size)
                        if (size > 0) Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
                            copyExactly(input, data, size) { amount ->
                                completed += amount
                                progress(BackupProgress("WRITING", completed, manifest.bytes, entries))
                            }
                            require(input.read() == -1) { "File grew during backup: $name" }
                        }
                        val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                        require(after.isRegularFile && after.fileKey() == attrs.fileKey() && after.size() == size &&
                            after.lastModifiedTime() == attrs.lastModifiedTime()) { "File changed during backup: $name" }
                    }
                }
            }
            require(entries == manifest.entries && completed == manifest.bytes) { "Environment changed during backup" }
            // A .l2s chain must be wholly contained in the selected data; dangling user links are allowed.
            validateEmulatedLinks(symbolic, emittedFiles)
            checkCancellation(); beforeFinish(); checkCancellation()
            data.writeByte(END); data.flush()
            digest.on(false); data.write(hash.digest()); data.flush()
            gzip.finish(); transport.finish(); gzip.close()
        }
    }

    /** Preview is bounded and unauthoritative until extract verifies the complete archive. */
    fun inspect(input: InputStream, password: CharArray): BackupManifest =
        BackupTransport.Reader(input, password).use { transport ->
            gzip(transport).use { compressed -> readManifest(DataInputStream(compressed)) }
        }

    fun extract(input: InputStream, password: CharArray, destination: Path, hostArchitecture: String): BackupManifest {
        require(Files.isDirectory(destination, NOFOLLOW_LINKS) && destination.toRealPath() == destination.toAbsolutePath().normalize())
        require(Files.newDirectoryStream(destination).use { !it.iterator().hasNext() }) { "Restore staging must be empty" }
        val root = destination.toAbsolutePath().normalize()
        BackupTransport.Reader(input, password).use { transport ->
            gzip(transport).use { compressed ->
                val hash = MessageDigest.getInstance("SHA-256").apply { update(transport.fingerprint) }
                val digest = DigestInputStream(compressed, hash)
                val data = DataInputStream(digest)
                val manifest = readManifest(data).also { it.checkCompatibility(hostArchitecture) }
                require(manifest.bytes <= availableBytes(root) - BackupLimits.RESERVE) { "Not enough storage for restore" }
                if (manifest.storageFormat == "native-hardlinks-1") probeHardlinks(root)
                val paths = hashSetOf<String>()
                val regular = hashSetOf<String>()
                val links = mutableListOf<Link>()
                val symbolic = hashMapOf<String, String>()
                val metadata = mutableListOf<Metadata>()
                val budget = MetadataBudget(metadataLimit)
                var completed = 0L
                var count = 0
                while (true) {
                    checkCancellation()
                    val type = data.readUnsignedByte()
                    if (type == END) break
                    require(type in DIRECTORY..HARDLINK && ++count <= manifest.entries) { "Invalid or excessive backup entries" }
                    val name = readText(data)
                    budget.entry(name)
                    val target = target(root, name, manifest)
                    require(paths.add(name)) { "Duplicate archive path" }
                    val mode = data.readInt().also { require(it in 0..4095) { "Invalid file permissions" } }
                    val time = data.readLong().also { require(it >= 0) { "Invalid file timestamp" } }
                    noSymlinkParents(root, target.parent)
                    Files.createDirectories(target.parent)
                    when (type) {
                        DIRECTORY -> {
                            require(Files.notExists(target, NOFOLLOW_LINKS) || Files.isDirectory(target, NOFOLLOW_LINKS))
                            Files.createDirectories(target)
                            metadata += Metadata(target, mode, time)
                        }
                        FILE -> {
                            val size = data.readLong()
                            require(size >= 0 && size <= manifest.bytes - completed) { "Expanded backup exceeds its declared size" }
                            require(size <= availableBytes(root) - BackupLimits.RESERVE) { "Not enough storage for restore" }
                            FileOutputStream(Files.createFile(target).toFile()).use { output ->
                                copyExactly(data, output, size) { amount ->
                                    completed += amount
                                    progress(BackupProgress("EXTRACTING", completed, manifest.bytes, count))
                                }
                                output.fd.sync()
                            }
                            regular += name
                            metadata += Metadata(target, mode, time)
                        }
                        SYMLINK, HARDLINK -> {
                            require(Files.notExists(target, NOFOLLOW_LINKS)) { "Link collides with an archive directory" }
                            val sourceName = readText(data)
                            budget.text(sourceName)
                            val source = linkTarget(root, sourceName)
                            if (type == HARDLINK) require(sourceName in regular) { "Hardlink must reference a preceding regular file" }
                            else symbolic[name] = sourceName
                            links += Link(target, source, type == HARDLINK, mode, time)
                        }
                    }
                    progress(BackupProgress("EXTRACTING", completed, manifest.bytes, count))
                }
                require(count == manifest.entries && completed == manifest.bytes && "home" in paths &&
                    (manifest.kind != BackupKind.FULL || "rootfs" in paths) &&
                    (manifest.workspace == BackupWorkspace.NONE || "workspace" in paths)) { "Incomplete backup contents" }
                digest.on(false)
                val expected = ByteArray(32).also(data::readFully)
                require(MessageDigest.isEqual(expected, hash.digest())) { "Backup integrity check failed" }
                require(data.read() == -1) { "Unexpected expanded data after backup checksum" }
                transport.verifyEnd()
                validateEmulatedLinks(symbolic, regular)
                progress(BackupProgress("VERIFYING", completed, manifest.bytes, count))
                val metadataByPath = metadata.associateBy { it.path }
                links.forEach { link ->
                    checkCancellation()
                    noSymlinkParents(root, link.target.parent)
                    require(Files.notExists(link.target, NOFOLLOW_LINKS)) { "Link path collides with extracted data" }
                    if (link.hard) {
                        noSymlinkParents(root, link.source.parent)
                        require(Files.isRegularFile(link.source, NOFOLLOW_LINKS))
                        val original = metadataByPath.getValue(link.source)
                        require(link.mode == original.mode && link.time == original.time) { "Conflicting hardlink metadata" }
                        Files.createLink(link.target, link.source)
                    } else {
                        val from = root.relativize(link.target).first().toString()
                        val to = root.relativize(link.source).first().toString()
                        val relationship = if (manifest.source.runtime == EnvironmentRuntime.ROOTLESS && from != to && !specialLink(link.source)) {
                            // Component bind points have guest names (/root, /workspace, /), not host names.
                            guestPath(to, root.resolve(to).relativize(link.source))
                        } else link.target.parent.relativize(link.source)
                        Files.createSymbolicLink(link.target, relationship)
                        setSymbolicLinkTime(link.target, FileTime.fromMillis(link.time))
                    }
                }
                // No following of symlinks when verifying emulated-link chains or later rootfs identity.
                links.filter { !it.hard && specialLink(it.source) }.forEach {
                    require(it.source.startsWith(root) && Files.exists(it.source, NOFOLLOW_LINKS)) { "Missing emulated hardlink data" }
                }
                metadata.sortedByDescending { it.path.nameCount }.forEach {
                    checkCancellation()
                    Files.setLastModifiedTime(it.path, FileTime.fromMillis(it.time))
                    Files.setAttribute(it.path, "unix:mode", it.mode)
                }
                checkCancellation()
                return manifest
            }
        }
    }

    private fun walk(source: Source, visit: (Path, String, BasicFileAttributes) -> Unit) {
        source.included.sorted().forEach { component ->
            val base = source.roots.getValue(component).toAbsolutePath().normalize()
            require(Files.isDirectory(base, NOFOLLOW_LINKS) && base.toRealPath() == base) { "Backup source must be a real directory: $component" }
            Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    checkCancellation()
                    val name = listOf(component, base.relativize(dir).toString()).filter { it.isNotEmpty() }.joinToString("/")
                    visit(dir, name, attrs)
                    return if (excluded(name)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    checkCancellation()
                    val name = "$component/${base.relativize(file)}"
                    if (!excluded(name)) visit(file, name, attrs)
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }

    private fun identity(path: Path, attrs: BasicFileAttributes): Any = attrs.fileKey() ?: path

    private fun validateEmulatedLinks(symbolic: Map<String, String>, regular: Set<String>) {
        val members = symbolic.filter { (_, target) -> specialLink(Paths.get(target)) && target in symbolic }
            .entries.groupBy({ it.value }, { it.key })
        members.forEach { (intermediate, aliases) ->
            val data = symbolic.getValue(intermediate)
            require(data in regular && data.substringBeforeLast('/') == intermediate.substringBeforeLast('/') &&
                data.startsWith("$intermediate.") && data.removePrefix("$intermediate.").matches(Regex("[0-9]{4,6}")) &&
                data.removePrefix("$intermediate.").toInt() == aliases.size) {
                "An emulated hardlink group is incomplete; include its Workspace or use a full backup"
            }
        }
        val aliases = members.values.flatten().toSet()
        symbolic.filterValues { specialLink(Paths.get(it)) }.keys.forEach {
            require(it in aliases || it in members) { "Missing or invalid emulated hardlink chain" }
        }
    }

    private fun relocateLink(source: Source, path: Path): String {
        val link = Files.readSymbolicLink(path)
        fun guestTarget(guest: Path): String {
            val normalized = guest.normalize().toString()
            val component = when {
                normalized == "/root" || normalized.startsWith("/root/") -> "home"
                source.legacyWorkspaceBinding && (normalized == "/workspace" || normalized.startsWith("/workspace/")) -> "workspace"
                else -> "rootfs"
            }
            val prefix = when (component) { "home" -> "/root"; "workspace" -> "/workspace"; else -> "" }
            return listOf(component, normalized.removePrefix(prefix).removePrefix("/")).filter(String::isNotEmpty).joinToString("/")
        }
        if (source.rootless && !link.isAbsolute) {
            val (component, base) = source.roots.entries.first { path.startsWith(it.value) }
            if (component != "workspace" || source.legacyWorkspaceBinding)
                return guestTarget(guestPath(component, base.relativize(path)).parent.resolve(link))
        }
        val host = (if (link.isAbsolute) link else path.parent.resolve(link)).normalize()
        fun mapped(candidate: Path): String? = source.roots.entries.firstOrNull { candidate.startsWith(it.value) }?.let { (name, root) ->
            listOf(name, root.relativize(candidate).toString()).filter { it.isNotEmpty() }.joinToString("/")
        }
        mapped(host)?.let { return it }
        // /data/data and /data/user/0 aliases: canonicalize the parent, never the final link.
        if (link.isAbsolute) runCatching { host.parent.toRealPath().resolve(host.fileName) }.getOrNull()?.let {
            mapped(it)?.let { target -> return target }
        }
        if (source.rootless && link.isAbsolute && !link.startsWith("/data") && !link.startsWith("/storage") && !link.startsWith("/sdcard")) {
            return guestTarget(link)
        }
        error("Symbolic link points outside this environment: ${path.fileName}")
    }

    private fun target(root: Path, name: String, manifest: BackupManifest): Path {
        val target = linkTarget(root, name)
        val component = name.substringBefore('/')
        require(component == "home" || component == "rootfs" && manifest.kind == BackupKind.FULL ||
            component == "workspace" && manifest.workspace != BackupWorkspace.NONE) { "Unexpected backup component" }
        return target
    }
    private fun linkTarget(root: Path, name: String): Path {
        require(name.isNotEmpty() && name.length <= BackupLimits.MAX_TEXT && !name.startsWith('/') && '\u0000' !in name &&
            '\\' !in name && name.split('/').size <= 128 && name.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "Unsafe backup path" }
        require(name.substringBefore('/') in setOf("home", "rootfs", "workspace")) { "Unsafe link component" }
        return root.resolve(name).normalize().also { require(it.startsWith(root)) }
    }
    private fun noSymlinkParents(root: Path, parent: Path) {
        require(parent.startsWith(root))
        var part = root
        root.relativize(parent).forEach { suffix ->
            part = part.resolve(suffix)
            require(!Files.isSymbolicLink(part)) { "Archive path traverses a symlink" }
        }
    }
    private fun copyExactly(input: InputStream, output: OutputStream, size: Long, copied: (Int) -> Unit) {
        val buffer = ByteArray(BackupLimits.BUFFER)
        var left = size
        while (left > 0) {
            checkCancellation()
            val count = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (count < 0) throw EOFException("Truncated backup file data")
            if (count == 0) continue
            output.write(buffer, 0, count); left -= count; copied(count)
        }
    }
    private fun readManifest(input: DataInputStream): BackupManifest = BackupManifest.fromJson(JSONObject(readText(input, BackupLimits.MAX_MANIFEST)))
    private fun writeText(output: DataOutputStream, value: String, max: Int = BackupLimits.MAX_TEXT) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= max) { "Backup text exceeds limit" }
        output.writeInt(bytes.size); output.write(bytes)
    }
    private fun readText(input: DataInputStream, max: Int = BackupLimits.MAX_TEXT): String {
        val size = input.readInt()
        require(size in 0..max) { "Invalid backup text length" }
        val bytes = ByteArray(size).also(input::readFully)
        return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }
    private fun gzip(input: InputStream): InputStream = BackupGzipInputStream(input)
    private fun probeHardlinks(root: Path) {
        val original = Files.createFile(root.resolve(".link-probe"))
        val linked = root.resolve(".link-probe-copy")
        try { Files.createLink(linked, original) }
        catch (failure: IOException) { throw IOException("This device cannot preserve native hardlinks", failure) }
        finally { Files.deleteIfExists(linked); Files.deleteIfExists(original) }
    }
    private data class Link(val target: Path, val source: Path, val hard: Boolean, val mode: Int, val time: Long)
    private data class Metadata(val path: Path, val mode: Int, val time: Long)
    /** Bound attacker-controlled identity/link maps as well as file expansion. The fixed charge
     * accounts for per-entry objects; four bytes per UTF-16 unit accounts for retained path copies.
     */
    private class MetadataBudget(private val maximum: Long) {
        private var used = 0L
        fun entry(name: String) { add(512); text(name) }
        fun text(value: String) = add(value.length.toLong() * 4)
        private fun add(amount: Long) { used += amount; require(used <= maximum) { "Backup metadata exceeds its safety limit" } }
    }
    companion object {
        private fun guestPath(component: String, relative: Path): Path =
            Paths.get(when (component) { "home" -> "/root"; "workspace" -> "/workspace"; else -> "/" }).resolve(relative)
        private const val END = 0
        private const val DIRECTORY = 1
        private const val FILE = 2
        private const val SYMLINK = 3
        private const val HARDLINK = 4
        private fun specialLink(path: Path) = path.fileName?.toString().orEmpty().let { it.startsWith(".l2s.") || it.startsWith(".proot.l2s.") }
        private fun excluded(name: String): Boolean {
            val volatile = listOf("rootfs/tmp", "rootfs/var/tmp", "rootfs/run", "rootfs/proc", "rootfs/sys", "rootfs/dev",
                "rootfs/var/cache", "rootfs/var/lib/apt/lists", "home/.cache", "home/.npm/_cacache", "home/.cargo/registry/cache",
                "home/.gradle/caches", "home/.local/share/Trash")
            return volatile.any { name == it || name.startsWith("$it/") }
        }
    }
}
