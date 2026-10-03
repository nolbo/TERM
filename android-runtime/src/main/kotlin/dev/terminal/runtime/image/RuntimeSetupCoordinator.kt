package dev.terminal.runtime.image

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkQuery
import androidx.work.multiprocess.RemoteWorkManager
import dev.terminal.image.BundledDebianImageCatalog
import dev.terminal.image.DebianImageVerifier
import dev.terminal.image.DebianImageInstaller
import dev.terminal.image.ImageVerification
import java.nio.file.Path
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Downloads survive UI detachment; the pending session continues after installation. */
class RuntimeSetupCoordinator(private val context: Context) {
    suspend fun ensureImage(imageRoot: Path, releaseId: String, storageId: String = "legacy") {
        if (DebianImageInstaller.verifyForStartup(imageRoot) is ImageVerification.Ready) return
        check(!Files.exists(imageRoot)) { "기존 AVF 이미지가 손상되어 있습니다. 저장소를 확인하세요." }
        check(BundledDebianImageCatalog.find(releaseId) != null) {
            "이 제품 빌드에 AVF 자동 설치 이미지가 등록되지 않았습니다."
        }
        val manager = RemoteWorkManager.getInstance(context)
        val workName = DebianImageDownloadWorker.workName(storageId)
        manager.enqueueUniqueWork(workName, androidx.work.ExistingWorkPolicy.KEEP,
            listOf(DebianImageDownloadWorker.request(releaseId, storageId))).awaitImageOperation()
        while (true) {
            val work = manager.getWorkInfos(WorkQuery.fromUniqueWorkNames(listOf(workName)))
                .awaitImageOperation().singleOrNull()
            when (work?.state) {
                WorkInfo.State.SUCCEEDED -> {
                    val verified = DebianImageVerifier.verifyInstalled(imageRoot)
                    check(verified is ImageVerification.Ready) { "자동 설치 이미지 검증 실패: $verified" }
                    return
                }
                WorkInfo.State.FAILED -> error(work.outputData.getString(DebianImageDownloadWorker.KEY_ERROR)
                    ?: "AVF 이미지 자동 설치 실패")
                WorkInfo.State.CANCELLED -> error("AVF 이미지 설치가 취소되었습니다.")
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
