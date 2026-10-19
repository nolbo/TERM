package dev.terminal.app

import android.os.ParcelFileDescriptor
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.protocol.SftpWire
import dev.terminal.protocol.SftpWire.bytes
import dev.terminal.protocol.SftpWire.text
import dev.terminal.runtime.storage.ScopedDirectory
import dev.terminal.runtime.storage.ScopedSftpServer
import dev.terminal.runtime.storage.SharedFolder
import dev.terminal.runtime.storage.SharedFolderRegistry
import dev.terminal.runtime.vsock.ParcelFileDescriptorByteStream
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivateShareBrokerDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(work: (File) -> Unit) {
        val root = Files.createTempDirectory(context.filesDir.canonicalFile.toPath(), "private-share-test-").toFile()
        try { work(root) } finally { ScopedDirectory.open(root).use { it.clear() }; root.delete() }
    }
    private class Client(private val stream: ParcelFileDescriptorByteStream) : Closeable {
        private val output = DataOutputStream(stream.output)
        private val input = DataInputStream(stream.input)
        private var sequence = 0
        init {
            SftpWire.send(output) { writeByte(1); writeInt(3) }
            val version = requireNotNull(SftpWire.read(input))
            assertEquals(2, version.input.readUnsignedByte()); assertEquals(3, version.input.readInt())
        }
        fun request(type: Int, body: DataOutputStream.() -> Unit = {}): SftpWire.Packet {
            val id = ++sequence
            SftpWire.send(output) { writeByte(type); writeInt(id); body() }
            return requireNotNull(SftpWire.read(input)).also {
                val response = it.input.readUnsignedByte(); assertEquals(id, it.input.readInt())
                // Preserve response type for the assertion helpers.
                lastType = response
            }
        }
        var lastType = 0
        fun ok(type: Int, body: DataOutputStream.() -> Unit = {}) {
            val response = request(type, body); assertEquals(101, lastType); assertEquals(0, response.input.readInt())
        }
        fun open(path: String, flags: Int): String {
            val response = request(3) { text(path); writeInt(flags); writeInt(0) }
            assertEquals(102, lastType); return response.text(64)
        }
        fun read(key: String, size: Int): ByteArray {
            val response = request(5) { text(key); writeLong(0); writeInt(size) }
            assertEquals(103, lastType); return response.bytes(SftpWire.MAX_DATA)
        }
        fun reject(type: Int, body: DataOutputStream.() -> Unit) {
            val response = request(type, body); assertEquals(101, lastType); assertNotEquals(0, response.input.readInt())
        }
        override fun close() = stream.close()
    }
    private fun session(root: File, work: (Client) -> Unit) {
        val pair = ParcelFileDescriptor.createSocketPair()
        val server = ParcelFileDescriptorByteStream(pair[0])
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try { server.use { stream -> ScopedSftpServer(root).use { it.serve(stream.input, stream.output) } } }
            catch (error: Throwable) { failure.set(error) }
        }
        worker.start()
        try { Client(ParcelFileDescriptorByteStream(pair[1])).use(work) }
        finally { server.close(); worker.join(3_000); assertFalse(worker.isAlive) }
        failure.get()?.let { if (it !is IOException) throw AssertionError("Broker server failed", it) }
    }

    @Test fun liveWritesRenameAndHostUpdatesUseOnePrivateTreeWithoutStoragePermission() = fixture { root ->
        session(root) { client ->
            client.ok(14) { text("/Code"); writeInt(0) }
            val key = client.open("/Code/data", 2 or 8 or 32)
            val bytes = ByteArray(100_000) { it.toByte() }
            client.ok(6) { text(key); writeLong(0); bytes(bytes) }
            client.ok(200) { text("fsync@openssh.com"); text(key) }
            client.ok(4) { text(key) }
            assertArrayEquals(bytes, File(root, "Code/data").readBytes())
            client.ok(200) { text("posix-rename@openssh.com"); text("Code/data"); text("Code/renamed") }
            val reader = client.open("/Code/renamed", 1)
            File(root, "Code/renamed").writeText("host update")
            assertEquals("host update", client.read(reader, 100).toString(Charsets.UTF_8))
            client.ok(4) { text(reader) }
            client.ok(13) { text("/Code/renamed") }
            assertFalse(File(root, "Code/renamed").exists())
        }
    }

    @Test fun traversalSymlinksHardlinksAndMalformedPacketsCannotAlterOutsideData() = fixture { root ->
        val outside = File(root.parentFile, "outside-${root.name}").apply { writeText("outside data") }
        try {
            Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
            Os.link(outside.path, File(root, "hardlink").path)
            session(root) { client ->
                for (path in listOf("../${outside.name}", "link", "hardlink")) {
                    client.reject(3) { text(path); writeInt(2 or 16); writeInt(0) }
                    assertEquals("outside data", outside.readText())
                }
                client.reject(3) { text("malformed"); writeInt(2 or 8); writeInt(0); writeByte(9) }
                assertFalse(File(root, "malformed").exists())
                client.reject(18) { text("link"); text("../escape") }
                assertTrue(Files.isSymbolicLink(File(root, "link").toPath()))
                client.ok(20) { text("/guest/path"); text("guest-link") }
                val result = client.request(19) { text("guest-link") }
                assertEquals(104, client.lastType); assertEquals(1, result.input.readInt()); assertEquals("/guest/path", result.text())
                // Replacing a symlink replaces its entry, never the file it points at.
                File(root, "replacement").writeText("replacement")
                client.ok(200) { text("posix-rename@openssh.com"); text("replacement"); text("link") }
                assertEquals("outside data", outside.readText())
                assertEquals("replacement", File(root, "link").readText())
            }
        } finally { outside.delete() }
    }

    @Test fun truncateTimesAndModeZeroRecoveryOperateOnPinnedFiles() = fixture { root ->
        File(root, "data").writeText("abcdef")
        session(root) { client ->
            client.ok(9) { text("data"); writeInt(1); writeLong(3) }
            assertEquals("abc", File(root, "data").readText())
            client.ok(9) { text("data"); writeInt(4); writeInt(0) }
            client.ok(9) { text("data"); writeInt(4 or 8); writeInt(0x1a0); writeInt(1_700_000_000); writeInt(1_700_000_001) }
            val stat = Os.stat(File(root, "data").path)
            assertEquals(0x1a0, stat.st_mode and 0x1ff)
            assertEquals(1_700_000_001L, stat.st_mtime)
            client.request(200) { text("statvfs@openssh.com"); text("data") }
            assertEquals(201, client.lastType)
        }
    }

    @Test fun restoringPublicBackingUsesLatestDataAndSurvivesMetadataFailure() = fixture { files ->
        var saved: String? = null
        var failWrite = false
        var granted = true
        val registry = SharedFolderRegistry(files, { saved }, {
            if (failWrite) throw IOException("metadata failure") else saved = it
        }, { File(files, "old-public") }, { granted })
        val folder = SharedFolder(UUID.randomUUID().toString(), "Code", emptySet())
        registry.save(folder)
        val original = registry.directory(folder)
        File(original, "data").writeText("stale original")
        registry.promoteToAndroidStorage(folder)
        val publicFolder = registry.requireFolder(folder.id)
        val public = registry.directory(publicFolder)
        File(public, "data").writeText("latest public data")
        val oldMarker = File(files, ".shared-folder-promotions/${folder.id}").apply { writeText("") }
        failWrite = true
        assertTrue(runCatching { registry.restorePrivateStorage(publicFolder) }.isFailure)
        assertTrue(registry.requireFolder(folder.id).androidStorage)
        File(public, "data").writeText("updated during retry")
        failWrite = false
        registry.restorePrivateStorage(publicFolder)
        assertFalse(registry.requireFolder(folder.id).androidStorage)
        assertFalse(oldMarker.exists())
        granted = false
        assertEquals("updated during retry", File(registry.directory(registry.requireFolder(folder.id)), "data").readText())
        assertEquals("stale original", File(registry.root, ".original-${folder.id}/data").readText())
        registry.delete(folder.id)
        assertEquals("updated during retry", File(public, "data").readText())
        assertFalse(original.exists())
    }

    @Test fun missingPublicSourceAndLinksNeverPublishAStalePrivateBackup() = fixture { files ->
        var saved: String? = null
        val registry = SharedFolderRegistry(files, { saved }, { saved = it }, { File(files, "old-public") }, { true })
        val folder = SharedFolder(UUID.randomUUID().toString(), "Code", emptySet())
        registry.save(folder)
        File(registry.directory(folder), "data").writeText("stale original")
        registry.promoteToAndroidStorage(folder)
        val publicFolder = registry.requireFolder(folder.id)
        val public = registry.directory(publicFolder)
        val outside = File(files, "outside").apply { writeText("outside") }
        Files.createSymbolicLink(File(public, "link").toPath(), outside.toPath())
        assertTrue(runCatching { registry.restorePrivateStorage(publicFolder) }.isFailure)
        assertTrue(registry.requireFolder(folder.id).androidStorage)
        Files.delete(File(public, "link").toPath())
        ScopedDirectory.open(public).use { it.clear() }; public.delete()
        assertTrue(runCatching { registry.restorePrivateStorage(publicFolder) }.isFailure)
        assertFalse(public.exists())
        assertTrue(registry.requireFolder(folder.id).androidStorage)
        assertEquals("stale original", File(registry.root, "${folder.id}/data").readText())
    }

    /** Optional interop with the host OpenSSH sftp client, forwarded only to device loopback. */
    @Test fun externalSftpClientInterop() = fixture { root ->
        assumeTrue(InstrumentationRegistry.getArguments().getString("sftpInterop") == "true")
        ServerSocket(40590, 1, InetAddress.getLoopbackAddress()).use { listener ->
            listener.soTimeout = 45_000
            listener.accept().use { socket ->
                socket.soTimeout = 30_000
                ScopedSftpServer(root).use { it.serve(socket.getInputStream(), socket.getOutputStream()) }
            }
        }
    }
}
