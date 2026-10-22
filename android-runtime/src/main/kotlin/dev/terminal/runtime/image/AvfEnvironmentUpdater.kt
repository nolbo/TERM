package dev.terminal.runtime.image

import dev.terminal.image.*
import dev.terminal.runtime.api.AvfMaintenanceBoot
import dev.terminal.runtime.api.RuntimeConfig
import dev.terminal.runtime.api.StopMode
import dev.terminal.runtime.avf.AvfBackend
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The caller holds this storage's preparation lease and excludes sessions. No host filesystem mounts. */
internal class AvfEnvironmentUpdater(private val backend: AvfBackend, private val catalog: AvfImageCatalog = BundledAvfImageCatalog,
    private val copyFile: (Path, Path, () -> Unit) -> AvfSparseFiles.CopyResult = AndroidAvfSparseFiles::copy) {
    private fun maintenanceId(instanceId: String) = "maintenance-" +
        UUID.nameUUIDFromBytes(instanceId.toByteArray(Charsets.UTF_8)).toString().replace("-", "")

    suspend fun recover(root: Path, instanceId: String) {
        val maintenance = root.resolve(AvfImageTransitions.MAINTENANCE)
        if (Files.exists(maintenance, NOFOLLOW_LINKS) || AvfImageTransitions.interrupted(root)) {
            backend.verifyStoppedInstance(instanceId).getOrThrow()
            backend.discardMaintenance(maintenanceId(instanceId)).getOrThrow()
            AvfImageTransitions.recover(root)
            AvfImageTransitions.deleteTree(maintenance)
        }
    }

    suspend fun update(root: Path, instanceId: String, distribution: AvfDistribution, downloaded: Path,
        operation: String, progress: (AvfImageInstallPhase) -> Unit, activate: (() -> Unit) -> Unit) {
        require(operation in setOf("components", "rebase"))
        recover(root, instanceId)
        backend.verifyStoppedInstance(instanceId).getOrThrow()
        val old = AvfImageVerifier.verifyInstalled(root, distribution) as? ImageVerification.Ready
            ?: error("기존 AVF 이미지의 무결성을 확인할 수 없습니다.")
        val next = AvfImageVerifier.verify(downloaded, distribution) as? ImageVerification.Ready
            ?: error("새 AVF 이미지의 무결성을 확인할 수 없습니다.")
        check(AvfImageVerifier.sha256(downloaded.resolve(AvfImageLayout.MANIFEST_FILE)) ==
            catalog.find(distribution.releaseId)?.manifestSha256) { "승인된 이미지 카탈로그와 일치하지 않습니다." }
        check(next.manifest.maintenanceVersion == 1) { "새 AVF 이미지가 게스트 관리 기능을 지원하지 않습니다." }
        check(next.manifest.formatVersion >= old.manifest.formatVersion) { "이전 디스크 형식으로 업데이트할 수 없습니다. 이전 세대 복구를 사용하세요." }
        if (operation == "components") check(AvfImageUpdateChecker.kind(old.manifest, next.manifest) == AvfImageUpdateKind.COMPONENTS) {
            "커널 또는 루트 디스크 형식이 다른 이미지입니다. 전체 이미지 업데이트를 사용하세요."
        }
        val work = Files.createDirectory(root.resolve(AvfImageTransitions.MAINTENANCE))
        val candidate = work.resolve("candidate")
        var stopped = false
        val coroutine = currentCoroutineContext()
        val checkCancellation = { coroutine.ensureActive() }
        try {
            Files.move(downloaded, candidate)
            // prepare() owns full startup verification and records the installation receipt.
            progress(AvfImageInstallPhase.INSTALL)
            val state = work.resolve("old-state.img")
            copyFile(root.resolve("home.img"), state, checkCancellation)
            val id = maintenanceId(instanceId)
            backend.prepare(RuntimeConfig(instanceId = id, imageDirectory = candidate.toString(),
                imageDistribution = distribution.id, avfMaintenance = AvfMaintenanceBoot(operation,
                    root.resolve("system.img").toString(), state.toString(), old.manifest.formatVersion))).getOrThrow()
            progress(AvfImageInstallPhase.BOOT)
            backend.start().getOrThrow()
            progress(AvfImageInstallPhase.UPDATE)
            AvfPackageInitializer.initialize(backend.connectImageInitializer().getOrThrow(),
                timeoutMillis = 1_860_000, maintenance = true)
            withContext(NonCancellable) {
                backend.stop(StopMode.GRACEFUL).getOrThrow()
                backend.discardMaintenance(id).getOrThrow()
                stopped = true
            }
            checkCancellation()
            val result = if (operation == "rebase") candidate else {
                val result = Files.createDirectory(work.resolve("result"))
                for (name in AvfImageTransitions.payload.filter { it != "home.img" }) {
                    val source = root.resolve(name)
                    if (Files.exists(source, NOFOLLOW_LINKS)) {
                        require(Files.isRegularFile(source, NOFOLLOW_LINKS))
                        copyFile(source, result.resolve(name), checkCancellation)
                    }
                }
                Files.move(state, result.resolve("home.img"))
                AvfGuestComponents.record(result, candidate)
                result
            }
            stopped = false
            backend.prepare(RuntimeConfig(instanceId = id, imageDirectory = result.toString(), imageDistribution = distribution.id)).getOrThrow()
            progress(AvfImageInstallPhase.BOOT)
            backend.start().getOrThrow()
            AvfPackageInitializer.initialize(backend.connectImageInitializer().getOrThrow(), healthCheck = true)
            withContext(NonCancellable) {
                backend.stop(StopMode.GRACEFUL).getOrThrow()
                backend.discardMaintenance(id).getOrThrow()
                stopped = true
            }
            progress(AvfImageInstallPhase.INSTALL)
            // Full checks run outside the registry lock. The fence rechecks generation before moving files.
            AvfImageTransitions.activate(root, result, distribution, activate = { promote ->
                checkCancellation(); activate(promote)
            })
        } finally {
            withContext(NonCancellable) {
                if (!stopped) {
                    backend.discardMaintenance(maintenanceId(instanceId)).getOrThrow()
                    stopped = true
                }
                if (stopped) {
                    AvfImageTransitions.recover(root)
                    AvfImageTransitions.deleteTree(work)
                }
            }
        }
    }
}
