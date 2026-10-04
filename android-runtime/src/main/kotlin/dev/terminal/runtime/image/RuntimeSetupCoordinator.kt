package dev.terminal.runtime.image

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkQuery
import androidx.work.multiprocess.RemoteWorkManager
import dev.terminal.image.BundledDebianImageCatalog
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Explicitly requested installation is independent from VM startup and survives its requesting UI. */
class RuntimeSetupCoordinator(private val context: Context) {
    /** Called only after installation consent. Enqueueing does not start a VM or a session. */
    suspend fun requestImageInstallation(storageId: String, releaseId: String = BundledDebianImageCatalog.DEFAULT_RELEASE_ID) {
        check(BundledDebianImageCatalog.find(releaseId) != null) {
            "이 제품 빌드에 AVF 설치 이미지가 등록되지 않았습니다."
        }
        dev.terminal.runtime.session.StorageRegistry(context.filesDir) {
            context.getSharedPreferences("session-profiles", Context.MODE_PRIVATE).all.mapValues { it.value as String }
        }.transaction {
            validateConnection(dev.terminal.runtime.session.StorageRegistry.Key(dev.terminal.runtime.session.SessionRuntime.AVF, storageId))
        }
        val imageRoot = dev.terminal.runtime.session.SessionStorage.root(context.filesDir,
            dev.terminal.runtime.session.SessionRuntime.AVF, storageId).toPath()
        if (dev.terminal.image.DebianImageVerifier.hasInstalledImage(imageRoot)) return
        check(!Files.exists(imageRoot, LinkOption.NOFOLLOW_LINKS)) {
            "이미지 저장공간에 기존 파일이 있어 자동으로 교체할 수 없습니다."
        }
        RemoteWorkManager.getInstance(context).enqueueUniqueWork(DebianImageDownloadWorker.workName(storageId),
            androidx.work.ExistingWorkPolicy.KEEP, listOf(DebianImageDownloadWorker.request(releaseId, storageId)))
            .awaitImageOperation()
    }

    /** Wait only for an installation already requested by the user; never enqueue from session startup. */
    suspend fun awaitInstalledImage(imageRoot: Path, storageId: String = "legacy") {
        // Leave existing images untouched, including invalid entries. Driver preparation
        // performs the authoritative checksum check instead of reading the image twice.
        if (Files.exists(imageRoot, LinkOption.NOFOLLOW_LINKS)) return
        val manager = RemoteWorkManager.getInstance(context)
        val workName = DebianImageDownloadWorker.workName(storageId)
        while (true) {
            val history = manager.getWorkInfos(WorkQuery.fromUniqueWorkNames(listOf(workName))).awaitImageOperation()
            val work = history.firstOrNull { !it.state.isFinished } ?: history.maxByOrNull { info ->
                info.tags.firstOrNull { it.startsWith(AvfImageInstallMonitor.REQUESTED_TAG) }
                    ?.removePrefix(AvfImageInstallMonitor.REQUESTED_TAG)?.toLongOrNull() ?: 0L
            }
            when (work?.state) {
                WorkInfo.State.SUCCEEDED -> {
                    check(Files.exists(imageRoot, LinkOption.NOFOLLOW_LINKS)) { "AVF 환경 설정에서 Debian 이미지를 설치하세요." }
                    return
                }
                WorkInfo.State.FAILED -> error(work.outputData.getString(DebianImageDownloadWorker.KEY_ERROR)
                    ?: "AVF 이미지 자동 설치 실패")
                WorkInfo.State.CANCELLED -> error("AVF 이미지 설치가 취소되었습니다.")
                null -> error("AVF 환경 설정에서 Debian 이미지를 설치하세요.")
                else -> delay(1_000)
            }
        }
    }
}

private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.awaitImageOperation(): T =
    suspendCancellableCoroutine { continuation ->
        addListener({
            try { continuation.resume(get()) }
            catch (error: Exception) { continuation.resumeWithException(error.cause ?: error) }
        }, java.util.concurrent.Executor { it.run() })
        continuation.invokeOnCancellation { cancel(false) }
    }
