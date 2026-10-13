package dev.terminal.image

import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** Hashes pin the content even when an HTTPS asset host redirects to its CDN. */
class AvfArtifactDownloader(private val cancelled: () -> Boolean = { false }) {
    internal var openConnection: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection }
    fun download(uri: URI, destination: Path, maxBytes: Long, gzip: Boolean = false,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> }) {
        var next = uri
        repeat(6) { redirect ->
            require(next.scheme == "https" && !next.host.isNullOrBlank() && next.userInfo == null) {
                "Image redirect must use HTTPS"
            }
            checkCancelled()
            val connection = openConnection(next).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 30_000
            }
            try {
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    require(redirect < 5) { "Too many image redirects" }
                    next = next.resolve(connection.getHeaderField("Location") ?: error("Missing image redirect location"))
                } else {
                    if (status == 408 || status == 429 || status >= 500) throw java.io.IOException("Image server unavailable (HTTP $status)")
                    require(status in 200..299) { "Image download failed (HTTP $status)" }
                    require(connection.contentLengthLong <= maxBytes) { "Artifact exceeds approved size limit" }
                    val totalBytes = connection.contentLengthLong.takeIf { it > 0 }
                    onProgress(0, totalBytes)
                    BufferedInputStream(connection.inputStream).use { raw ->
                        val bounded = object : java.io.FilterInputStream(raw) {
                            var bytes = 0L
                            override fun read(): Int = super.read().also { if (it >= 0) count(1) }
                            override fun read(b: ByteArray, off: Int, len: Int): Int = `in`.read(b, off, len).also { if (it > 0) count(it) }
                            private fun count(n: Int) {
                                bytes += n
                                require(bytes <= maxBytes) { "Download exceeds approved size limit" }
                                onProgress(bytes, totalBytes)
                            }
                        }
                        val input = if (gzip) GZIPInputStream(bounded) else bounded
                        input.use {
                            AvfSparseFiles.copy(it, destination, maxBytes, ::checkCancelled)
                        }
                    }
                    return
                }
            } finally { connection.disconnect() }
        }
    }

    private fun checkCancelled() {
        if (cancelled()) throw java.util.concurrent.CancellationException("Image installation cancelled")
    }

}
