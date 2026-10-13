package dev.terminal.runtime.image

import dev.terminal.image.AvfImageInstaller
import dev.terminal.image.AvfImageVerifier
import dev.terminal.image.ImageInstallResult
import dev.terminal.runtime.session.Environment
import dev.terminal.runtime.session.EnvironmentRegistry
import dev.terminal.runtime.session.SessionProfile
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.session.StorageRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class AvfImageActivationTest {
    @Test fun copyingImageLeavesEnvironmentAndProfileMetadataAccessible() {
        val root = Files.createTempDirectory("avf-copy-metadata")
        val executor = Executors.newFixedThreadPool(2)
        val copying = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val registry = StorageRegistry(root.resolve("files").toFile())
            val environments = EnvironmentRegistry(registry)
            val environment = Environment.create(SessionRuntime.AVF, "Installing")
            val profile = SessionProfile("installing", "Installing", SessionRuntime.AVF, "/bin/bash")
            environments.transaction { save(profile, environment) }
            val source = Files.createDirectory(root.resolve("source")).also(::writeImage)
            val destination = registry.root(environment.storageKey).toPath()
            Files.createDirectories(destination.parent)
            val paused = AtomicBoolean()
            val install = executor.submit<ImageInstallResult> {
                AvfImageInstaller.install(source, destination, activate = { promote ->
                    registry.transaction { validateImageGeneration(environment.storageKey, 0); promote() }
                }, checkCancellation = {
                    if (Files.list(destination.parent).use { entries ->
                        entries.anyMatch { it.fileName.toString().startsWith(".${destination.fileName}.install-") }
                    } && paused.compareAndSet(false, true)) {
                        copying.countDown()
                        check(release.await(10, TimeUnit.SECONDS)) { "Copy was not released" }
                    }
                })
            }
            assertTrue(copying.await(5, TimeUnit.SECONDS))
            val metadata = executor.submit<Pair<Int, Int>> {
                EnvironmentRegistry(root.resolve("files").toFile()).transaction { all().size to profiles().size }
            }
            assertEquals(1 to 1, metadata.get(5, TimeUnit.SECONDS))
            assertFalse(install.isDone)
            release.countDown()
            assertTrue(install.get(5, TimeUnit.SECONDS) is ImageInstallResult.Installed)
        } finally {
            release.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            root.toFile().deleteRecursively()
        }
    }

    @Test fun removalDuringPreparationRejectsStaleActivationAndCleansTemporaryImage() {
        for (consumeStaging in listOf(false, true)) {
            val root = Files.createTempDirectory("avf-stale-activation")
            try {
                val registry = StorageRegistry(root.resolve("files").toFile())
                val environment = Environment.create(SessionRuntime.AVF, "Installing")
                EnvironmentRegistry(registry).transaction {
                    save(SessionProfile("installing", "Installing", SessionRuntime.AVF, "/bin/bash"), environment)
                }
                val destination = registry.root(environment.storageKey).toPath()
                Files.createDirectories(destination.parent)
                val source = Files.createDirectory(if (consumeStaging) AvfImageInstaller.newStagingPath(destination)
                    else root.resolve("source")).also(::writeImage)
                val result = AvfImageInstaller.install(source, destination, activate = { promote ->
                    registry.transaction { removeSoftware(environment.storageKey) }
                    registry.transaction { validateImageGeneration(environment.storageKey, 0); promote() }
                }, consumeStagingDirectory = consumeStaging)
                assertTrue(result is ImageInstallResult.Failed)
                assertTrue((result as ImageInstallResult.Failed).reason.contains("superseded"))
                assertFalse(Files.exists(destination))
                Files.list(destination.parent).use { assertEquals(0L, it.count()) }
                registry.transaction { assertEquals(1L, imageGeneration(environment.storageKey)) }
            } finally { root.toFile().deleteRecursively() }
        }
    }

    private fun writeImage(root: Path) {
        val artifacts = listOf("kernel" to "vmlinuz", "initrd" to "initrd.img", "system" to "system.img",
            "home" to "home.img", "config" to "config.img")
        artifacts.forEach { (name, file) -> Files.writeString(root.resolve(file), name) }
        Files.writeString(root.resolve("manifest.properties"),
            "formatVersion=1\ndistribution=debian\narchitecture=arm64\n" + artifacts.joinToString("\n") { (name, file) ->
                "$name.sha256=${AvfImageVerifier.sha256(root.resolve(file))}"
            })
    }
}
