package dev.terminal.runtime.rootless

import android.content.Context
import dev.terminal.runtime.native.ApkNativeExecutableLoader
import dev.terminal.runtime.native.NativeExecutableLoader
import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Describes the installed APK engine only, never a distribution's files or state. */
data class RootlessEngineInfo(
    val arm64: Boolean,
    val prootAvailable: Boolean,
    val loaderAvailable: Boolean,
    val acceleration: Boolean,
    val faststat: Boolean,
    val prootVersion: String?,
) {
    fun toJson() = JSONObject().put("arm64", arm64).put("prootAvailable", prootAvailable)
        .put("loaderAvailable", loaderAvailable).put("acceleration", acceleration).put("faststat", faststat)
        .put("prootVersion", prootVersion ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject) = RootlessEngineInfo(json.getBoolean("arm64"), json.getBoolean("prootAvailable"),
            json.getBoolean("loaderAvailable"), json.getBoolean("acceleration"), json.getBoolean("faststat"),
            json.optString("prootVersion").takeUnless { it.isBlank() || it == "null" })

        internal fun inspect(context: Context): RootlessEngineInfo = inspect(
            android.os.Build.SUPPORTED_ABIS.toList(), ApkNativeExecutableLoader(context),
            File(context.applicationInfo.nativeLibraryDir, "libproot-faststat.so"),
            dev.terminal.runtime.BuildConfig.ROOTLESS_ACCELERATION,
        )

        internal fun inspect(abis: List<String>, loader: NativeExecutableLoader, library: File,
            accelerationIncluded: Boolean, version: (File) -> String? = ::probeVersion): RootlessEngineInfo {
            val arm64 = "arm64-v8a" in abis
            val proot = if (arm64) loader.resolvePackagedExecutable("proot").getOrNull() else null
            val loaderAvailable = arm64 && loader.resolvePackagedExecutable("proot-loader").isSuccess
            val available = proot != null && loaderAvailable
            val acceleration = available && accelerationIncluded && library.isFile && library.canRead()
            val faststat = acceleration && RootlessAccelerationProfile(library).hostEnvironment.contains("PROOT_FASTSTAT=2")
            return RootlessEngineInfo(arm64, available, loaderAvailable, acceleration, faststat,
                proot?.let { version(it) })
        }

        private fun probeVersion(proot: File): String? = runCatching {
            val process = ProcessBuilder(proot.path, "--version").redirectErrorStream(true).start()
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) return@runCatching null
                val output = process.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(2048)
                    val count = reader.read(buffer)
                    if (count < 0) "" else String(buffer, 0, count)
                }
                Regex("(?i)\\bproot\\s+(\\d+(?:\\.\\d+)+)").find(output)?.groupValues?.get(1)
            } finally {
                if (process.isAlive) process.destroyForcibly()
                process.inputStream.close()
                process.outputStream.close()
                process.errorStream.close()
            }
        }.getOrNull()
    }
}
