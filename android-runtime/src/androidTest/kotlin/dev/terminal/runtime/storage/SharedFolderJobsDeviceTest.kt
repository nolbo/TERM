package dev.terminal.runtime.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import dev.terminal.runtime.vsock.ParcelFileDescriptorByteStream
import dev.terminal.protocol.FileTransfer
import dev.terminal.runtime.api.GuestByteStream
import dev.terminal.runtime.session.EnvironmentId
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the real Android/JNI publication path with deterministic guest streams. */
@RunWith(AndroidJUnit4::class)
class SharedFolderJobsDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class Stream(override val input: InputStream) : GuestByteStream {
        override val output = ByteArrayOutputStream()
        override fun close() { input.close() }
    }
    private class BlockingInput(private val prefix: ByteArrayInputStream) : InputStream() {
        val waiting = CountDownLatch(1)
        private val closed = CountDownLatch(1)
        override fun read(): Int = if (prefix.available() > 0) prefix.read() else {
            waiting.countDown(); closed.await(); throw IOException("Operation connection closed")
        }
        override fun read(bytes: ByteArray, off: Int, len: Int): Int = if (prefix.available() > 0) prefix.read(bytes, off, len) else read()
        override fun close() { closed.countDown() }
    }
    private fun response(bytes: ByteArray, digest: ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes), size: Long = bytes.size.toLong()): ByteArray =
        ByteArrayOutputStream().also { raw -> DataOutputStream(raw).use { it.writeInt(FileTransfer.MAGIC); it.writeByte(0); it.writeLong(size); it.write(bytes); it.write(digest) } }.toByteArray()
    private suspend fun finished(jobs: SharedFolderJobs, id: String): JSONObject = withTimeout(10_000) {
        while (true) {
            val records = JSONArray(jobs.snapshots())
            val record = (0 until records.length()).map { records.getJSONObject(it) }.firstOrNull { it.getString("id") == id }
            if (record != null && record.getString("state") != "RUNNING" && !jobs.hasActive()) return@withTimeout record
            delay(20)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
    private fun request(folder: SharedFolder, vm: EnvironmentId, path: String) = JSONObject().put("mode", "FROM_VM")
        .put("folderId", folder.id).put("environmentId", vm.value).put("path", path).toString()

    @Test fun closingRealSocketTransportUnblocksPendingReadAndIsIdempotent() {
        val local = FileDescriptor(); val remote = FileDescriptor()
        Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_STREAM, 0, local, remote)
        val stream = ParcelFileDescriptorByteStream(ParcelFileDescriptor.dup(local))
        Os.close(local)
        val entered = CountDownLatch(1); val returned = CountDownLatch(1)
        val reader = Thread {
            entered.countDown()
            try { stream.input.read() } catch (_: IOException) { /* Expected cancellation. */ }
            finally { returned.countDown() }
        }
        try {
            reader.start()
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(returned.await(100, TimeUnit.MILLISECONDS))
            stream.close(); stream.close() // Concurrent cancel/finally paths may both close.
            assertTrue("A real kernel read must return after cancellation", returned.await(2, TimeUnit.SECONDS))
            assertTrue(remote.valid())
        } finally {
            stream.close(); Os.shutdown(remote, OsConstants.SHUT_RDWR); Os.close(remote)
            reader.join(2_000)
        }
    }

    @Test fun verifiedCopiesRejectCorruptionOversizeOverwriteAndUnconnectedVms() = runBlocking {
        val files = Files.createTempDirectory(context.filesDir.canonicalFile.toPath(), "shared-copy-test-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var saved: String? = null
        val folders = SharedFolderRegistry(files, { saved }, { saved = it })
        val vm = EnvironmentId.create()
        val folder = SharedFolder(UUID.randomUUID().toString(), "Project", setOf(vm))
        folders.save(folder)
        val bytes = ByteArray(200_000) { it.toByte() }
        var wire = response(bytes)
        var opened = 0
        val jobs = SharedFolderJobs(context, scope, folders, { emptyList() }, { _, work -> work() },
            { selected -> assertEquals(vm, selected); opened++; Stream(wire.inputStream()) }, { work -> work() })
        try {
            val rejected = jobs.submit(UUID.randomUUID().toString(), request(folder, EnvironmentId.create(), "private"))
            assertTrue(rejected.isNotEmpty()); assertEquals(0, opened)
            val valid = UUID.randomUUID().toString()
            assertEquals("", jobs.submit(valid, request(folder, vm, "dir/result")))
            assertEquals("COMPLETED", finished(jobs, valid).getString("state"))
            assertArrayEquals(bytes, File(folders.directory(folder), "dir/result").readBytes())
            for ((path, response) in listOf("dir/result" to response(byteArrayOf(9)), "corrupt" to response(bytes, ByteArray(32)),
                "oversized" to response(byteArrayOf(), size = FileTransfer.MAX_BYTES + 1), "truncated" to response(byteArrayOf(), size = 100))) {
                wire = response
                val id = UUID.randomUUID().toString()
                assertEquals("", jobs.submit(id, request(folder, vm, path)))
                assertEquals("FAILED", finished(jobs, id).getString("state"))
                if (path != "dir/result") assertFalse(File(folders.directory(folder), path).exists())
                assertArrayEquals(bytes, File(folders.directory(folder), "dir/result").readBytes())
            }
            ScopedDirectory.open(folders.directory(folder)).use { root ->
                assertFalse(root.names().any { it.startsWith(".term-transfer-") })
                root.child("dir").use { assertEquals(listOf("result"), it.names()) }
            }
        } finally { scope.cancel(); ScopedDirectory.open(files).use { it.clear() }; files.delete() }
    }

    @Test fun cancellationClosesOnlyItsStreamAndRevocationPreventsPublication() = runBlocking {
        val files = Files.createTempDirectory(context.filesDir.canonicalFile.toPath(), "shared-cancel-test-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var saved: String? = null
        val folders = SharedFolderRegistry(files, { saved }, { saved = it })
        val vm = EnvironmentId.create()
        val folder = SharedFolder(UUID.randomUUID().toString(), "Files", setOf(vm))
        folders.save(folder)
        val header = response(byteArrayOf(), size = 100).copyOf(13)
        val blocked = BlockingInput(header.inputStream())
        var next: InputStream = blocked
        val jobs = SharedFolderJobs(context, scope, folders, { emptyList() }, { _, work -> work() }, { Stream(next) }, { work -> work() })
        try {
            val id = UUID.randomUUID().toString()
            assertEquals("", jobs.submit(id, request(folder, vm, "cancelled")))
            assertTrue(blocked.waiting.await(5, TimeUnit.SECONDS))
            assertTrue(jobs.cancel(id))
            assertEquals("CANCELLED", finished(jobs, id).getString("state"))
            assertFalse(File(folders.directory(folder), "cancelled").exists())
            val bytes = byteArrayOf(1, 2, 3)
            next = object : ByteArrayInputStream(response(bytes)) {
                override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                    val count = super.read(buffer, off, len)
                    if (pos >= 13 + bytes.size) folders.save(folder.copy(environments = emptySet()))
                    return count
                }
            }
            val revoked = UUID.randomUUID().toString()
            assertEquals("", jobs.submit(revoked, request(folder, vm, "revoked")))
            assertEquals("FAILED", finished(jobs, revoked).getString("state"))
            assertFalse(File(folders.directory(folder), "revoked").exists())
            assertTrue(ScopedDirectory.open(folders.directory(folder)).use { it.names().isEmpty() })
        } finally { scope.cancel(); ScopedDirectory.open(files).use { it.clear() }; files.delete() }
    }

    @Test fun ownerRestartCleansOnlyUnpublishedDataAndKeepsCommittedImports() {
        val files = Files.createTempDirectory(context.filesDir.canonicalFile.toPath(), "shared-recovery-test-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefix = "shared-recovery-${UUID.randomUUID()}-"
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(prefix + name, mode)
        }
        var saved: String? = null
        val folders = SharedFolderRegistry(files, { saved }, { saved = it })
        val completed = SharedFolder(UUID.randomUUID().toString(), "Complete", emptySet())
        folders.save(completed)
        File(folders.directory(completed), "keep").writeText("committed data")
        val id = UUID.randomUUID().toString()
        val abandoned = UUID.randomUUID().toString()
        File(folders.root, abandoned).apply { mkdir() }.resolve("partial").writeText("unpublished")
        File(folders.root, ".legacy-import-$id").apply { mkdir() }.resolve("partial").writeText("staged")
        val transfer = UUID.randomUUID().toString()
        File(folders.directory(completed), ".term-transfer-$transfer").writeText("partial transfer")
        fun record(id: String, folderId: String) = JSONObject().put("id", id).put("state", "RUNNING").put("mode", "IMPORT_LEGACY").put("importFolderId", folderId)
        val history = JSONArray().put(record(id, abandoned)).put(record(UUID.randomUUID().toString(), completed.id))
            .put(JSONObject().put("id", transfer).put("state", "RUNNING").put("mode", "FROM_VM").put("folderId", completed.id).put("path", "new-file"))
        val preferences = isolated.getSharedPreferences("shared-folder-jobs", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putString("records", history.toString()).commit())
        try {
            val jobs = SharedFolderJobs(isolated, scope, folders, { emptyList() }, { _, work -> work() }, { error("No VM must be opened during recovery") }, { work -> work() })
            val recovered = JSONArray(jobs.snapshots())
            assertEquals("INTERRUPTED", recovered.getJSONObject(0).getString("state"))
            assertEquals("COMPLETED", recovered.getJSONObject(1).getString("state"))
            assertEquals("INTERRUPTED", recovered.getJSONObject(2).getString("state"))
            assertFalse(File(folders.root, abandoned).exists())
            assertFalse(File(folders.root, ".legacy-import-$id").exists())
            assertEquals("committed data", File(folders.directory(completed), "keep").readText())
            assertEquals(listOf("keep"), ScopedDirectory.open(folders.directory(completed)).use { it.names() })
        } finally {
            scope.cancel(); context.deleteSharedPreferences(prefix + "shared-folder-jobs")
            ScopedDirectory.open(files).use { it.clear() }; files.delete()
        }
    }
}
