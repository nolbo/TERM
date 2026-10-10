package dev.terminal.app

import android.content.Context
import android.graphics.fonts.Font
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Keep font bytes across app updates and the loss of a document provider or its URI grant. */
internal class CustomTerminalFontStore(context: Context) {
    private val app = context.applicationContext
    private val directory = File(app.filesDir, "terminal-fonts")

    fun import(uri: Uri): File {
        check(directory.mkdirs() || directory.isDirectory)
        val temporary = File.createTempFile("font-", ".tmp", directory)
        try {
            app.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot open font" }
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            val font = Font.Builder(temporary).build()
            require(OpenTypeCmap.codePointRanges(font.buffer, font.ttcIndex).isNotEmpty()) { "Invalid font" }
            val target = file(uri.toString())
            check(temporary.renameTo(target)) { "Cannot store font" }
            return target
        } finally {
            temporary.delete()
        }
    }

    fun load(uri: String): Font {
        val stored = file(uri)
        // Lazily import legacy URI-only entries without changing their identity or priority.
        if (!stored.isFile) import(Uri.parse(uri))
        return Font.Builder(stored).build()
    }

    fun remove(uri: String) {
        file(uri).delete()
    }

    private fun file(uri: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$digest.font")
    }
}
