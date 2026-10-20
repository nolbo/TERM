package dev.terminal.runtime.image

import dev.terminal.image.*
import dev.terminal.protocol.*
import dev.terminal.runtime.api.*
import dev.terminal.runtime.avf.*
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AvfEnvironmentUpdaterTest {
    private fun image(path: Path, version: String, maintenance: Boolean = true, installed: Boolean = true): Path {
        Files.createDirectories(path)
        val artifacts = mapOf("kernel" to "vmlinuz", "initrd" to "initrd.img", "system" to "system.img", "home" to "home.img", "config" to "config.img")
        artifacts.forEach { (_, file) -> Files.write(path.resolve(file), (if (file == "vmlinuz") "same kernel" else "$file:$version").toByteArray()) }
        Files.write(path.resolve("manifest.properties"), ("formatVersion=1\ndistribution=debian\narchitecture=arm64\n" +
            (if (maintenance) "maintenanceVersion=1\n" else "") +
            artifacts.entries.joinToString("\n") { (key, file) -> "$key.sha256=${AvfImageVerifier.sha256(path.resolve(file))}" }).toByteArray())
        check((if (installed) AvfImageInstaller.verifyForStartup(path) else AvfImageVerifier.verify(path)) is ImageVerification.Ready)
        return path
    }

    @Test fun bothModesKeepOriginalDisksUntilTheCandidatePassesASeparateBoot() = runBlocking {
        for (mode in listOf("components", "rebase")) {
            val temp = Files.createTempDirectory("avf-update")
            try {
                val root = image(temp.resolve("installed"), "old")
                Files.write(root.resolve("home.img"), "original user files".toByteArray())
                val staged = image(temp.resolve("download"), "new", installed = false)
                val driver = Driver(root)
                val updater = updater(driver, staged)
                updater.update(root, "test", AvfDistribution.DEBIAN, staged, mode, {}, { it() })
                assertEquals(2, driver.starts)
                assertEquals("original user files", String(Files.readAllBytes(AvfImageTransitions.previous(root)!!.resolve("home.img"))))
                assertEquals(if (mode == "components") "component state" else "migrated state", String(Files.readAllBytes(root.resolve("home.img"))))
                assertEquals(if (mode == "components") "system.img:old" else "system.img:new", String(Files.readAllBytes(root.resolve("system.img"))))
                assertFalse(Files.exists(root.resolve(AvfImageTransitions.MAINTENANCE)))
            } finally { temp.toFile().deleteRecursively() }
        }
    }

    @Test fun healthFailureAndGenerationRejectionPreserveTheOriginal() = runBlocking {
        for (healthFailure in listOf(true, false)) {
            val temp = Files.createTempDirectory("avf-update-failed")
            try {
                val root = image(temp.resolve("installed"), "old")
                Files.write(root.resolve("home.img"), "original user files".toByteArray())
                val staged = image(temp.resolve("download"), "new", installed = false)
                val driver = Driver(root, healthFailure)
                val failure = runCatching {
                    updater(driver, staged).update(root, "test", AvfDistribution.DEBIAN, staged, "rebase", {}, { error("Stale worker") })
                }
                assertTrue(failure.isFailure)
                assertEquals("original user files", String(Files.readAllBytes(root.resolve("home.img"))))
                assertEquals("system.img:old", String(Files.readAllBytes(root.resolve("system.img"))))
                assertNull(AvfImageTransitions.previous(root))
                assertFalse(Files.exists(root.resolve(AvfImageTransitions.MAINTENANCE)))
            } finally { temp.toFile().deleteRecursively() }
        }
    }

    @Test fun pinnedImageWithoutMaintenanceIsRejectedBeforeBootOrStateCopy() = runBlocking {
        val temp = Files.createTempDirectory("avf-update-unsupported")
        try {
            val root = image(temp.resolve("installed"), "old")
            val staged = image(temp.resolve("download"), "legacy", maintenance = false)
            val driver = Driver(root)
            val failure = runCatching {
                updater(driver, staged).update(root, "test", AvfDistribution.DEBIAN, staged, "rebase", {}, { it() })
            }.exceptionOrNull()
            assertEquals("새 AVF 이미지가 게스트 관리 기능을 지원하지 않습니다.", failure?.message)
            assertEquals(0, driver.starts)
            assertEquals("home.img:old", String(Files.readAllBytes(root.resolve("home.img"))))
            assertTrue(Files.isDirectory(staged))
            assertFalse(Files.exists(root.resolve(AvfImageTransitions.MAINTENANCE)))
        } finally { temp.toFile().deleteRecursively() }
    }

    private fun updater(driver: Driver, staged: Path): AvfEnvironmentUpdater {
        val release = AvfImageRelease("debian-arm64-current", URI("https://example.com/release/manifest.properties"),
            AvfImageVerifier.sha256(staged.resolve("manifest.properties")), 1024)
        return AvfEnvironmentUpdater(AvfBackend(driver), object : AvfImageCatalog {
            override fun find(releaseId: String) = release
        }, copyFile = { source, target, cancelled -> AvfSparseFiles.copyFile(source, target, checkCancellation = cancelled) })
    }

    private class Driver(val original: Path, val failHealth: Boolean = false) : AvfPlatformDriver {
        lateinit var config: RuntimeConfig
        var starts = 0
        override suspend fun prepare(config: RuntimeConfig): Result<Unit> {
            check(AvfImageInstaller.verifyForStartup(Path.of(config.imageDirectory)) is ImageVerification.Ready)
            this.config = config
            return Result.success(Unit)
        }
        override suspend fun start(config: RuntimeConfig): Result<Unit> { starts++; return Result.success(Unit) }
        override suspend fun stop(mode: StopMode) = Result.success(Unit)
        override suspend fun verifyStoppedInstance(instanceId: String) = Result.success(Unit)
        override suspend fun discardMaintenance(instanceId: String) = Result.success(Unit)
        override suspend fun connectGuestAgent(): Result<GuestConnection> = Result.failure(IOException("Unused"))
        override suspend fun connectImageInitializer(): Result<GuestConnection> = Result.success(object : GuestConnection {
            override val guestCid = 2
            override suspend fun send(frame: ProtocolFrame): Result<Unit> {
                assertEquals("original user files", String(Files.readAllBytes(original.resolve("home.img"))))
                if (frame.payload.contentEquals(byteArrayOf(0x41))) {
                    val maintenance = requireNotNull(config.avfMaintenance)
                    Files.write(Path.of(maintenance.copiedState), "component state".toByteArray())
                    Files.write(Path.of(config.imageDirectory, "home.img"), "migrated state".toByteArray())
                } else {
                    assertNull(config.avfMaintenance)
                    if (failHealth) return Result.failure(IOException("Candidate boot check failed"))
                }
                return Result.success(Unit)
            }
            override fun incomingFrames() = flowOf(ProtocolFrame(service = Service.LIFECYCLE, kind = FrameKind.RESPONSE,
                requestId = 1, payload = ByteBuffer.allocate(8).putLong(123).array()))
            override fun close() {}
        })
    }
}
