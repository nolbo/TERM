package dev.terminal.image

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test

class AvfArtifactDownloaderTest {
    private val origin = URI("https://example.invalid/v1/system.img.gz")
    private class Connection(uri: URI, private val status: Int, private val bytes: ByteArray = byteArrayOf(),
        private val location: String? = null) : HttpURLConnection(uri.toURL()) {
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(bytes)
        override fun getContentLengthLong() = bytes.size.toLong()
        override fun getHeaderField(name: String?) = if (name == "Location") location else null
    }

    @Test fun followsHttpsCdnRedirectAndDecompressesVerifiedPayload() {
        val payload = "Debian disk data".repeat(100).toByteArray()
        val compressed = ByteArrayOutputStream().also { bytes -> GZIPOutputStream(bytes).use { it.write(payload) } }.toByteArray()
        val connections = mutableListOf<Connection>()
        val downloader = AvfArtifactDownloader().apply {
            openConnection = { uri ->
                (if (uri == origin) Connection(uri, 302, location = "https://cdn.example.invalid/disk")
                else Connection(uri, 200, compressed)).also { connections.add(it) }
            }
        }
        val destination = Files.createTempFile("debian-download", ".img")
        try {
            downloader.download(origin, destination, payload.size.toLong(), gzip = true)
            assertArrayEquals(payload, Files.readAllBytes(destination))
            assertEquals(2, connections.size)
            assertTrue(connections.all { it.disconnected })
        } finally { Files.deleteIfExists(destination) }
    }

    @Test fun refusesDowngradeCredentialsAndRedirectLoops() {
        for (location in listOf("http://cdn.example.invalid/disk", "https://user@cdn.example.invalid/disk", origin.toString())) {
            var requests = 0
            val downloader = AvfArtifactDownloader().apply {
                openConnection = { uri -> requests++; Connection(uri, 302, location = location) }
            }
            val destination = Files.createTempFile("debian-reject", ".img")
            try {
                assertThrows(IllegalArgumentException::class.java) { downloader.download(origin, destination, 1024) }
                assertTrue(requests <= 6)
                assertEquals(0, Files.size(destination))
            } finally { Files.deleteIfExists(destination) }
        }
    }

    @Test fun refusesGzipExpansionBeyondApprovedLimit() {
        val compressed = ByteArrayOutputStream().also { bytes -> GZIPOutputStream(bytes).use { it.write(ByteArray(4096)) } }.toByteArray()
        val downloader = AvfArtifactDownloader().apply { openConnection = { uri -> Connection(uri, 200, compressed) } }
        val destination = Files.createTempFile("debian-bomb", ".img")
        try {
            assertThrows(IllegalArgumentException::class.java) { downloader.download(origin, destination, 512, gzip = true) }
        } finally { Files.deleteIfExists(destination) }
    }

    @Test fun progressCountsCompressedTransferAndKeepsUnknownLengthIndeterminate() {
        val payload = ByteArray(8192) { 1 }
        val compressed = ByteArrayOutputStream().also { bytes -> GZIPOutputStream(bytes).use { it.write(payload) } }.toByteArray()
        for (knownLength in listOf(true, false)) {
            val downloader = AvfArtifactDownloader().apply {
                openConnection = { uri -> object : HttpURLConnection(uri.toURL()) {
                    override fun connect() = Unit
                    override fun disconnect() = Unit
                    override fun usingProxy() = false
                    override fun getResponseCode() = 200
                    override fun getInputStream() = ByteArrayInputStream(compressed)
                    override fun getContentLengthLong() = if (knownLength) compressed.size.toLong() else -1L
                } }
            }
            val updates = mutableListOf<Pair<Long, Long?>>()
            val destination = Files.createTempFile("debian-progress", ".img")
            try {
                downloader.download(origin, destination, payload.size.toLong(), gzip = true) { bytes, total -> updates += bytes to total }
                assertEquals(0L, updates.first().first)
                assertEquals(compressed.size.toLong(), updates.last().first)
                assertTrue(updates.zipWithNext().all { (before, after) -> before.first <= after.first })
                assertTrue(updates.all { it.second == if (knownLength) compressed.size.toLong() else null })
                assertArrayEquals(payload, Files.readAllBytes(destination))
            } finally { Files.deleteIfExists(destination) }
        }
    }
}
