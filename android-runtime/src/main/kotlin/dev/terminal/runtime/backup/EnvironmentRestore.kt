package dev.terminal.runtime.backup

import dev.terminal.runtime.rootless.*
import dev.terminal.runtime.session.*
import java.io.File
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID

/** Filesystem promotion and registry registration share one recoverable transaction.
 * A pending UUID is excluded from discovery and launches in every app process.
 */
class EnvironmentRestore(
    private val files: File,
    private val registry: EnvironmentRegistry,
    private val defaults: List<SessionProfile> = emptyList(),
    private val checkCancellation: () -> Unit = {},
    private val progress: (BackupProgress) -> Unit = {},
    private val checkpoint: (String) -> Unit = {},
    private val archive: TermBackupArchive = TermBackupArchive(checkCancellation, progress),
) {
    private val stagingRoot = File(files, ".environment-restores").toPath()
    fun restore(operationId: String, input: InputStream, password: CharArray, architecture: String,
        importProfiles: Boolean, requestedName: String? = null): Environment {
        UUID.fromString(operationId)
        Files.createDirectories(stagingRoot)
        require(stagingRoot.toRealPath() == stagingRoot.toAbsolutePath().normalize())
        val staging = Files.createDirectory(stagingRoot.resolve(operationId))
        Files.setAttribute(staging, "unix:mode", 448) // 0700; backup data never leaves app-private storage.
        var environment: Environment? = null
        try {
            val manifest = archive.extract(input, password, staging, architecture)
            validateContents(staging, manifest)
            environment = Environment.create(manifest.source.sessionRuntime,
                requestedName?.takeIf(String::isNotBlank) ?: manifest.source.name)
            val restored = environment
            rebaseEmulatedLinks(staging, SessionStorage.root(files, restored.sessionRuntime, restored.storage.id).toPath())
            val profiles = if (importProfiles) manifest.profiles.map { profile ->
                profile.copy(id = UUID.randomUUID().toString(), environmentId = restored.id,
                    storageId = restored.storage.id, runtime = restored.sessionRuntime,
                    // Imported image bytes are not part of this format; retain built-in icons.
                    icon = profile.icon.takeUnless { it.startsWith("image:") } ?: "auto",
                    workingDirectory = remapNativePath(profile.workingDirectory, manifest, restored),
                    shell = remapNativePath(profile.shell, manifest, restored),
                    shellArguments = profile.shellArguments.map { remapNativePath(it, manifest, restored) },
                    environment = profile.environment.mapValues { remapNativePath(it.value, manifest, restored) })
            } else emptyList()
            checkCancellation()
            registry.transaction(defaults) { reserveRestore(operationId, restored) }
            syncDirectory(files.toPath())
            checkpoint("reserved")
            checkCancellation()
            progress(BackupProgress("ACTIVATING", manifest.bytes, manifest.bytes, manifest.entries))
            return registry.transaction(defaults) {
                checkCancellation() // Last cancellation boundary; publication is one indivisible commit.
                activateRestore(operationId, profiles) { current ->
                    val destination = SessionStorage.root(files, current.sessionRuntime, current.storage.id).toPath()
                    Files.createDirectories(destination.parent)
                    require(destination.parent.toRealPath() == destination.parent.toAbsolutePath().normalize())
                    require(Files.notExists(destination, NOFOLLOW_LINKS))
                    Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE)
                    syncDirectory(destination.parent)
                    checkpoint("promoted")
                }
            }.also { syncDirectory(files.toPath()); checkpoint("committed") }
        } catch (failure: Exception) {
            // Publication may have completed just before cancellation or a progress-client failure.
            return registry.transaction(defaults) { completedRestore(operationId) } ?: throw failure
        } finally {
            // A late cancellation/failure after metadata commit must never remove an activated environment.
            val committed = environment?.let { value -> registry.transaction(defaults) { find(value.id) != null } } == true
            if (!committed) {
                deletePrivateTree(staging)
                val pending = registry.storage.transaction { pendingRestores()[operationId] }
                if (pending != null) {
                    deletePrivateTree(SessionStorage.root(files, pending.sessionRuntime, pending.storage.id).toPath())
                    registry.storage.transaction { abandonRestore(operationId) }
                }
            }
        }
    }

    /** Invoke only in the single runtime owner before admitting new backup/restore work. */
    fun recover() {
        val pending = registry.storage.transaction { pendingRestores() }
        pending.forEach { (operation, environment) ->
            val registered = registry.transaction(defaults) { find(environment.id) }
            if (registered == null) deletePrivateTree(SessionStorage.root(files, environment.sessionRuntime, environment.storage.id).toPath())
            deletePrivateTree(stagingRoot.resolve(operation))
            registry.storage.transaction { abandonRestore(operation) }
        }
        if (Files.isDirectory(stagingRoot, NOFOLLOW_LINKS)) Files.newDirectoryStream(stagingRoot).use { stream ->
            stream.forEach { path ->
                if (runCatching { UUID.fromString(path.fileName.toString()) }.isSuccess) deletePrivateTree(path)
            }
        }
    }

    private fun validateContents(staging: Path, manifest: BackupManifest) {
        require(Files.isDirectory(staging.resolve("home"), NOFOLLOW_LINKS)) { "Backup home is missing" }
        if (manifest.source.runtime == EnvironmentRuntime.ROOTLESS) {
            // Pin link semantics when the user later consents to installing the base distribution.
            Files.write(staging.resolve(".terminal-data-storage-format"), (manifest.storageFormat + "\n").toByteArray(Charsets.UTF_8))
        }
        if (manifest.kind != BackupKind.FULL) return
        val root = staging.resolve("rootfs")
        val actual = when (RootlessStorageFormat.resolveForLaunch(root)) {
            RootlessStorageFormat.LaunchFormat.NATIVE -> "native-hardlinks-1"
            RootlessStorageFormat.LaunchFormat.EMULATED -> "emulated-hardlinks-1"
            RootlessStorageFormat.LaunchFormat.LEGACY_EMULATED -> "legacy-emulated-hardlinks"
        }
        require(actual == manifest.storageFormat) { "Rootless storage marker does not match the backup manifest" }
        val distribution = RootlessDistribution.entries.single { it.runtime == manifest.source.sessionRuntime }
        RootlessDebianLayout(staging.toFile(), distribution, staging.resolve("workspace").toFile()).validateInstalledRootfs().getOrThrow()
        for (executable in listOf("bin/sh") + distribution.packageExecutables.take(1)) {
            val file = resolveRootlessFile(root.toFile(), executable) ?: error("Unsafe Rootless executable path")
            val header = ByteArray(20)
            java.io.DataInputStream(Files.newInputStream(file.toPath(), NOFOLLOW_LINKS)).use { it.readFully(header) }
            require(header.take(4) == listOf(127, 69, 76, 70).map(Int::toByte) && header[4] == 2.toByte() && header[5] == 1.toByte() &&
                header[18] == 183.toByte() && header[19] == 0.toByte()) { "Rootless executable architecture does not match ARM64" }
        }
        val os = readRootlessFile(root.toFile(), "etc/os-release") ?: error("Distribution identity is missing")
        val id = Regex("(?m)^ID=[\"']?([a-z0-9_-]+)[\"']?$").find(os)?.groupValues?.get(1)
        val expected = when (manifest.source.distribution) {
            EnvironmentDistribution.DEBIAN -> "debian"
            EnvironmentDistribution.UBUNTU -> "ubuntu"
            EnvironmentDistribution.ARCH -> "archarm"
            else -> error("Unsupported distribution")
        }
        require(id == expected || manifest.source.distribution == EnvironmentDistribution.ARCH && id == "arch") { "Backup distribution does not match its rootfs" }
        val database = root.resolve(if (manifest.source.distribution == EnvironmentDistribution.ARCH) "var/lib/pacman/local" else "var/lib/dpkg/status")
        require(if (manifest.source.distribution == EnvironmentDistribution.ARCH) Files.isDirectory(database, NOFOLLOW_LINKS)
            else Files.isRegularFile(database, NOFOLLOW_LINKS)) { "Package database is missing" }
    }

    private fun remapNativePath(value: String, manifest: BackupManifest, environment: Environment): String {
        if (manifest.source.runtime != EnvironmentRuntime.NATIVE) return value
        // Only recognized source layout prefixes are rewritten. Commands are user configuration, not evaluated here.
        val old = SessionStorage.root(files, manifest.source.sessionRuntime, manifest.source.storage.id).absolutePath
        val fresh = SessionStorage.root(files, environment.sessionRuntime, environment.storage.id).absolutePath
        if (value == old || value.startsWith("$old/")) return fresh + value.removePrefix(old)
        val oldLayout = if (manifest.sourceLegacyLayout) "native" else "profile-storage/${manifest.source.sessionRuntime.wireName}/${manifest.source.storage.id}"
        val match = Regex("^/data/(?:user/[0-9]+|data)/[^/]+/files/" + Regex.escape(oldLayout) + "(?=/|$)").find(value)
        return if (match != null) fresh + value.removePrefix(match.value) else value
    }

    /** PRoot bookkeeping requires absolute host .l2s links. Rebase only validated chains to the
     * final unpublished UUID path, then rename the tree. Ordinary symlinks remain relative.
     */
    private fun rebaseEmulatedLinks(staging: Path, destination: Path) {
        fun visit(path: Path) {
            checkCancellation()
            if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
                val mode = Files.getAttribute(path, "unix:mode", NOFOLLOW_LINKS) as Int
                val time = Files.getLastModifiedTime(path, NOFOLLOW_LINKS)
                try {
                    Files.setAttribute(path, "unix:mode", mode or 448)
                    Files.newDirectoryStream(path).use { entries -> entries.forEach(::visit) }
                    syncDirectory(path)
                } finally { Files.setLastModifiedTime(path, time); Files.setAttribute(path, "unix:mode", mode) }
            } else if (Files.isSymbolicLink(path)) {
                val relative = Files.readSymbolicLink(path)
                val target = path.parent.resolve(relative).normalize()
                val name = target.fileName?.toString().orEmpty()
                if (name.startsWith(".l2s.") || name.startsWith(".proot.l2s.")) {
                    require(target.startsWith(staging))
                    val time = Files.getLastModifiedTime(path, NOFOLLOW_LINKS)
                    Files.delete(path)
                    Files.createSymbolicLink(path, destination.resolve(staging.relativize(target)))
                    setSymbolicLinkTime(path, time)
                }
            }
        }
        visit(staging)
    }

    companion object {
        internal fun syncDirectory(path: Path) { FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) } }
        internal fun deletePrivateTree(path: Path) {
            if (Files.notExists(path, NOFOLLOW_LINKS)) return
            require(path.parent.toRealPath() == path.parent.toAbsolutePath().normalize()) { "Unsafe restore cleanup parent" }
            fun remove(entry: Path) {
                if (Files.isDirectory(entry, NOFOLLOW_LINKS)) {
                    Files.setAttribute(entry, "unix:mode", (Files.getAttribute(entry, "unix:mode", NOFOLLOW_LINKS) as Int) or 448)
                    Files.newDirectoryStream(entry).use { children -> children.forEach(::remove) }
                }
                Files.delete(entry)
            }
            remove(path)
        }
    }
}
