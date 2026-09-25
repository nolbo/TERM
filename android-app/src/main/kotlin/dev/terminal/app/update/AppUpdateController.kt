package dev.terminal.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.terminal.app.BuildConfig
import dev.terminal.app.R
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

internal sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data object NoRelease : AppUpdateState
    data object Current : AppUpdateState
    data class Available(val manifest: AppUpdateManifest) : AppUpdateState
    data class Downloading(val manifest: AppUpdateManifest, val completed: Long) : AppUpdateState
    data class Ready(val manifest: AppUpdateManifest, val apk: File) : AppUpdateState
    data class Failed(val message: Int) : AppUpdateState
}

/** Android may purge cache files even while a download holds them open. */
internal fun updateStagingFile(context: Context): File =
    File(context.filesDir, "app-update-download/TERM.pending.apk")

/** Application-owned update operation; screens only observe state and issue commands. */
internal class AppUpdateController(context: Context) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val preferences = app.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
    val releaseUpdatesSupported = BuildConfig.APPLICATION_ID == "dev.terminal.app"
    var channel: UpdateChannel by mutableStateOf(
        UpdateChannel.entries.firstOrNull { it.wireName == preferences.getString("channel", BuildConfig.UPDATE_CHANNEL) }
            ?: UpdateChannel.fromWire(BuildConfig.UPDATE_CHANNEL)
    )
        private set
    var state: AppUpdateState by mutableStateOf(AppUpdateState.Idle)
        private set
    val busy: Boolean get() = state is AppUpdateState.Checking || state is AppUpdateState.Downloading

    fun selectChannel(value: UpdateChannel) {
        if (!releaseUpdatesSupported || busy || value == channel) return
        preferences.edit().putString("channel", value.wireName).apply()
        channel = value
        state = AppUpdateState.Idle
    }

    fun check() {
        if (!releaseUpdatesSupported || busy) return
        val requestedChannel = channel
        state = AppUpdateState.Checking
        worker.execute {
            try {
                val connection = open(requestedChannel.manifestUrl)
                val manifest = try {
                    if (connection.responseCode == 404) null
                    else {
                        if (connection.responseCode != 200) throw IOException("HTTP response rejected")
                        val text = connection.inputStream.use { readUpdateText(it, MAX_UPDATE_MANIFEST_BYTES) }
                        AppUpdateManifest.parse(text, requestedChannel)
                    }
                } finally { connection.disconnect() }
                val result = if (manifest == null) AppUpdateState.NoRelease else when (
                    updateAvailability(BuildConfig.VERSION_CODE, BuildConfig.UPDATE_REVISION, Build.VERSION.SDK_INT, manifest)
                ) {
                    UpdateAvailability.CURRENT -> AppUpdateState.Current
                    UpdateAvailability.AVAILABLE -> AppUpdateState.Available(manifest.copy(releaseNotes = loadReleaseNotes(manifest)))
                    UpdateAvailability.OLDER -> AppUpdateState.Failed(R.string.update_channel_older)
                    UpdateAvailability.INCOMPATIBLE -> AppUpdateState.Failed(R.string.update_incompatible)
                    UpdateAvailability.NON_FORWARD -> AppUpdateState.Failed(R.string.update_non_forward)
                }
                publish(result)
            } catch (error: Exception) { fail(error) }
        }
    }

    private fun loadReleaseNotes(manifest: AppUpdateManifest): String? = try {
        val connection = open(manifest.releaseNotesUrl)
        try {
            if (connection.responseCode != 200) null
            else connection.inputStream.use { readUpdateText(it, MAX_RELEASE_NOTES_BYTES) }
                .trim().takeIf { it.isNotEmpty() }
        } finally { connection.disconnect() }
    } catch (_: IOException) {
        // Missing notes or a network failure must not prevent downloading a valid update.
        null
    }

    fun download() {
        if (!releaseUpdatesSupported) return
        val manifest = (state as? AppUpdateState.Available)?.manifest ?: return
        state = AppUpdateState.Downloading(manifest, 0)
        worker.execute {
            val directory = File(app.cacheDir, "app-updates")
            val staging = updateStagingFile(app)
            try {
                val stagingDirectory = staging.parentFile!!
                if (!stagingDirectory.isDirectory && !stagingDirectory.mkdirs()) throw IOException("Cannot create download staging")
                val connection = open(manifest.apkUrl)
                try {
                    if (connection.responseCode != 200) throw IOException("APK response rejected")
                    val deadline = System.nanoTime() + 300_000_000_000L
                    var lastProgress = 0L
                    connection.inputStream.use { input ->
                        staging.outputStream().use { output ->
                            copyVerifiedUpdate(input, output, manifest) { completed ->
                                val now = System.nanoTime()
                                if (now > deadline) throw IOException("Deadline exceeded")
                                if (now - lastProgress >= 100_000_000L || completed == manifest.size) {
                                    lastProgress = now
                                    publish(AppUpdateState.Downloading(manifest, completed))
                                }
                            }
                        }
                    }
                } finally { connection.disconnect() }
                validateApk(staging, manifest)
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create update cache")
                val apk = File(directory, "TERM.apk")
                if (!staging.renameTo(apk)) throw IOException("Cannot activate verified APK")
                publish(AppUpdateState.Ready(manifest, apk))
            } catch (error: Exception) {
                staging.delete()
                fail(error)
            }
        }
    }

    @Suppress("DEPRECATION")
    internal fun validateApk(apk: File, manifest: AppUpdateManifest) {
        val pm = app.packageManager
        val installed = pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val candidate = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw UpdateRejected(UpdateRejection.IDENTITY)
        if (candidate.packageName != app.packageName) throw UpdateRejected(UpdateRejection.IDENTITY)
        fun signers(info: PackageInfo) = info.signingInfo?.apkContentsSigners?.map { it.toByteArray().sha256Hex() }?.toSet().orEmpty()
        validateUpdateIdentity(manifest, candidate.packageName, candidate.longVersionCode, candidate.versionName, signers(installed), signers(candidate))
        if ((candidate.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) > Build.VERSION.SDK_INT) throw UpdateRejected(UpdateRejection.INCOMPATIBLE)
        if (candidate.longVersionCode <= installed.longVersionCode) throw UpdateRejected(UpdateRejection.IDENTITY)
    }

    private fun publish(value: AppUpdateState) { handler.post { state = value } }
    private fun fail(error: Exception) = publish(AppUpdateState.Failed(when (error) {
        is UpdateRejected -> when (error.reason) {
            UpdateRejection.SIGNATURE -> R.string.update_wrong_signature
            UpdateRejection.INCOMPATIBLE -> R.string.update_incompatible
            else -> R.string.update_invalid_apk
        }
        is IOException -> R.string.update_network_error
        else -> R.string.update_invalid_manifest
    }))

    /** Fixed public GitHub endpoints, bounded HTTPS-only redirects, no credentials. */
    private fun open(address: String): HttpsURLConnection {
        var url = URL(address)
        repeat(6) {
            if (url.protocol != "https" || (url.port != -1 && url.port != 443) || url.userInfo != null ||
                !(url.host == "github.com" || url.host.endsWith(".githubusercontent.com"))) throw IOException("Redirect rejected")
            val connection = url.openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "TERM/${BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("Accept-Encoding", "identity")
            try {
                if (connection.responseCode in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: throw IOException("Redirect missing")
                    url = URL(url, location)
                    connection.disconnect()
                } else return connection
            } catch (error: Exception) { connection.disconnect(); throw error }
        }
        throw IOException("Too many redirects")
    }
}
