package dev.terminal.runtime.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.protocol.SessionEvent
import dev.terminal.runtime.native.ApkNativeExecutableLoader
import dev.terminal.runtime.rootless.*
import dev.terminal.runtime.session.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses an isolated registry and only test-owned processes/data. No existing app environments stop. */
@RunWith(AndroidJUnit4::class)
class EnvironmentBackupDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun androidProviderPreservesPermissionsSymlinksAndAtomicRegistration() {
        val files = Files.createTempDirectory(context.filesDir.canonicalFile.toPath(), "backup-device-").toFile()
        try {
            val registry = EnvironmentRegistry(files)
            val environment = Environment.create(SessionRuntime.NATIVE, "Backup device fixture")
            registry.transaction { save(SessionProfile(UUID.randomUUID().toString(), "Fixture", SessionRuntime.NATIVE, "/system/bin/sh"), environment) }
            val root = registry.storage.root(environment.storageKey).toPath()
            val home = Files.createDirectories(root.resolve("home"))
            Files.write(home.resolve("private"), "preserved".toByteArray())
            Files.setAttribute(home.resolve("private"), "unix:mode", 384)
            Files.createSymbolicLink(home.resolve("link"), java.nio.file.Paths.get("private"))
            val source = TermBackupArchive.Source(mapOf("home" to home, "rootfs" to root.resolve("rootfs"), "workspace" to root.resolve("workspace")), setOf("home"), false)
            val archive = TermBackupArchive()
            val inventory = archive.inventory(source)
            val manifest = BackupManifest(BackupKind.DATA, environment, "arm64-v8a", "device-test", "native-data-1", BackupWorkspace.NONE,
                registry.transaction { profiles() }, inventory.bytes, inventory.entries)
            val output = java.io.ByteArrayOutputStream()
            archive.write(output, "device-test-password".toCharArray(), manifest, source)
            val restored = EnvironmentRestore(files, registry).restore(UUID.randomUUID().toString(), output.toByteArray().inputStream(),
                "device-test-password".toCharArray(), "arm64-v8a", true)
            val fresh = registry.storage.root(restored.storageKey).toPath()
            assertEquals("preserved", fresh.resolve("home/link").toFile().readText())
            assertEquals(384, (Files.getAttribute(fresh.resolve("home/private"), "unix:mode", NOFOLLOW_LINKS) as Int) and 4095)
            assertNotEquals(environment.id, restored.id)
            assertEquals(2, registry.transaction { all().size })
        } finally { EnvironmentRestore.deletePrivateTree(files.toPath()) }
    }

    /** Explicit opt-in downloads the catalog-pinned Debian base (~100 MiB); no default packages/network update. */
    @Test fun realDebianFullRestoreRunsShellAptDpkgAndPreservesMutableHardlinkSemantics() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("backupRealRootless") == "true")
        check(context.packageName == "dev.terminal.runtime.test")
        val files = Files.createTempDirectory(context.filesDir.canonicalFile.toPath(), "backup-debian-").toFile()
        var layout: RootlessDebianLayout? = null
        try {
            val registry = EnvironmentRegistry(files)
            val environment = Environment.create(SessionRuntime.ROOTLESS, "Debian backup fixture")
            registry.transaction { save(SessionProfile(UUID.randomUUID().toString(), "Fixture", SessionRuntime.ROOTLESS, "/bin/sh"), environment) }
            val original = RootlessDebianLayout(registry.storage.root(environment.storageKey), workspaceRoot = File(registry.storage.root(environment.storageKey), "workspace"))
            layout = original
            original.createHostDirectories()
            assertFalse("New environments do not automatically create legacy workspace data", original.workspace.exists())
            assertTrue(original.workspace.mkdir())
            File(original.workspace, "legacy.txt").writeText("legacy-preserved")
            if (InstrumentationRegistry.getArguments().getString("backupRealRootlessFormat") == "emulated") {
                File(original.root, ".terminal-data-storage-format").writeText("emulated-hardlinks-1\n")
            }
            withTimeout(600_000) { RootlessBootstrapper(context, original).installIfNeeded().getOrThrow() }
            val install = shell(original, """
                set -eu
                umask 022
                mkdir -p /root/fixture-pkg/DEBIAN /root/fixture-pkg/usr/share/term-backup-fixture
                chmod 755 /root/fixture-pkg/DEBIAN
                printf 'Package: term-backup-fixture\nVersion: 1.0\nArchitecture: all\nMaintainer: TERM test\nDescription: Isolated backup test\n' > /root/fixture-pkg/DEBIAN/control
                printf 'package-preserved\n' > /root/fixture-pkg/usr/share/term-backup-fixture/value
                dpkg-deb --build --root-owner-group /root/fixture-pkg /root/fixture.deb
                dpkg -i /root/fixture.deb
                printf 'links-preserved\n' > /root/original
                chmod 600 /root/original
                ln /root/original /root/alias
                ln -s original /root/symbolic
                ln -s /usr/share/term-backup-fixture/value /root/package-link
                ln -s /root/original /etc/term-backup-home-link
                ln -s ../etc/os-release /root/os-link
                printf 'source-ready\n'
            """.trimIndent())
            assertTrue(install.contains("source-ready"))
            EnvironmentWriterProbe(android.os.Process.myUid(), android.os.Process.myPid()).verify(listOf(original.root.toPath()))
            val source = TermBackupArchive.Source(mapOf("home" to original.home.toPath(), "rootfs" to original.rootfs.toPath(), "workspace" to original.workspace.toPath()),
                setOf("home", "rootfs", "workspace"), true)
            val archive = TermBackupArchive()
            val inventory = archive.inventory(source)
            val format = when (RootlessStorageFormat.resolveForLaunch(original.rootfs.toPath())) {
                RootlessStorageFormat.LaunchFormat.NATIVE -> "native-hardlinks-1"
                RootlessStorageFormat.LaunchFormat.EMULATED -> "emulated-hardlinks-1"
                else -> "legacy-emulated-hardlinks"
            }
            val manifest = BackupManifest(BackupKind.FULL, environment, "arm64-v8a", "device-test", format, BackupWorkspace.ENVIRONMENT,
                registry.transaction { profiles() }, inventory.bytes, inventory.entries)
            val backup = File(files, "fixture.termbackup")
            backup.outputStream().use { archive.write(it, "roundtrip-password".toCharArray(), manifest, source) }
            val restored = backup.inputStream().use { input -> EnvironmentRestore(files, registry).restore(UUID.randomUUID().toString(), input,
                "roundtrip-password".toCharArray(), "arm64-v8a", true) }
            val fresh = RootlessDebianLayout(registry.storage.root(restored.storageKey), workspaceRoot = File(registry.storage.root(restored.storageKey), "workspace"))
            fresh.createHostDirectories()
            assertEquals("legacy-preserved", File(fresh.workspace, "legacy.txt").readText())
            File(fresh.workspace, "legacy.txt").writeText("restored-change")
            assertEquals("legacy-preserved", File(original.workspace, "legacy.txt").readText())
            val result = shell(fresh, """
                set -eu
                test "${'$'}(cat /usr/share/term-backup-fixture/value)" = package-preserved
                test "${'$'}(cat /root/package-link)" = package-preserved
                test "${'$'}(cat /etc/term-backup-home-link)" = links-preserved
                grep '^ID=debian' /root/os-link
                dpkg-query -W -f='${'$'}{Status}\n' term-backup-fixture | grep 'install ok installed'
                test "${'$'}(stat -c %a /root/alias)" = 600
                test "${'$'}(stat -c %h /root/alias)" = 2
                printf 'changed\n' > /root/original
                test "${'$'}(cat /root/alias)" = changed
                rm /root/original
                test "${'$'}(cat /root/alias)" = changed
                ln /root/alias /root/third
                test "${'$'}(stat -c %h /root/alias)" = 2
                apt-get --version
                apt-get check
                dpkg --audit
                dpkg -r term-backup-fixture
                test ! -f /usr/share/term-backup-fixture/value
                printf 'restore-ready\n'
            """.trimIndent())
            assertTrue(result.contains("restore-ready"))
            assertTrue(result.contains("apt "))
            assertEquals("links-preserved\n", File(original.home, "original").readText())
            assertTrue(File(original.rootfs, "usr/share/term-backup-fixture/value").isFile)
            println("TERM_BACKUP_DEVICE: shell, apt check, dpkg install/removal, mode and hardlink mutation passed ($format)")
        } catch (failure: Throwable) {
            println("TERM_BACKUP_DEVICE: ${failure.stackTraceToString()}")
            throw failure
        } finally {
            layout?.let { EnvironmentRestore.deletePrivateTree(it.downloadDirectory(context.cacheDir.canonicalFile).toPath()) }
            EnvironmentRestore.deletePrivateTree(files.toPath())
        }
    }

    private suspend fun shell(layout: RootlessDebianLayout, command: String): String = coroutineScope {
        val loader = ApkNativeExecutableLoader(context)
        val controller = RootlessSessionController(layout, loader.resolvePackagedExecutable("proot").getOrThrow(),
            loader.resolvePackagedExecutable("proot-loader").getOrThrow(), cacheDirectory = context.cacheDir)
        val text = StringBuilder()
        val exited = CompletableDeferred<Int>()
        val reader = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.events.collect { event -> when (event) {
                is SessionEvent.Output -> text.append(event.bytes.toString(Charsets.UTF_8))
                is SessionEvent.Exited -> exited.complete(event.exitCode)
                is SessionEvent.Resized, is SessionEvent.OutputGap -> Unit
            } }
        }
        try {
            controller.create("/bin/sh", "/root", mapOf(SessionCommand.KEY to SessionCommand.encode(listOf("/bin/sh", "-c", command))), 24u, 100u).getOrThrow()
            val exitCode = withTimeout(120_000) { exited.await() }
            assertEquals(text.toString(), 0, exitCode)
            text.toString()
        } finally { reader.cancelAndJoin(); controller.close() }
    }
}
