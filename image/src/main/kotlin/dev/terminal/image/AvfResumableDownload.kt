package dev.terminal.image

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.nio.file.StandardOpenOption.*
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.CancellationException
import java.util.zip.GZIPInputStream

/** A cache is private to one WorkManager attempt; it never authenticates a raw file by metadata alone. */
internal class AvfResumableDownload(private val open: (URI) -> HttpURLConnection,
    private val checkCancellation: () -> Unit) {
    private data class State(val etag: String?, val total: Long)

    fun download(uri: URI, destination: Path, maxBytes: Long, expected: String, cache: Path, gzip: Boolean,
        progress: (Long, Long?) -> Unit) {
        require(expected.matches(Regex("[0-9a-f]{64}")) && maxBytes > 0)
        checkCancellation()
        https(uri)
        require(!Files.isSymbolicLink(cache)) { "Download cache is a symlink" }
        Files.createDirectories(cache)
        val key = expected + if (gzip) ".gz" else ".raw"
        val part = cache.resolve("$key.part")
        val metadata = cache.resolve("$key.properties")
        // A worker retry can reuse a completed raw artifact, but only after hashing its current bytes.
        if (Files.exists(destination, NOFOLLOW_LINKS)) {
            if (Files.isRegularFile(destination, NOFOLLOW_LINKS) && Files.size(destination) in 1..maxBytes &&
                AvfImageVerifier.sha256(destination, checkCancellation) == expected) return
            require(!Files.isDirectory(destination, NOFOLLOW_LINKS)) { "Artifact is a directory" }
            Files.delete(destination)
        }
        var state = readState(metadata, uri, maxBytes)
        if (!Files.isRegularFile(part, NOFOLLOW_LINKS) || Files.size(part) !in 1..maxBytes ||
            state == null || (state.total >= 0 && Files.size(part) > state.total)) {
            discard(part, metadata)
            state = null
        }
        val complete = state != null && state.total > 0 && Files.size(part) == state.total
        if (!complete) transfer(uri, part, metadata, state, maxBytes, progress)
        else progress(Files.size(part), Files.size(part))
        try {
            checkCancellation()
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(part, NOFOLLOW_LINKS).buffered().use { raw ->
                val expanded = if (gzip) GZIPInputStream(raw, 64 * 1024) else raw
                DigestInputStream(expanded, digest).use { input ->
                    AvfSparseFiles.copy(input, destination, maxBytes, checkCancellation)
                }
            }
            require(digest.digest().joinToString("") { "%02x".format(it) } == expected) {
                "Downloaded artifact checksum does not match manifest"
            }
        } catch (error: Exception) {
            Files.deleteIfExists(destination)
            if (error !is CancellationException) discard(part, metadata)
            throw error
        }
    }

    private fun transfer(uri: URI, part: Path, metadata: Path, initial: State?, max: Long,
        progress: (Long, Long?) -> Unit) {
        var state = initial
        var restarted = false
        // Bound servers that repeatedly return tiny partial responses.
        repeat(64) {
            checkCancellation()
            val offset = if (state?.etag != null && Files.isRegularFile(part, NOFOLLOW_LINKS)) Files.size(part) else 0L
            val connection = connect(uri, offset, state?.etag)
            try {
                val status = connection.responseCode
                if (status == 416 && offset > 0 && !restarted) {
                    discard(part, metadata); state = null; restarted = true
                    return@repeat
                }
                if (status == 408 || status == 429 || status >= 500) throw IOException("Image server unavailable (HTTP $status)")
                require(status == 200 || (status == 206 && offset > 0)) { "Image download failed (HTTP $status)" }
                require(connection.getHeaderField("Content-Encoding").let { it == null || it.equals("identity", true) }) {
                    "Unexpected image HTTP content encoding"
                }
                val etag = strongEtag(connection.getHeaderField("ETag"))
                val contentLength = connection.contentLengthLong
                require(contentLength <= max) { "Artifact exceeds approved size limit" }
                val start: Long
                val total: Long
                val responseBytes: Long
                if (status == 206) {
                    val range = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
                        .matchEntire(connection.getHeaderField("Content-Range") ?: "")
                        ?: throw IOException("Invalid image Content-Range")
                    val (first, last, length) = range.destructured
                    start = first.toLong(); total = length.toLong()
                    val end = last.toLong()
                    require(start == offset && end >= start && end < total && total <= max &&
                        (state!!.total < 0 || total == state!!.total) && etag == state!!.etag) {
                        "Image range or validator changed"
                    }
                    responseBytes = end - start + 1
                    require(contentLength < 0 || contentLength == responseBytes) { "Invalid image range length" }
                } else {
                    // If-Range mismatch or ignored Range: this is a whole new representation.
                    start = 0; total = contentLength; responseBytes = contentLength
                }
                val next = State(etag, total)
                var received = 0L
                FileChannel.open(part, CREATE, WRITE, NOFOLLOW_LINKS).use { output ->
                    output.truncate(start); output.position(start)
                    // Reset old bytes before publishing a new validator, including process death here.
                    output.force(true)
                    writeState(metadata, uri, next)
                    progress(start, total.takeIf { it > 0 })
                    try {
                        connection.inputStream.buffered().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                checkCancellation()
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(count.toLong() <= max - start - received) { "Download exceeds approved size limit" }
                                if (responseBytes >= 0) require(count.toLong() <= responseBytes - received) { "Image response exceeds declared length" }
                                val bytes = ByteBuffer.wrap(buffer, 0, count)
                                while (bytes.hasRemaining()) output.write(bytes)
                                received += count
                                progress(start + received, total.takeIf { it > 0 })
                            }
                        }
                    } finally { output.force(true) }
                }
                if (responseBytes >= 0 && received != responseBytes) throw IOException("Image download was interrupted")
                val size = start + received
                require(size > 0) { "Empty image artifact" }
                if (total < 0 || size == total) {
                    writeState(metadata, uri, State(etag, size))
                    progress(size, size)
                    return
                }
                state = next
            } finally { connection.disconnect() }
        }
        throw IOException("Too many partial image responses")
    }

    private fun connect(uri: URI, offset: Long, etag: String?): HttpURLConnection {
        var next = uri
        repeat(6) { redirect ->
            https(next); checkCancellation()
            val connection = open(next).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000; readTimeout = 30_000
                setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0) {
                    setRequestProperty("Range", "bytes=$offset-")
                    setRequestProperty("If-Range", etag!!)
                }
            }
            try {
                if (connection.responseCode !in listOf(301, 302, 303, 307, 308)) return connection
                require(redirect < 5) { "Too many image redirects" }
                next = next.resolve(connection.getHeaderField("Location") ?: error("Missing image redirect location"))
            } catch (error: Exception) { connection.disconnect(); throw error }
            connection.disconnect()
        }
        error("Too many image redirects")
    }

    private fun readState(path: Path, uri: URI, max: Long): State? = runCatching {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.size(path) > 8192) return null
        val values = Properties().also { p -> Files.newInputStream(path, NOFOLLOW_LINKS).use(p::load) }
        if (values.getProperty("uri") != uri.toASCIIString()) return null
        val total = values.getProperty("total").toLong()
        require(total in -1..max)
        State(strongEtag(values.getProperty("etag")), total)
    }.getOrNull()

    private fun writeState(path: Path, uri: URI, state: State) {
        val values = Properties().apply {
            setProperty("uri", uri.toASCIIString()); setProperty("total", state.total.toString())
            state.etag?.let { setProperty("etag", it) }
        }
        val temporary = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.deleteIfExists(temporary)
        try {
            Files.newOutputStream(temporary, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { values.store(it, null) }
            FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS).use { it.force(true) }
            try { Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, path, REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun discard(part: Path, metadata: Path) { Files.deleteIfExists(part); Files.deleteIfExists(metadata) }
    private fun strongEtag(value: String?): String? = value?.takeIf {
        it.length in 2..1024 && it.startsWith('"') && it.endsWith('"') &&
            it.substring(1, it.length - 1).all { c -> c != '"' && c.code >= 0x21 && c.code != 0x7f }
    }
    private fun https(uri: URI) {
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.toASCIIString().length <= 2048) {
            "Image URL must use credential-free HTTPS"
        }
    }
}
