package dev.terminal.runtime.image

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.terminal.image.BundledDebianImageCatalog
import dev.terminal.image.DebianImageInstaller
import dev.terminal.image.DebianImageLayout
import dev.terminal.image.DebianImageRelease
import dev.terminal.image.DebianImageVerifier
import dev.terminal.image.ImageInstallResult
import dev.terminal.image.ImageVerification
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Downloads only a catalog-pinned Debian release; it never owns a VM or session. */
class DebianImageDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val releaseId = inputData.getString(KEY_RELEASE_ID) ?: return failure("Missing release ID")
        val release = BundledDebianImageCatalog.find(releaseId) ?: return failure("Unknown approved Debian release")
        val stagingRoot = applicationContext.cacheDir.toPath()
            .resolve("image-downloads")
            .resolve("${release.id}-${UUID.randomUUID()}")
        return try {
            Files.createDirectories(stagingRoot)
            download(release.manifestUri, stagingRoot.resolve(DebianImageLayout.MANIFEST_FILE), release)
            if (DebianImageVerifier.sha256(stagingRoot.resolve(DebianImageLayout.MANIFEST_FILE)) != release.manifestSha256) {
                return failure("Downloaded manifest checksum does not match catalog")
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
                    val installed = DebianImageInstaller.install(
                        stagingRoot,
                        applicationContext.filesDir.toPath().resolve("vm/default"),
                    )
                ) {
                    is ImageInstallResult.Installed -> Result.success(Data.Builder().putString(KEY_STATUS, "installed").build())
                    is ImageInstallResult.AlreadyInstalled -> Result.success(Data.Builder().putString(KEY_STATUS, "already_installed").build())
                    is ImageInstallResult.Failed -> failure(installed.reason)
                }
            }
        } catch (error: TransientDownloadException) {
            if (runAttemptCount < MAX_RETRIES) Result.retry() else failure(error.message ?: "Download failed")
        } catch (error: Exception) {
            failure(error.message ?: "Image download failed")
        } finally {
            if (Files.exists(stagingRoot)) deleteTree(stagingRoot)
        }
    }

    private fun downloadArtifact(release: DebianImageRelease, name: String, stagingRoot: Path) {
        val artifactUri = release.manifestUri.resolve(name)
        require(artifactUri.scheme == "https" && artifactUri.host == release.manifestUri.host) {
            "Artifact URL escaped the approved catalog host"
        }
        download(artifactUri, stagingRoot.resolve(name), release)
    }

    private fun download(uri: URI, destination: Path, release: DebianImageRelease) {
        val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
        }
        try {
            if (connection.responseCode !in 200..299) {
                if (connection.responseCode >= 500) throw TransientDownloadException("Image server unavailable")
                error("Image download failed with HTTP ${connection.responseCode}")
            }
            val declaredSize = connection.contentLengthLong
            require(declaredSize < 0 || declaredSize <= release.maxArtifactBytes) { "Artifact exceeds approved size limit" }
            BufferedInputStream(connection.inputStream).use { input ->
                BufferedOutputStream(Files.newOutputStream(destination)).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= release.maxArtifactBytes) { "Artifact exceeds approved size limit" }
                        output.write(buffer, 0, count)
                    }
                }
            }
        } catch (error: java.net.SocketTimeoutException) {
            throw TransientDownloadException("Image download timed out", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun failure(message: String): Result = Result.failure(Data.Builder().putString(KEY_ERROR, message).build())

    private fun deleteTree(root: Path) {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private class TransientDownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

    companion object {
        private const val KEY_RELEASE_ID = "release_id"
        private const val KEY_STATUS = "status"
        private const val KEY_ERROR = "error"
        private const val MAX_RETRIES = 3
        private const val CONNECT_TIMEOUT_MILLIS = 20_000
        private const val READ_TIMEOUT_MILLIS = 30_000
        private const val UNIQUE_WORK_NAME = "debian-image-download"

        fun enqueue(context: Context, releaseId: String) {
            val request = OneTimeWorkRequestBuilder<DebianImageDownloadWorker>()
                .setInputData(Data.Builder().putString(KEY_RELEASE_ID, releaseId).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
