package dev.terminal.runtime.native

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Only data is extracted. PATH entries are symlinks to loader-approved APK ELFs. */
class NativeToolchain(private val context: Context) {
    private val loader = ApkNativeExecutableLoader(context)

    fun prepare(): Map<String, String> = synchronized(lock) {
        val manifestBytes = context.assets.open("native-cli/manifest.json").use { it.readBytes() }
        val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
        val directory = File(context.noBackupFilesDir, "native-tools/${sha256(manifestBytes)}")
        directory.parentFile!!.mkdirs()
        RandomAccessFile(File(directory.parentFile, "prepare.lock"), "rw").channel.use { channel ->
            channel.lock().use {
                if (!File(directory, "ready").isFile) {
                    val stage = Files.createTempDirectory(directory.parentFile!!.toPath(), ".prepare-").toFile()
                    try {
                        val resources = manifest.getJSONObject("resources")
                        resources.keys().forEach { name ->
                            val target = File(stage, "runtime/$name")
                            require(target.canonicalPath.startsWith(File(stage, "runtime").canonicalPath + "/"))
                            target.parentFile!!.mkdirs()
                            context.assets.open("native-cli/runtime/$name").use { input ->
                                target.outputStream().use { input.copyTo(it) }
                            }
                            require(sha256(target.readBytes()) == resources.getJSONObject(name).getString("sha256")) {
                                "Native runtime resource checksum failed: $name"
                            }
                        }
                        val bin = File(stage, "bin").apply { mkdirs() }
                        (commands + "terminal-usb").forEach { name ->
                            val executable = loader.resolvePackagedExecutable(name).getOrThrow()
                            if (name in commands) {
                                val record = manifest.getJSONObject("executables").getJSONObject(executable.name)
                                require(sha256(executable.readBytes()) == record.getString("sha256")) { "Native CLI checksum failed: $name" }
                            }
                            Files.createSymbolicLink(File(bin, name).toPath(), executable.toPath())
                        }
                        File(stage, "ready").writeText("1\n")
                        check(stage.renameTo(directory)) { "Cannot activate Native runtime resources" }
                    } finally { stage.deleteRecursively() }
                }
                // The UI diagnostics and runtime process share this lock. A
                // repaired alias must never disappear between unlink and exec.
                (commands + "terminal-usb").forEach { name ->
                    val expected = loader.resolvePackagedExecutable(name).getOrThrow().toPath()
                    val link = File(directory, "bin/$name").toPath()
                    if (!Files.isSymbolicLink(link) || Files.readSymbolicLink(link) != expected) {
                        val replacement = File(directory, "bin/.link-${java.util.UUID.randomUUID()}").toPath()
                        try {
                            Files.createSymbolicLink(replacement, expected)
                            Files.move(replacement, link, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        } finally { Files.deleteIfExists(replacement) }
                    }
                }
            }
        }
        val runtime = File(directory, "runtime")
        mapOf("PATH" to File(directory, "bin").absolutePath,
            "LD_LIBRARY_PATH" to context.applicationInfo.nativeLibraryDir,
            "VIM" to runtime.absolutePath, "VIMRUNTIME" to File(runtime, "vim").absolutePath,
            "TERMINFO" to File(runtime, "terminfo").absolutePath,
            "CURL_CA_BUNDLE" to File(runtime, "cacert.crt").absolutePath,
            "SSL_CERT_FILE" to File(runtime, "cacert.crt").absolutePath,
            "OPENSSL_CONF" to "/dev/null")
    }

    companion object {
        val commands = listOf("jq", "curl", "ssh", "ssh-keygen", "rg", "vim")
        private val lock = Any()
        internal fun sha256(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val digits = "0123456789abcdef"
            return CharArray(digest.size * 2) { index ->
                val value = digest[index / 2].toInt() and 0xff
                digits[if (index % 2 == 0) value ushr 4 else value and 15]
            }.concatToString()
        }
    }
}

internal fun nativeEnvironment(home: File, temporary: File, shell: String, rc: File,
    requested: Map<String, String>, tools: Map<String, String>, host: Map<String, String>): Map<String, String> {
    val reserved = setOf("HOME", "TMPDIR", "PATH", "SHELL", "ENV", "LD_LIBRARY_PATH", "VIM", "VIMRUNTIME", "TERMINFO", "OPENSSL_CONF")
    val extraPaths = requested["PATH"].orEmpty().split(':').filter {
        it.startsWith('/') && File(it).isDirectory
    }
    return requested.filterKeys { it !in reserved && it != "DROID_LOGIN_SHELL" &&
        it != dev.terminal.runtime.session.SessionCommand.KEY && !it.startsWith("TERMINAL_USB_") } +
        mapOf("TERM" to (requested["TERM"] ?: "xterm-256color"),
            "COLORTERM" to (requested["COLORTERM"] ?: "truecolor"), "LANG" to (requested["LANG"] ?: "C.UTF-8"),
            "HOME" to home.absolutePath, "TMPDIR" to temporary.absolutePath, "SHELL" to shell,
            "ENV" to rc.absolutePath, "HISTFILE" to (requested["HISTFILE"] ?: File(home, ".sh_history").absolutePath),
            "HISTSIZE" to (requested["HISTSIZE"] ?: "5000")) + tools +
        mapOf("PATH" to (listOfNotNull(tools["PATH"], "/system/bin", "/system/xbin", "/vendor/bin") + extraPaths).distinct().joinToString(":")) + host
}
