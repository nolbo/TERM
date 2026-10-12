package dev.terminal.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.IntentCompat
import dev.terminal.runtime.lifecycle.VmRuntimeService
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Android share-sheet entry point; it imports content but never owns the VM or a shell. */
class ShareToLinuxActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { importSharedContent(intent) }
            .onSuccess {
                runCatching { startForegroundService(Intent(this, VmRuntimeService::class.java)) }
                    .onFailure { showFailure(it) }
            }
            .onFailure { showFailure(it) }
        finish()
    }

    private fun importSharedContent(intent: Intent) {
        require(intent.action == Intent.ACTION_SEND) { "Unsupported share action" }
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java)
            ?: error("공유된 파일이 없습니다")
        val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.take(120)
            ?.ifBlank { null }
            ?: "shared-${System.currentTimeMillis()}"
        val inbox = File(filesDir, "shared-vms/default/inbox").apply { mkdirs() }
        val requestedTarget = File(inbox, name).canonicalFile
        val target = if (requestedTarget.exists()) {
            File(inbox, "${requestedTarget.nameWithoutExtension}-${System.currentTimeMillis()}" +
                requestedTarget.extension.takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty()).canonicalFile
        } else {
            requestedTarget
        }
        require(target.toPath().startsWith(inbox.canonicalFile.toPath())) { "Invalid shared filename" }
        val staging = File.createTempFile(".share-", ".part", inbox).canonicalFile
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                staging.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_SHARED_BYTES) { "Shared file is too large" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Cannot open shared content")
            runCatching {
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    private fun showFailure(error: Throwable) {
        Toast.makeText(this, "공유 가져오기 실패: ${error.message ?: error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
    }

    private companion object { const val MAX_SHARED_BYTES = 1_073_741_824L }
}
