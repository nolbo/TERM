package dev.terminal.app.update

import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

internal const val UPDATE_REPOSITORY = "nolbo/TERM"
internal const val UPDATE_MANIFEST_URL = "https://github.com/$UPDATE_REPOSITORY/releases/latest/download/update.json"
internal const val MAX_UPDATE_APK_BYTES = 128L * 1024 * 1024
internal const val MAX_UPDATE_MANIFEST_BYTES = 16 * 1024

internal data class AppUpdateManifest(
    val commit: String,
    val versionCode: Int,
    val versionName: String,
    val size: Long,
    val sha256: String,
    val minSdk: Int,
) {
    val apkUrl: String get() = "https://github.com/$UPDATE_REPOSITORY/releases/download/main-$commit/TERM.apk"

    companion object {
        fun parse(text: String): AppUpdateManifest {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_UPDATE_MANIFEST_BYTES)
            val json = JSONObject(text)
            fun integer(key: String): Long {
                val value = json.get(key)
                require(value is Number && value.toString().matches(Regex("[0-9]+")))
                return value.toString().toLong()
            }
            require(integer("schemaVersion") == 1L)
            require(json.get("applicationId") == "dev.terminal.app")
            val commit = json.getString("commit")
            require(commit.matches(Regex("[0-9a-f]{40}")))
            val code = integer("versionCode")
            require(code in 1..Int.MAX_VALUE.toLong())
            val name = json.getString("versionName")
            require(name.length <= 96 && name.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+\\+main\\.$code\\.${commit.take(12)}")))
            val size = integer("size")
            require(size in 1..MAX_UPDATE_APK_BYTES)
            val sha256 = json.getString("sha256")
            require(sha256.matches(Regex("[0-9a-f]{64}")))
            val minSdk = integer("minSdk")
            require(minSdk in 1..1000)
            return AppUpdateManifest(commit, code.toInt(), name, size, sha256, minSdk.toInt())
        }
    }
}

internal enum class UpdateAvailability { CURRENT, AVAILABLE, INCOMPATIBLE, NON_FORWARD }

internal fun updateAvailability(installedCode: Int, installedCommit: String, deviceSdk: Int, update: AppUpdateManifest): UpdateAvailability = when {
    update.versionCode < installedCode || update.commit == installedCommit -> UpdateAvailability.CURRENT
    update.versionCode == installedCode -> UpdateAvailability.NON_FORWARD
    update.minSdk > deviceSdk -> UpdateAvailability.INCOMPATIBLE
    else -> UpdateAvailability.AVAILABLE
}

internal enum class UpdateRejection { INTEGRITY, IDENTITY, SIGNATURE, INCOMPATIBLE }
internal class UpdateRejected(val reason: UpdateRejection) : Exception(reason.name)

internal fun validateUpdateIdentity(
    manifest: AppUpdateManifest,
    packageName: String,
    versionCode: Long,
    versionName: String?,
    installedSigners: Set<String>,
    updateSigners: Set<String>,
) {
    if (packageName != "dev.terminal.app" || versionCode != manifest.versionCode.toLong() || versionName != manifest.versionName) {
        throw UpdateRejected(UpdateRejection.IDENTITY)
    }
    if (installedSigners.isEmpty() || updateSigners.isEmpty() || installedSigners != updateSigners) {
        throw UpdateRejected(UpdateRejection.SIGNATURE)
    }
}

internal fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256").digest(this).hex()
internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** A remote manifest is only a claim: enforce exact bytes and hash before parsing an APK. */
internal fun copyVerifiedUpdate(input: InputStream, output: OutputStream, manifest: AppUpdateManifest, progress: (Long) -> Unit = {}) {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var copied = 0L
    while (true) {
        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), manifest.size - copied + 1).toInt())
        if (count < 0) break
        if (count == 0) continue
        copied += count
        if (copied > manifest.size) throw UpdateRejected(UpdateRejection.INTEGRITY)
        digest.update(buffer, 0, count)
        output.write(buffer, 0, count)
        progress(copied)
    }
    if (copied != manifest.size || digest.digest().hex() != manifest.sha256) throw UpdateRejected(UpdateRejection.INTEGRITY)
}
