package dev.terminal.runtime.backup

import dev.terminal.runtime.session.*
import java.io.*
import java.nio.file.*
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EnvironmentBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private data class Fixture(val files: File, val registry: EnvironmentRegistry, val environment: Environment,
        val source: TermBackupArchive.Source, val kind: BackupKind, val workspace: BackupWorkspace)

    private fun fixture(runtime: SessionRuntime = SessionRuntime.NATIVE, full: Boolean = false,
        workspace: Boolean = false, emulated: Boolean = false): Fixture {
        val files = temporary.newFolder()
        val registry = EnvironmentRegistry(files)
        val environment = Environment.create(runtime, "Development")
        registry.transaction { save(SessionProfile("source-profile", "Shell", runtime,
            if (runtime == SessionRuntime.NATIVE) "/system/bin/sh" else "/bin/sh", environmentId = environment.id), environment) }
        val root = registry.storage.root(environment.storageKey).toPath()
        val roots = listOf("home", "rootfs", "workspace").associateWith { Files.createDirectories(root.resolve(it)) }
        write(roots.getValue("home"), ".profile", "export TEST=1\n")
        write(roots.getValue("home"), "private", "secret")
        Files.setAttribute(roots.getValue("home").resolve("private"), "unix:mode", 384) // 0600
        write(roots.getValue("home"), ".cache/discard", "cache")
        write(roots.getValue("workspace"), "project/main", "workspace")
        if (full) {
            val rootfs = roots.getValue("rootfs")
            val distro = when (runtime) { SessionRuntime.ROOTLESS_UBUNTU -> "ubuntu"; SessionRuntime.ROOTLESS_ARCH -> "archarm"; else -> "debian" }
            write(rootfs, "etc/os-release", "ID=$distro\n")
            for (name in listOf("bin/sh", "usr/bin/apt", "usr/bin/apt-get", "usr/bin/dpkg", "usr/bin/pacman")) {
                val executable = write(rootfs, name, "")
                Files.write(executable, ByteArray(20).also { header ->
                    byteArrayOf(127, 69, 76, 70, 2, 1).copyInto(header); header[18] = 183.toByte()
                })
                Files.setAttribute(executable, "unix:mode", 493)
            }
            if (runtime == SessionRuntime.ROOTLESS_ARCH) write(rootfs, "var/lib/pacman/local/base-1/desc", "base package")
            else write(rootfs, "var/lib/dpkg/status", "Package: bash\nStatus: install ok installed\n")
            write(rootfs, "var/cache/packages", "discard")
            write(rootfs, "tmp/discard", "discard")
            write(rootfs, ".terminal-storage-format", if (emulated) "emulated-hardlinks-1\n" else "native-hardlinks-1\n")
        }
        val included = buildSet { add("home"); if (full) add("rootfs"); if (workspace) add("workspace") }
        return Fixture(files, registry, environment, TermBackupArchive.Source(roots, included, runtime != SessionRuntime.NATIVE, legacyWorkspaceBinding = true),
            if (full) BackupKind.FULL else BackupKind.DATA, if (workspace) BackupWorkspace.ENVIRONMENT else BackupWorkspace.NONE)
    }
    private fun write(root: Path, name: String, text: String): Path {
        val path = root.resolve(name); Files.createDirectories(path.parent)
        return Files.writeString(path, text)
    }
    private fun manifest(fixture: Fixture): BackupManifest {
        val inventory = TermBackupArchive().inventory(fixture.source)
        val format = when {
            fixture.environment.runtime == EnvironmentRuntime.NATIVE -> "native-data-1"
            fixture.kind == BackupKind.DATA -> "legacy-emulated-hardlinks"
            Files.readString(fixture.source.roots.getValue("rootfs").resolve(".terminal-storage-format")).startsWith("emulated") -> "emulated-hardlinks-1"
            else -> "native-hardlinks-1"
        }
        return BackupManifest(fixture.kind, fixture.environment, "arm64-v8a", "0.14.0-test", format, fixture.workspace,
            fixture.registry.transaction { profiles() }, inventory.bytes, inventory.entries)
    }
    private fun backup(fixture: Fixture, password: String = ""): ByteArray = ByteArrayOutputStream().also {
        TermBackupArchive().write(it, password.toCharArray(), manifest(fixture), fixture.source)
    }.toByteArray()
    private fun restore(fixture: Fixture, bytes: ByteArray, password: String = "", profiles: Boolean = true): Environment =
        EnvironmentRestore(fixture.files, fixture.registry).restore(UUID.randomUUID().toString(), ByteArrayInputStream(bytes),
            password.toCharArray(), "arm64-v8a", profiles)

    @Test fun nativeDataRoundTripPreservesModesLinksProfilesAndIsolation() {
        val fixture = fixture(workspace = true)
        val home = fixture.source.roots.getValue("home")
        Files.createLink(home.resolve("hard"), home.resolve("private"))
        Files.createSymbolicLink(home.resolve("symbolic"), Path.of("private"))
        val before = Files.readString(home.resolve("private"))
        val restored = restore(fixture, backup(fixture, "correct horse"), "correct horse")
        assertNotEquals(fixture.environment.id, restored.id)
        assertNotEquals(fixture.environment.storage.id, restored.storage.id)
        val root = fixture.registry.storage.root(restored.storageKey).toPath()
        assertEquals("secret", Files.readString(root.resolve("home/private")))
        assertEquals(384, (Files.getAttribute(root.resolve("home/private"), "unix:mode") as Int) and 4095)
        assertTrue(Files.isSameFile(root.resolve("home/private"), root.resolve("home/hard")))
        assertEquals(Path.of("private"), Files.readSymbolicLink(root.resolve("home/symbolic")))
        assertFalse(Files.exists(root.resolve("home/.cache/discard")))
        assertEquals("workspace", Files.readString(root.resolve("workspace/project/main")))
        assertEquals(before, Files.readString(home.resolve("private")))
        Files.writeString(root.resolve("home/private"), "restored only")
        assertEquals("secret", Files.readString(home.resolve("private")))
        fixture.registry.transaction {
            assertEquals(2, all().size)
            val imported = profiles().single { it.environmentId == restored.id }
            assertNotEquals("source-profile", imported.id)
            assertEquals(restored.storage.id, imported.storageId)
            assertEquals(restored, resolve(imported).executionEnvironment)
        }
    }

    @Test fun fullRoundTripsRetainEveryDistributionPackageDatabaseAndReadOnlyModes() {
        for (runtime in listOf(SessionRuntime.ROOTLESS, SessionRuntime.ROOTLESS_UBUNTU, SessionRuntime.ROOTLESS_ARCH)) {
            val fixture = fixture(runtime, full = true)
            Files.setAttribute(fixture.source.roots.getValue("rootfs"), "unix:mode", 365) // 0555
            val restored = restore(fixture, backup(fixture), profiles = false)
            val root = fixture.registry.storage.root(restored.storageKey).toPath()
            val database = if (runtime == SessionRuntime.ROOTLESS_ARCH) "var/lib/pacman/local/base-1/desc" else "var/lib/dpkg/status"
            assertEquals(Files.readString(fixture.source.roots.getValue("rootfs").resolve(database)), Files.readString(root.resolve("rootfs/$database")))
            assertEquals("native-hardlinks-1\n", Files.readString(root.resolve("rootfs/.terminal-storage-format")))
            assertEquals(365, (Files.getAttribute(root.resolve("rootfs"), "unix:mode") as Int) and 4095)
            assertFalse(Files.exists(root.resolve("rootfs/var/cache/packages")))
            assertFalse(Files.exists(root.resolve("rootfs/tmp/discard")))
            assertFalse(Files.exists(root.resolve("workspace")))
            assertTrue(fixture.registry.transaction { profiles().none { it.environmentId == restored.id } })
        }
    }

    @Test fun adoptedNativeProfilesRemapLegacyPathsAcrossAppInstallations() {
        val fixture = fixture()
        val sourceHome = "/data/user/0/old.term.app/files/native/home"
        val manifest = manifest(fixture).let { value -> value.copy(sourceLegacyLayout = true,
            profiles = value.profiles.map { it.copy(workingDirectory = sourceHome, environment = mapOf("HOME" to sourceHome)) }) }
        val bytes = ByteArrayOutputStream().also { TermBackupArchive().write(it, charArrayOf(), manifest, fixture.source) }.toByteArray()
        val restored = restore(fixture, bytes)
        val imported = fixture.registry.transaction { profiles().single { it.environmentId == restored.id } }
        val newHome = File(fixture.registry.storage.root(restored.storageKey), "home").absolutePath
        assertEquals(newHome, imported.workingDirectory)
        assertEquals(newHome, imported.environment["HOME"])
        assertNotEquals(fixture.environment.storage.id, imported.storageId)
    }

    @Test fun emulatedHardlinksAreRelocatedToTheNewAbsoluteStorageWithoutConversion() {
        val fixture = fixture(SessionRuntime.ROOTLESS, full = true, emulated = true)
        val root = fixture.source.roots.getValue("rootfs")
        val intermediate = root.resolve(".l2s.data0001")
        val data = write(root, ".l2s.data0001.0002", "linked package contents")
        Files.createSymbolicLink(intermediate, data)
        Files.createSymbolicLink(root.resolve("usr/bin/linked"), intermediate)
        Files.createSymbolicLink(root.resolve("usr/bin/linked2"), intermediate)
        val restored = restore(fixture, backup(fixture))
        val fresh = fixture.registry.storage.root(restored.storageKey).toPath().resolve("rootfs")
        assertEquals(fresh.resolve(".l2s.data0001"), Files.readSymbolicLink(fresh.resolve("usr/bin/linked")))
        assertEquals(fresh.resolve(".l2s.data0001.0002"), Files.readSymbolicLink(fresh.resolve(".l2s.data0001")))
        assertEquals("linked package contents", Files.readString(fresh.resolve("usr/bin/linked2")))
        assertEquals("emulated-hardlinks-1\n", Files.readString(fresh.resolve(".terminal-storage-format")))
        assertEquals(data, Files.readSymbolicLink(intermediate))
    }

    @Test fun omittedWorkspaceCannotBreakEmulatedLinkCountsOrLeakSharedData() {
        val fixture = fixture(SessionRuntime.ROOTLESS, full = true, emulated = true)
        val root = fixture.source.roots.getValue("rootfs")
        val intermediate = root.resolve(".l2s.data0001")
        Files.createSymbolicLink(intermediate, write(root, ".l2s.data0001.0002", "shared links"))
        Files.createSymbolicLink(root.resolve("alias"), intermediate)
        Files.createSymbolicLink(fixture.source.roots.getValue("workspace").resolve("alias"), intermediate)
        assertTrue(runCatching { backup(fixture) }.isFailure)
        assertEquals("workspace", Files.readString(fixture.source.roots.getValue("workspace").resolve("project/main")))
    }

    @Test fun rootlessDataRestoresAreUninstalledAndRetainHomeLinkPolicy() {
        val fixture = fixture(SessionRuntime.ROOTLESS_UBUNTU)
        val restored = restore(fixture, backup(fixture))
        val root = fixture.registry.storage.root(restored.storageKey).toPath()
        assertFalse(Files.exists(root.resolve("rootfs")))
        assertEquals("legacy-emulated-hardlinks\n", Files.readString(root.resolve(".terminal-data-storage-format")))
        assertEquals(EnvironmentDistribution.UBUNTU, restored.distribution)
    }

    @Test fun rootlessCrossComponentAndParentRelativeLinksRetainGuestPathMeaning() {
        val fixture = fixture(SessionRuntime.ROOTLESS, full = true, workspace = true)
        val home = fixture.source.roots.getValue("home")
        val rootfs = fixture.source.roots.getValue("rootfs")
        Files.createSymbolicLink(home.resolve("os"), Path.of("../etc/os-release"))
        Files.createSymbolicLink(home.resolve("project"), Path.of("/workspace/project/main"))
        Files.createSymbolicLink(rootfs.resolve("etc/private"), Path.of("/root/private"))
        val restored = restore(fixture, backup(fixture))
        val root = fixture.registry.storage.root(restored.storageKey).toPath()
        assertEquals(Path.of("/etc/os-release"), Files.readSymbolicLink(root.resolve("home/os")))
        assertEquals(Path.of("/workspace/project/main"), Files.readSymbolicLink(root.resolve("home/project")))
        assertEquals(Path.of("/root/private"), Files.readSymbolicLink(root.resolve("rootfs/etc/private")))
    }
    @Test fun ordinaryGuestWorkspaceIsPartOfRootfsAfterRetiringTheSpecialBinding() {
        val oldFixture = fixture(SessionRuntime.ROOTLESS, full = true, workspace = false)
        val fixture = oldFixture.copy(source = oldFixture.source.copy(legacyWorkspaceBinding = false))
        val rootfs = fixture.source.roots.getValue("rootfs")
        write(rootfs, "workspace/manual", "ordinary guest directory")
        Files.createSymbolicLink(fixture.source.roots.getValue("home").resolve("manual"), Path.of("/workspace/manual"))
        val restored = restore(fixture, backup(fixture))
        val root = fixture.registry.storage.root(restored.storageKey).toPath()
        assertEquals("ordinary guest directory", Files.readString(root.resolve("rootfs/workspace/manual")))
        assertEquals(Path.of("/workspace/manual"), Files.readSymbolicLink(root.resolve("home/manual")))
        assertFalse(Files.exists(root.resolve("workspace")))
    }

    @Test fun legacyWorkspaceRequiresAnExplicitComponentAndRestoresAsAPrivateCopy() {
        val files = temporary.newFolder()
        val registry = EnvironmentRegistry(files)
        val profile = SessionProfile(SessionProfile.builtInId(SessionRuntime.ROOTLESS), "Legacy Debian", SessionRuntime.ROOTLESS, "/bin/sh")
        val environment = registry.transaction(listOf(profile)) { all().single() }
        val root = SessionStorage.root(files, environment.sessionRuntime, environment.storage.id).toPath()
        val home = Files.createDirectories(root.resolve("home"))
        write(home, ".profile", "home-only")
        val shared = Files.createDirectories(files.toPath().resolve("workspace"))
        write(shared, "other-environment/project", "deliberately shared")
        val archive = TermBackupArchive()
        fun make(include: Boolean): ByteArray {
            val source = TermBackupArchive.Source(mapOf("home" to home, "rootfs" to root.resolve("rootfs"), "workspace" to shared),
                if (include) setOf("home", "workspace") else setOf("home"), true)
            val inventory = archive.inventory(source)
            val manifest = BackupManifest(BackupKind.DATA, environment, "arm64-v8a", "test", "legacy-emulated-hardlinks",
                if (include) BackupWorkspace.LEGACY_SHARED else BackupWorkspace.NONE, emptyList(), inventory.bytes, inventory.entries)
            return ByteArrayOutputStream().also { archive.write(it, charArrayOf(), manifest, source) }.toByteArray()
        }
        val excluded = EnvironmentRestore(files, registry, listOf(profile)).restore(UUID.randomUUID().toString(), make(false).inputStream(), charArrayOf(), "arm64-v8a", false)
        assertFalse(File(registry.storage.root(excluded.storageKey), "workspace").exists())
        val included = EnvironmentRestore(files, registry, listOf(profile)).restore(UUID.randomUUID().toString(), make(true).inputStream(), charArrayOf(), "arm64-v8a", false)
        val copy = File(registry.storage.root(included.storageKey), "workspace/other-environment/project")
        assertEquals("deliberately shared", copy.readText())
        copy.writeText("private restored copy")
        assertEquals("deliberately shared", Files.readString(shared.resolve("other-environment/project")))
    }

    @Test fun wrongPasswordCorruptionTruncationAndWrongArchitectureDoNotRegisterOrModifyEnvironments() {
        val fixture = fixture()
        val encrypted = backup(fixture, "password")
        val corrupted = encrypted.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        for ((bytes, password) in listOf(encrypted to "wrong", corrupted to "password", encrypted.copyOf(encrypted.size - 1) to "password")) {
            assertTrue(runCatching { restore(fixture, bytes, password) }.isFailure)
            assertEquals(listOf(fixture.environment), fixture.registry.transaction { all() })
        }
        assertTrue(runCatching { EnvironmentRestore(fixture.files, fixture.registry).restore(UUID.randomUUID().toString(),
            ByteArrayInputStream(encrypted), "password".toCharArray(), "x86_64", true) }.isFailure)
        assertEquals("secret", Files.readString(fixture.source.roots.getValue("home").resolve("private")))
    }

    @Test fun storageExhaustionAndCancellationCleanOnlyPrivateStaging() {
        val fixture = fixture()
        val bytes = backup(fixture)
        val operation = UUID.randomUUID().toString()
        val archive = TermBackupArchive(availableBytes = { 0 })
        assertTrue(runCatching { EnvironmentRestore(fixture.files, fixture.registry, archive = archive)
            .restore(operation, ByteArrayInputStream(bytes), charArrayOf(), "arm64-v8a", true) }.isFailure)
        val metadataBombGuard = TermBackupArchive(metadataLimit = 600)
        val excessiveMetadata = runCatching { EnvironmentRestore(fixture.files, fixture.registry, archive = metadataBombGuard)
            .restore(UUID.randomUUID().toString(), ByteArrayInputStream(bytes), charArrayOf(), "arm64-v8a", true) }.exceptionOrNull()
        assertTrue(excessiveMetadata?.message.orEmpty().contains("metadata exceeds"))
        var checked = 0
        val check = { if (++checked >= 8) throw IOException("cancelled") }
        assertTrue(runCatching { EnvironmentRestore(fixture.files, fixture.registry, checkCancellation = check)
            .restore(UUID.randomUUID().toString(), ByteArrayInputStream(bytes), charArrayOf(), "arm64-v8a", true) }.isFailure)
        assertTrue(File(fixture.files, ".environment-restores").listFiles().orEmpty().isEmpty())
        assertEquals(listOf(fixture.environment), fixture.registry.transaction { all() })
        assertEquals("secret", Files.readString(fixture.source.roots.getValue("home").resolve("private")))
    }

    @Test fun failureAfterPromotionRollsBackAndLateFailureAfterCommitPreservesActivation() {
        val fixture = fixture()
        val bytes = backup(fixture)
        val operation = UUID.randomUUID().toString()
        assertTrue(runCatching { EnvironmentRestore(fixture.files, fixture.registry, checkpoint = {
            if (it == "promoted") throw IOException("injected failure")
        }).restore(operation, ByteArrayInputStream(bytes), charArrayOf(), "arm64-v8a", true) }.isFailure)
        assertEquals(listOf(fixture.environment), fixture.registry.transaction { all() })
        assertTrue(fixture.registry.storage.transaction { pendingRestores().isEmpty() })
        val restored = EnvironmentRestore(fixture.files, fixture.registry, checkpoint = {
            if (it == "committed") throw IOException("late client failure")
        }).restore(UUID.randomUUID().toString(), ByteArrayInputStream(bytes), charArrayOf(), "arm64-v8a", true)
        assertEquals(restored, fixture.registry.transaction { requireEnvironment(restored.id) })
    }

    @Test fun processDeathBetweenRenameAndCommitNeverDiscoversPartialEnvironment() {
        val fixture = fixture()
        val operation = UUID.randomUUID().toString()
        val incomplete = Environment.create(SessionRuntime.ROOTLESS, "Interrupted restore")
        fixture.registry.transaction { reserveRestore(operation, incomplete) }
        val target = fixture.registry.storage.root(incomplete.storageKey).toPath()
        write(target, "home/partial", "not activated")
        assertEquals(listOf(fixture.environment), EnvironmentRegistry(fixture.files).transaction { all() })
        fixture.registry.storage.transaction {
            assertFalse(incomplete.storageKey in keys())
            assertTrue(runCatching { validateConnection(incomplete.storageKey) }.isFailure)
        }
        EnvironmentRestore(fixture.files, fixture.registry).recover()
        assertFalse(Files.exists(target, NOFOLLOW_LINKS))
        assertEquals("secret", Files.readString(fixture.source.roots.getValue("home").resolve("private")))
    }

    @Test fun forgedArchivePathsDuplicatesLinksAndExpandedSizeAreRejectedBeforeActivation() {
        val fixture = fixture()
        fun invalid(name: String, type: Int = 2, target: String? = null, size: Long = 0): ByteArray {
            val manifest = manifest(fixture).copy(entries = 2, bytes = 0, profiles = emptyList())
            return forged(manifest) { out ->
                out.writeByte(1); text(out, "home"); out.writeInt(448); out.writeLong(0)
                out.writeByte(type); text(out, name); out.writeInt(384); out.writeLong(0)
                if (target != null) text(out, target) else if (type == 2) out.writeLong(size)
            }
        }
        val attacks = listOf(invalid("home/../../escape"), invalid("/home/escape"), invalid("home"),
            invalid("home/link", 4, "rootfs/missing"), invalid("home/link", 3, "../outside"),
            invalid("rootfs/package"), invalid("home/oversized", size = Long.MAX_VALUE), invalid("home/device", type = 9))
        attacks.forEach { bytes -> assertTrue(runCatching { restore(fixture, bytes) }.isFailure) }
        val symlinkParent = forged(manifest(fixture).copy(entries = 3, bytes = 0, profiles = emptyList())) { out ->
            out.writeByte(1); text(out, "home"); out.writeInt(448); out.writeLong(0)
            out.writeByte(3); text(out, "home/link"); out.writeInt(511); out.writeLong(0); text(out, "workspace")
            out.writeByte(2); text(out, "home/link/injected"); out.writeInt(384); out.writeLong(0); out.writeLong(0)
        }
        assertTrue(runCatching { restore(fixture, symlinkParent) }.isFailure)
        assertEquals(listOf(fixture.environment), fixture.registry.transaction { all() })
    }

    private fun forged(manifest: BackupManifest, entries: (DataOutputStream) -> Unit): ByteArray {
        val buffer = ByteArrayOutputStream()
        BackupTransport.Writer(buffer, charArrayOf()).use { transport ->
            val gzip = GZIPOutputStream(transport)
            val hash = MessageDigest.getInstance("SHA-256").apply { update(transport.fingerprint) }
            val digest = DigestOutputStream(gzip, hash)
            val output = DataOutputStream(digest)
            text(output, manifest.toJson().toString()); entries(output); output.writeByte(0); output.flush()
            digest.on(false); output.write(hash.digest()); output.flush(); gzip.finish(); transport.finish(); gzip.close()
        }
        return buffer.toByteArray()
    }
    private fun text(out: DataOutputStream, value: String) { val bytes = value.toByteArray(); out.writeInt(bytes.size); out.write(bytes) }
}
