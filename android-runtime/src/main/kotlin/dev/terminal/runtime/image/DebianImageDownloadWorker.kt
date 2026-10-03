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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
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
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val releaseId = inputData.getString(KEY_RELEASE_ID) ?: return@withContext failure("Missing release ID")
        val release = BundledDebianImageCatalog.find(releaseId) ?: return@withContext failure("Unknown approved Debian release")
        val storageId = inputData.getString(KEY_STORAGE_ID) ?: "legacy"
        val target = runCatching {
            dev.terminal.runtime.session.SessionStorage.root(applicationContext.filesDir,
                dev.terminal.runtime.session.SessionRuntime.AVF, storageId).toPath()
        }.getOrElse { return@withContext failure("Invalid storage ID") }
        val stagingRoot = applicationContext.cacheDir.toPath()
            .resolve("image-downloads")
            .resolve("${release.id}-${UUID.randomUUID()}")
        return@withContext try {
            setForeground(foregroundInfo())
            if (Files.exists(target)) {
                return@withContext if (DebianImageVerifier.verifyInstalled(target) is ImageVerification.Ready) {
                    Result.success()
                } else failure("Existing AVF image is invalid; refusing replacement")
            }
            Files.createDirectories(target.parent)
            Files.createDirectories(stagingRoot)
            DebianArtifactDownloader { isStopped }.download(release.manifestUri,
                stagingRoot.resolve(DebianImageLayout.MANIFEST_FILE), 64 * 1024)
            if (DebianImageVerifier.sha256(stagingRoot.resolve(DebianImageLayout.MANIFEST_FILE)) != release.manifestSha256) {
                return@withContext failure("Downloaded manifest checksum does not match catalog")
            }
            DebianImageVerifier.parse(stagingRoot.resolve(DebianImageLayout.MANIFEST_FILE))
            downloadArtifact(release, DebianImageLayout.KERNEL_IMAGE, stagingRoot)
            downloadArtifact(release, DebianImageLayout.INITRD_IMAGE, stagingRoot)
            downloadArtifact(release, DebianImageLayout.SYSTEM_IMAGE, stagingRoot)
            downloadArtifact(release, DebianImageLayout.HOME_IMAGE, stagingRoot)
            downloadArtifact(release, DebianImageLayout.CONFIG_IMAGE, stagingRoot)
            when (val verification = DebianImageVerifier.verify(stagingRoot)) {
                is ImageVerification.Invalid -> failure(verification.reason)
                is ImageVerification.Ready -> when (
                    val installed = dev.terminal.runtime.session.StorageRegistry(applicationContext.filesDir) {
                        applicationContext.getSharedPreferences("session-profiles", Context.MODE_PRIVATE).all.mapValues { it.value as String }
                    }.transaction {
                        validateConnection(dev.terminal.runtime.session.StorageRegistry.Key(dev.terminal.runtime.session.SessionRuntime.AVF, storageId))
                        DebianImageInstaller.install(stagingRoot, target)
                    }
                ) {
                    is ImageInstallResult.Installed -> Result.success(Data.Builder().putString(KEY_STATUS, "installed").build())
                    is ImageInstallResult.AlreadyInstalled -> if (installed.verification is ImageVerification.Ready)
                        Result.success(Data.Builder().putString(KEY_STATUS, "already_installed").build())
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

    private fun downloadArtifact(release: DebianImageRelease, name: String, stagingRoot: Path) {
        val artifactUri = release.manifestUri.resolve(if (release.gzipArtifacts) "$name.gz" else name)
        DebianArtifactDownloader { isStopped }.download(artifactUri, stagingRoot.resolve(name),
            release.maxArtifactBytes, release.gzipArtifacts)
    }

    private fun foregroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "AVF 이미지 설치", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("AVF Debian 자동 설치")
            .setContentText("이미지를 내려받아 검증하고 있습니다. 완료 후 세션이 시작됩니다.")
            .setOngoing(true).setProgress(0, 0, true).build()
        return ForegroundInfo(id.hashCode() and Int.MAX_VALUE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun failure(message: String): Result = Result.failure(Data.Builder().putString(KEY_ERROR, message).build())

    private fun deleteTree(root: Path) {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    companion object {
        private const val KEY_RELEASE_ID = "release_id"
        private const val KEY_STORAGE_ID = "storage_id"
        private const val KEY_STATUS = "status"
        internal const val KEY_ERROR = "error"
        private const val MAX_RETRIES = 3
        private const val CHANNEL_ID = "avf-image-install"
        private const val UNIQUE_WORK_NAME = "debian-image-download"

        internal fun workName(storageId: String): String {
            dev.terminal.runtime.session.SessionStorage.validate(storageId)
            return if (storageId == "legacy") UNIQUE_WORK_NAME else "$UNIQUE_WORK_NAME-$storageId"
        }

        internal fun request(releaseId: String, storageId: String): androidx.work.OneTimeWorkRequest {
            dev.terminal.runtime.session.SessionStorage.validate(storageId)
            return OneTimeWorkRequestBuilder<DebianImageDownloadWorker>()
                .setInputData(Data.Builder().putString(KEY_RELEASE_ID, releaseId).putString(KEY_STORAGE_ID, storageId).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        }
    }
}
