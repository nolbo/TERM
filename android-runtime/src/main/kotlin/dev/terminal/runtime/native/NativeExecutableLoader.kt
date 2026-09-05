package dev.terminal.runtime.native

import android.content.Context
import java.io.File

/**
 * The only path through which optional native runtime executables may be selected.
 * targetSdk 35 forbids execve from writable app data, so downloaded files are never accepted.
 */
interface NativeExecutableLoader {
    fun resolvePackagedExecutable(name: String): Result<File>
}

class ApkNativeExecutableLoader(context: Context) : NativeExecutableLoader {
    private val resolver = PackagedExecutableResolver(
        File(context.applicationInfo.nativeLibraryDir),
        supportedAbis = { android.os.Build.SUPPORTED_ABIS.toList() },
    )

    override fun resolvePackagedExecutable(name: String): Result<File> = resolver.resolvePackagedExecutable(name)
}

internal class PackagedExecutableResolver(
    nativeLibraryDirectory: File,
    private val supportedAbis: () -> List<String>,
) : NativeExecutableLoader {
    private val nativeLibraryDirectory = nativeLibraryDirectory.canonicalFile

    override fun resolvePackagedExecutable(name: String): Result<File> = runCatching {
        require(name in ALLOWED_EXECUTABLES) { "Native executable is not allow-listed" }
        val candidate = File(nativeLibraryDirectory, "lib$name.so").canonicalFile
        require(candidate.parentFile == nativeLibraryDirectory) { "Native executable escaped APK library directory" }
        require(candidate.isFile && candidate.canExecute()) {
            "$name is not packaged or executable for ${supportedAbis().joinToString()}"
        }
        candidate
    }

    private companion object {
        val ALLOWED_EXECUTABLES = setOf("proot", "proot-loader")
    }
}
