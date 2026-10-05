package dev.terminal.runtime.image

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import dev.terminal.image.BundledDebianImageCatalog
import dev.terminal.image.DebianImageInstaller
import dev.terminal.image.DebianImageLayout
import dev.terminal.image.DebianImageRelease
import dev.terminal.image.DebianImageVerifier
import dev.terminal.image.ImageInstallResult
import dev.terminal.image.ImageVerification
import dev.terminal.image.DebianArtifactDownloader
import dev.terminal.runtime.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.Constraints
import androidx.work.NetworkType
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Downloads only a catalog-pinned Debian release; it never owns a VM or session. */
class DebianImageDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    private var startedAt = 0L
    private var lastProgress = Data.EMPTY
    private var lastReportedAt = -1L
    private var notificationChannelCreated = false
    private var workContext: kotlin.coroutines.CoroutineContext? = null

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        workContext = currentCoroutineContext()
        startedAt = android.os.SystemClock.elapsedRealtime()
        val releaseId = inputData.getString(KEY_RELEASE_ID) ?: return@withContext failure("Missing release ID")
        val release = BundledDebianImageCatalog.find(releaseId) ?: return@withContext failure("Unknown approved Debian release")
        val storageId = inputData.getString(KEY_STORAGE_ID) ?: "legacy"
        val generation = inputData.getLong(KEY_IMAGE_GENERATION, 0L)
        val target = runCatching {
            dev.terminal.runtime.session.SessionStorage.root(applicationContext.filesDir,
                dev.terminal.runtime.session.SessionRuntime.AVF, storageId).toPath()
        }.getOrElse { return@withContext failure("Invalid storage ID") }
        val stagingRoot = applicationContext.cacheDir.toPath()
            .resolve("image-downloads")
            .resolve("${release.id}-${UUID.randomUUID()}")
        return@withContext try {
            dev.terminal.runtime.session.StorageRegistry(applicationContext.filesDir).transaction {
                validateImageGeneration(dev.terminal.runtime.session.StorageRegistry.Key(dev.terminal.runtime.session.SessionRuntime.AVF, storageId), generation)
            }
            setForeground(foregroundInfo())
            report(AvfImageInstallPhase.PREPARING)
            if (Files.exists(target)) {
                report(AvfImageInstallPhase.VERIFY)
                return@withContext if (DebianImageVerifier.verifyInstalled(target, ::checkCancellation) is ImageVerification.Ready) {
                    success("already_installed")
                } else failure("Existing AVF image is invalid; refusing replacement")
            }
            Files.createDirectories(target.parent)
            Files.createDirectories(stagingRoot)
            report(AvfImageInstallPhase.MANIFEST)
            DebianArtifactDownloader { isStopped }.download(release.manifestUri,
                stagingRoot.resolve(DebianImageLayout.MANIFEST_FILE), 64 * 1024)
            if (DebianImageVerifier.sha256(stagingRoot.resolve(DebianImageLayout.MANIFEST_FILE), ::checkCancellation) != release.manifestSha256) {
                return@withContext failure("Downloaded manifest checksum does not match catalog")
            }
            DebianImageVerifier.parse(stagingRoot.resolve(DebianImageLayout.MANIFEST_FILE))
            listOf(DebianImageLayout.KERNEL_IMAGE, DebianImageLayout.INITRD_IMAGE,
                DebianImageLayout.SYSTEM_IMAGE, DebianImageLayout.HOME_IMAGE, DebianImageLayout.CONFIG_IMAGE)
                .forEachIndexed { index, name -> downloadArtifact(release, name, stagingRoot, index + 1) }
            report(AvfImageInstallPhase.VERIFY)
            when (val verification = DebianImageVerifier.verify(stagingRoot, ::checkCancellation)) {
                is ImageVerification.Invalid -> failure(verification.reason)
                is ImageVerification.Ready -> when (
                    val installed = dev.terminal.runtime.session.StorageRegistry(applicationContext.filesDir) {
                        applicationContext.getSharedPreferences("session-profiles", Context.MODE_PRIVATE).all.mapValues { it.value as String }
                    }.transaction {
                        validateImageGeneration(dev.terminal.runtime.session.StorageRegistry.Key(dev.terminal.runtime.session.SessionRuntime.AVF, storageId), generation)
                        report(AvfImageInstallPhase.INSTALL)
                        DebianImageInstaller.install(stagingRoot, target, ::checkCancellation)
                    }
                ) {
                    is ImageInstallResult.Installed -> success("installed")
                    is ImageInstallResult.AlreadyInstalled -> if (installed.verification is ImageVerification.Ready)
                        success("already_installed")
                        else failure("Existing AVF image is invalid; refusing replacement")
                    is ImageInstallResult.Failed -> failure(installed.reason)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: java.io.IOException) {
            if (runAttemptCount < MAX_RETRIES) Result.retry() else failure(error.message ?: "Download failed")
        } catch (error: Exception) {
            failure(error.message ?: "Image download failed")
        } finally {
            if (Files.exists(stagingRoot)) deleteTree(stagingRoot)
        }
    }

    private fun downloadArtifact(release: DebianImageRelease, name: String, stagingRoot: Path, index: Int) {
        val artifactUri = release.manifestUri.resolve(if (release.gzipArtifacts) "$name.gz" else name)
        report(AvfImageInstallPhase.DOWNLOAD, index)
        DebianArtifactDownloader { isStopped }.download(artifactUri, stagingRoot.resolve(name),
            release.maxArtifactBytes, release.gzipArtifacts) { bytes, total ->
                report(AvfImageInstallPhase.DOWNLOAD, index, bytes, total)
            }
    }

    private fun report(phase: AvfImageInstallPhase, artifact: Int = 0, bytes: Long? = null, total: Long? = null) {
        checkCancellation()
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastProgress.getString(AvfImageInstallMonitor.KEY_PHASE) == phase.name &&
            lastProgress.getInt(AvfImageInstallMonitor.KEY_ARTIFACT, 0) == artifact &&
            now - lastReportedAt < 500 && (total == null || bytes != total)) return
        lastProgress = Data.Builder()
            .putString(AvfImageInstallMonitor.KEY_STORAGE, inputData.getString(KEY_STORAGE_ID) ?: "legacy")
            .putString(AvfImageInstallMonitor.KEY_PHASE, phase.name)
            .putInt(AvfImageInstallMonitor.KEY_ARTIFACT, artifact)
            .putLong(AvfImageInstallMonitor.KEY_BYTES, bytes ?: -1)
            .putLong(AvfImageInstallMonitor.KEY_TOTAL, total ?: -1)
            .putLong(AvfImageInstallMonitor.KEY_STARTED, startedAt)
            .putLong(AvfImageInstallMonitor.KEY_UPDATED, now).build()
        // Blocking callbacks run only on Dispatchers.IO. Persist each throttled update in order.
        setProgressAsync(lastProgress).get()
        lastReportedAt = now
        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(id.hashCode() and Int.MAX_VALUE, foregroundInfo().notification)
    }

    private fun success(status: String): Result {
        report(AvfImageInstallPhase.COMPLETE)
        // WorkManager clears running progress at completion; outputData retains the final snapshot.
        return Result.success(Data.Builder().putAll(lastProgress).putString(KEY_STATUS, status).build())
    }

    private fun checkCancellation() {
        workContext?.ensureActive()
        if (isStopped) throw CancellationException("AVF image installation cancelled")
    }

    private fun foregroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (!notificationChannelCreated) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "AVF 이미지 설치", NotificationManager.IMPORTANCE_LOW))
            notificationChannelCreated = true
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_terminal)
            .setContentTitle("AVF Debian 자동 설치")
            .setContentText(when (lastProgress.getString(AvfImageInstallMonitor.KEY_PHASE)) {
                "MANIFEST" -> "설치 정보 확인 중"
                "DOWNLOAD" -> "이미지 다운로드 및 압축 해제 중"
                "VERIFY" -> "이미지 무결성 검증 중"
                "INSTALL" -> "이미지 설치 및 최종 검증 중"
                "COMPLETE" -> "이미지 설치 완료"
                else -> "이미지 설치 준비 중"
            })
            .setOngoing(true).setProgress(0, 0, true).build()
        return ForegroundInfo(id.hashCode() and Int.MAX_VALUE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun failure(message: String): Result = Result.failure(Data.Builder().putAll(lastProgress).putString(KEY_ERROR, message.take(240)).build())

    private fun deleteTree(root: Path) {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    companion object {
        private const val KEY_RELEASE_ID = "release_id"
        private const val KEY_STORAGE_ID = "storage_id"
        private const val KEY_IMAGE_GENERATION = "image_generation"
        private const val KEY_STATUS = "status"
        internal const val KEY_ERROR = "error"
        private const val MAX_RETRIES = 3
        private const val CHANNEL_ID = "avf-image-install"
        private const val UNIQUE_WORK_NAME = "debian-image-download"

        internal fun workName(storageId: String): String {
            dev.terminal.runtime.session.SessionStorage.validate(storageId)
            return if (storageId == "legacy") UNIQUE_WORK_NAME else "$UNIQUE_WORK_NAME-$storageId"
        }

        internal fun request(releaseId: String, storageId: String, generation: Long = 0L): androidx.work.OneTimeWorkRequest {
            dev.terminal.runtime.session.SessionStorage.validate(storageId)
            return OneTimeWorkRequestBuilder<DebianImageDownloadWorker>()
                .addTag("${AvfImageInstallMonitor.STORAGE_TAG}$storageId")
                .addTag("${AvfImageInstallMonitor.REQUESTED_TAG}${System.currentTimeMillis()}")
                .setInputData(Data.Builder().putString(KEY_RELEASE_ID, releaseId).putString(KEY_STORAGE_ID, storageId)
                    .putLong(KEY_IMAGE_GENERATION, generation).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        }
    }
}
