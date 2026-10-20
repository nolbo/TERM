package dev.terminal.image

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test

class AvfResumableDownloadTest {
    private val origin = URI("https://example.invalid/release/system.img.gz")
    private val payload = ByteArray(256 * 1024) { (it % 251).toByte() }
    private val compressed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(payload) } }.toByteArray()
    private val expected = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

    private class Connection(uri: URI, val code: Int = 200, val body: ByteArray,
        val headers: Map<String, String> = mapOf("ETag" to "\"version-1\""),
        val length: Long = body.size.toLong(), val failAfter: Int? = null) : HttpURLConnection(uri.toURL()) {
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getContentLengthLong() = length
        override fun getHeaderField(name: String?) = headers[name]
        override fun getInputStream(): InputStream = if (failAfter == null) ByteArrayInputStream(body) else object : InputStream() {
            var position = 0
            override fun read(): Int {
                if (position >= failAfter) throw IOException("connection lost")
                return body[position++].toInt() and 255
            }
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                if (position >= failAfter) throw IOException("connection lost")
                val size = minOf(count, failAfter - position)
                body.copyInto(bytes, offset, position, position + size)
                position += size
                return size
            }
        }
    }

    private fun download(downloader: AvfArtifactDownloader, root: Path, progress: (Long, Long?) -> Unit = { _, _ -> }) {
        downloader.downloadVerified(origin, root.resolve("disk"), payload.size.toLong(), expected,
            root.resolve("cache"), gzip = true, onProgress = progress)
    }

    private fun partial(root: Path, etag: String? = "\"version-1\"") {
        val downloader = AvfArtifactDownloader().apply { openConnection = { uri ->
            Connection(uri, body = compressed, headers = etag?.let { mapOf("ETag" to it) } ?: emptyMap(), failAfter = 13)
        } }
        assertThrows(IOException::class.java) { download(downloader, root) }
        assertFalse(Files.exists(root.resolve("disk")))
    }

    private inline fun fixture(test: (Path) -> Unit) {
        val root = Files.createTempDirectory("avf-resume")
        try { test(root) } finally { root.toFile().deleteRecursively() }
    }

    @Test fun resumesCompressedBytesAcrossHttpsRedirectAndVerifiesExpandedHash() = fixture { root ->
        partial(root)
        val connections = mutableListOf<Connection>()
        val downloader = AvfArtifactDownloader().apply { openConnection = { uri ->
            (if (uri == origin) Connection(uri, 302, byteArrayOf(), mapOf("Location" to "https://cdn.example.invalid/signed"))
            else Connection(uri, 206, compressed.copyOfRange(13, compressed.size), mapOf(
                "ETag" to "\"version-1\"", "Content-Range" to "bytes 13-${compressed.lastIndex}/${compressed.size}")))
                .also { connections += it }
        } }
        val progress = mutableListOf<Long>()
        download(downloader, root) { bytes, _ -> progress += bytes }
        assertArrayEquals(payload, Files.readAllBytes(root.resolve("disk")))
        assertEquals(13L, progress.first())
        assertEquals(compressed.size.toLong(), progress.last())
        assertTrue(connections.all { it.getRequestProperty("Range") == "bytes=13-" &&
            it.getRequestProperty("If-Range") == "\"version-1\"" && it.getRequestProperty("Accept-Encoding") == "identity" && it.disconnected })
    }

    @Test fun changedValidatorOrIgnoredRangeReplacesPartialInsteadOfAppending() = fixture { root ->
        partial(root)
        val response = Connection(origin, body = compressed, headers = mapOf("ETag" to "\"version-2\""))
        download(AvfArtifactDownloader().apply { openConnection = { response } }, root)
        assertEquals("bytes=13-", response.getRequestProperty("Range"))
        assertArrayEquals(payload, Files.readAllBytes(root.resolve("disk")))
    }

    @Test fun transientServerErrorsKeepTheValidatedPartialPrefixForAnotherRetry() = fixture { root ->
        partial(root)
        val unavailable = Connection(origin, 503, byteArrayOf())
        assertThrows(IOException::class.java) { download(AvfArtifactDownloader().apply { openConnection = { unavailable } }, root) }
        val resumed = Connection(origin, 206, compressed.copyOfRange(13, compressed.size), mapOf(
            "ETag" to "\"version-1\"", "Content-Range" to "bytes 13-${compressed.lastIndex}/${compressed.size}"))
        download(AvfArtifactDownloader().apply { openConnection = { resumed } }, root)
        assertEquals("bytes=13-", resumed.getRequestProperty("Range"))
        assertArrayEquals(payload, Files.readAllBytes(root.resolve("disk")))
    }

    @Test fun validGzipWithTheWrongExpandedDigestIsRejectedAndEvicted() = fixture { root ->
        val other = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(ByteArray(payload.size) { 7 }) } }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            download(AvfArtifactDownloader().apply { openConnection = { Connection(it, body = other) } }, root)
        }
        assertFalse(Files.exists(root.resolve("disk")))
        assertFalse(Files.exists(root.resolve("cache/$expected.gz.part")))
    }

    @Test fun missingWeakAndInvalidValidatorsRestartFromZero() {
        for (etag in listOf(null, "W/\"weak\"", "\"bad\r\nheader\"", "\"bad\"quote\"")) fixture { root ->
            partial(root, etag)
            val response = Connection(origin, body = compressed)
            download(AvfArtifactDownloader().apply { openConnection = { response } }, root)
            assertNull(response.getRequestProperty("Range"))
            assertArrayEquals(payload, Files.readAllBytes(root.resolve("disk")))
        }
    }

    @Test fun refusesWrongRangesAndChangedPartialValidatorsBeforeAppending() {
        for (headers in listOf(
            mapOf("Content-Range" to "bytes 12-${compressed.lastIndex}/${compressed.size}", "ETag" to "\"version-1\""),
            mapOf("Content-Range" to "bytes 13-${compressed.lastIndex}/${compressed.size}", "ETag" to "\"changed\""),
            mapOf("Content-Range" to "bytes 13-${compressed.lastIndex}/999999999999", "ETag" to "\"version-1\""),
            mapOf("ETag" to "\"version-1\""))) fixture { root ->
            partial(root)
            val response = Connection(origin, 206, compressed.copyOfRange(13, compressed.size), headers)
            assertThrows(Exception::class.java) { download(AvfArtifactDownloader().apply { openConnection = { response } }, root) }
            assertFalse(Files.exists(root.resolve("disk")))
            assertEquals(13L, Files.size(root.resolve("cache/$expected.gz.part")))
        }
    }

    @Test fun unsatisfiableRangeRestartsOnceWithoutRange() = fixture { root ->
        partial(root)
        val responses = mutableListOf(Connection(origin, 416, byteArrayOf()), Connection(origin, body = compressed))
        val first = responses[0]; val last = responses[1]
        download(AvfArtifactDownloader().apply { openConnection = { responses.removeAt(0) } }, root)
        assertEquals("bytes=13-", first.getRequestProperty("Range"))
        assertNull(last.getRequestProperty("Range"))
        assertArrayEquals(payload, Files.readAllBytes(root.resolve("disk")))
    }

    @Test fun earlyEofIsRetriedAndCompletedRawArtifactsAreRehashedBeforeReuse() = fixture { root ->
        val early = Connection(origin, body = compressed.copyOfRange(0, 13), length = compressed.size.toLong())
        assertThrows(IOException::class.java) { download(AvfArtifactDownloader().apply { openConnection = { early } }, root) }
        val resumed = Connection(origin, 206, compressed.copyOfRange(13, compressed.size), mapOf(
            "ETag" to "\"version-1\"", "Content-Range" to "bytes 13-${compressed.lastIndex}/${compressed.size}"))
        download(AvfArtifactDownloader().apply { openConnection = { resumed } }, root)
        val offline = AvfArtifactDownloader().apply { openConnection = { error("Completed cache must not use network") } }
        download(offline, root)
        Files.writeString(root.resolve("disk"), "corrupt raw artifact")
        download(offline, root) // The verified compressed bytes repair the raw artifact without a transfer.
        assertArrayEquals(payload, Files.readAllBytes(root.resolve("disk")))
    }

    @Test fun corruptedCompressedCacheAndWrongHashesCannotProduceUsableArtifacts() = fixture { root ->
        download(AvfArtifactDownloader().apply { openConnection = { Connection(it, body = compressed) } }, root)
        Files.delete(root.resolve("disk"))
        Files.write(root.resolve("cache/$expected.gz.part"), ByteArray(compressed.size))
        assertThrows(IOException::class.java) { download(AvfArtifactDownloader().apply { openConnection = { error("No network") } }, root) }
        assertFalse(Files.exists(root.resolve("disk")))
        assertFalse(Files.exists(root.resolve("cache/$expected.gz.part")))
        val wrong = compressed.copyOf().also { it[it.lastIndex - 8] = (it[it.lastIndex - 8].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { download(AvfArtifactDownloader().apply { openConnection = { Connection(it, body = wrong) } }, root) }
        assertFalse(Files.exists(root.resolve("disk")))
    }

    @Test fun boundsExpansionAndPreservesOutsideFilesWhenCacheEntriesAreSymlinks() = fixture { root ->
        val outside = Files.writeString(root.resolve("outside"), "keep")
        Files.createDirectory(root.resolve("cache"))
        Files.createSymbolicLink(root.resolve("cache/$expected.gz.part"), outside)
        download(AvfArtifactDownloader().apply { openConnection = { Connection(it, body = compressed) } }, root)
        assertEquals("keep", Files.readString(outside))
        Files.delete(root.resolve("disk"))
        val downloader = AvfArtifactDownloader().apply { openConnection = { Connection(it, body = compressed) } }
        assertThrows(IllegalArgumentException::class.java) {
            downloader.downloadVerified(origin, root.resolve("disk"), compressed.size.toLong() + 1, expected, root.resolve("small"), true)
        }
        assertFalse(Files.exists(root.resolve("disk")))
    }

    @Test fun acceptsBoundedUnknownLengthAndCleansCancelledRawOutput() = fixture { root ->
        download(AvfArtifactDownloader().apply { openConnection = { Connection(it, body = compressed, length = -1) } }, root)
        Files.delete(root.resolve("disk"))
        var calls = 0
        val cancelled = AvfArtifactDownloader { ++calls > 5 }.apply { openConnection = { error("Cached body needs no network") } }
        assertThrows(CancellationException::class.java) { download(cancelled, root) }
        assertFalse(Files.exists(root.resolve("disk")))
    }

    @Test fun resumedDownloadStillRejectsInsecureRedirects() = fixture { root ->
        partial(root)
        val response = Connection(origin, 302, byteArrayOf(), mapOf("Location" to "http://cdn.example.invalid/disk"))
        assertThrows(IllegalArgumentException::class.java) {
            download(AvfArtifactDownloader().apply { openConnection = { response } }, root)
        }
        assertTrue(response.disconnected)
        assertFalse(Files.exists(root.resolve("disk")))
    }
}
