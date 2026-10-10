package dev.terminal.runtime.storage

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import com.termux.terminal.ScopedFileAccess
import dev.terminal.protocol.FileTransfer
import dev.terminal.runtime.api.GuestByteStream
import dev.terminal.runtime.session.EnvironmentId
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Runtime-owned explicit copies. UI unbinding never cancels a job or changes a VM's lifecycle. */
class SharedFolderJobs(
    context: Context, private val scope: CoroutineScope, private val folders: SharedFolderRegistry,
    private val sources: () -> List<LegacyWorkspaces.Source>,
    private val withLegacyLease: suspend (LegacyWorkspaces.Source, suspend () -> Unit) -> Unit,
    private val openVm: suspend (EnvironmentId) -> GuestByteStream,
    private val tracked: suspend (suspend () -> Unit) -> Unit,
    private val withVmLease: suspend (EnvironmentId, suspend () -> Unit) -> Unit = { _, work -> work() },
) {
    private val context = context.applicationContext
    private val preferences = context.getSharedPreferences("shared-folder-jobs", Context.MODE_PRIVATE)
    private data class Active(val job: Job, val stream: AtomicReference<Closeable?> = AtomicReference(), val folderId: String?, val environmentId: String?)
    private val active = ConcurrentHashMap<String, Active>()
    private val records = linkedMapOf<String, JSONObject>()
    init {
        val saved = JSONArray(preferences.getString("records", "[]"))
        require(saved.length() <= 20) { "Invalid file operation history" }
        for (i in 0 until saved.length()) {
            val record = saved.getJSONObject(i)
            if (record.optString("state") in setOf("RUNNING", "INTERRUPTED")) {
                val recovery = runCatching { recoverUnpublished(record) }
                val imported = record.optString("importFolderId").takeIf(String::isNotEmpty)
                if (imported != null && folders.all().any { it.id == imported }) record.put("state", "COMPLETED").put("error", JSONObject.NULL)
                else record.put("state", "INTERRUPTED").put("error", recovery.exceptionOrNull()?.message ?: "Runtime owner stopped; original data is preserved. Retry explicitly.")
            }
            records[record.getString("id")] = record
        }
        persist()
    }
    fun hasActive() = active.isNotEmpty()
    @Synchronized fun snapshots(): String = JSONArray(records.values.toList()).toString()
    @Synchronized private fun persist() { check(preferences.edit().putString("records", JSONArray(records.values.toList()).toString()).commit()) }
    @Synchronized private fun update(id: String, state: String, bytes: Long = 0, error: String? = null) {
        records[id]?.put("state", state)?.put("bytes", bytes)?.put("error", error ?: JSONObject.NULL)
        persist()
    }
    @Synchronized fun submit(id: String, encoded: String): String = runCatching {
        require(UUID.fromString(id).toString() == id && encoded.toByteArray().size <= 16 * 1024)
        if (id in records) return@runCatching ""
        require(active.size < 2) { "Finish or cancel another file operation first" }
        val request = JSONObject(encoded)
        val mode = request.getString("mode")
        require(mode in setOf("IMPORT_LEGACY", "EXPORT_LEGACY", "TO_VM", "FROM_VM"))
        val folderId = request.optString("folderId").takeIf(String::isNotEmpty)
        val imported = if (mode == "IMPORT_LEGACY") SharedFolder(UUID.randomUUID().toString(), request.getString("name"), emptySet()) else null
        if (mode == "TO_VM" || mode == "FROM_VM") {
            val folder = folders.requireFolder(requireNotNull(folderId))
            require(EnvironmentId(request.getString("environmentId")) in folder.environments) { "Shared Directory is not connected to this VM" }
            SharedRelativePath.components(request.getString("path"))
        } else require(sources().any { it.id == request.getString("sourceId") }) { "Legacy workspace not found" }
        records[id] = JSONObject().put("id", id).put("mode", mode).put("state", "RUNNING").put("bytes", 0).put("error", JSONObject.NULL)
            .put("folderId", folderId ?: JSONObject.NULL).put("path", request.optString("path"))
            .put("importFolderId", imported?.id ?: JSONObject.NULL)
        while (records.size > 20) records.entries.firstOrNull { it.value.getString("state") != "RUNNING" }?.let { records.remove(it.key) } ?: break
        persist()
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val timeout = if (mode in setOf("TO_VM", "FROM_VM")) scope.launch { delay(120_000); cancel(id) } else null
            var processed = 0L
            var publishedAt = 0L
            val progress: (Long) -> Unit = { count ->
                processed = count
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - publishedAt >= 500) { publishedAt = now; update(id, "RUNNING", count) }
            }
            try {
                tracked {
                    when (mode) {
                        "IMPORT_LEGACY", "EXPORT_LEGACY" -> {
                            val source = sources().first { it.id == request.getString("sourceId") }
                            withLegacyLease(source) {
                                if (mode == "IMPORT_LEGACY") importLegacy(id, source, checkNotNull(imported), progress)
                                else exportLegacy(id, source, Uri.parse(request.getString("uri")), progress)
                            }
                        }
                        else -> withVmLease(EnvironmentId(request.getString("environmentId"))) { transfer(id, request, mode, progress) }
                    }
                }
                update(id, "COMPLETED", processed)
            } catch (cancelled: CancellationException) {
                update(id, "CANCELLED", processed, "Cancelled; originals and existing destination files are preserved.")
                throw cancelled
            } catch (failure: Exception) {
                if (!currentCoroutineContext().isActive) update(id, "CANCELLED", processed, "Cancelled; originals and existing destination files are preserved.")
                else update(id, "FAILED", processed, failure.message ?: "File operation failed")
            } finally { timeout?.cancel(); active.remove(id)?.stream?.getAndSet(null)?.let { runCatching { it.close() } } }
        }
        active[id] = Active(job, folderId = folderId, environmentId = request.optString("environmentId").takeIf(String::isNotEmpty))
        job.start()
        ""
    }.exceptionOrNull()?.message.orEmpty()

    fun cancel(id: String): Boolean = active[id]?.let {
        it.job.cancel()
        it.stream.getAndSet(null)?.let { stream -> runCatching { stream.close() } }
        true
    } ?: false
    fun revokeFolder(id: String) { active.filterValues { it.folderId == id }.keys.forEach(::cancel) }
    fun revokeEnvironment(id: EnvironmentId) { active.filterValues { it.environmentId == id.value }.keys.forEach(::cancel) }

    private suspend fun transfer(id: String, request: JSONObject, mode: String, progress: (Long) -> Unit) {
        val folderId = request.getString("folderId")
        val environment = EnvironmentId(request.getString("environmentId"))
        fun authorize() = folders.requireFolder(folderId).also { require(environment in it.environments) { "Shared Directory permission was revoked" } }
        val folder = authorize()
        val parts = SharedRelativePath.components(request.getString("path"))
        val job = currentCoroutineContext()
        var acquired: GuestByteStream? = null
        val stream = try { withTimeout(30_000) { openVm(environment).also {
            acquired = it
            active[id]?.stream?.set(it)
            job.ensureActive()
        } } } catch (failure: Exception) { acquired?.let { runCatching { it.close() } }; throw failure }
        try {
            job.ensureActive()
            active[id]?.stream?.set(stream)
            val input = DataInputStream(stream.input)
            val output = DataOutputStream(stream.output)
            FileTransfer.checkGreeting(input)
            FileTransfer.request(output, if (mode == "TO_VM") FileTransfer.TO_VM else FileTransfer.FROM_VM,
                folder.name + "/" + parts.joinToString("/"))
            ScopedDirectory.open(folders.directory(folder)).use { root ->
                if (mode == "TO_VM") root.parent(parts.dropLast(1)).use { parent ->
                    val descriptor = parent.file(parts.last())
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { source ->
                        val size = Os.fstat(descriptor.fileDescriptor).st_size
                        require(size in 0..FileTransfer.MAX_BYTES)
                        output.writeLong(size)
                        val digest = FileTransfer.copy(source, output, size, { job.ensureActive() }, progress)
                        require(source.read() == -1 && Os.fstat(descriptor.fileDescriptor).st_size == size) { "Source changed during copy" }
                        job.ensureActive(); authorize()
                        output.write(digest); output.flush()
                        FileTransfer.checkStatus(input)
                        FileTransfer.verify(digest, ByteArray(32).also(input::readFully))
                    }
                } else {
                    FileTransfer.checkStatus(input)
                    val size = input.readLong()
                    require(size in 0..FileTransfer.MAX_BYTES)
                    root.parent(parts.dropLast(1), create = true).use { parent ->
                        val temporary = ".term-transfer-$id"
                        var created = false
                        try {
                            val descriptor = parent.file(temporary, create = true)
                            created = true
                            val digest = ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { target ->
                                FileTransfer.copy(input, target, size, { job.ensureActive() }, progress).also {
                                    target.flush(); target.fd.sync()
                                }
                            }
                            FileTransfer.verify(digest, ByteArray(32).also(input::readFully))
                            folders.withConnection(folderId, environment) {
                                job.ensureActive()
                                ScopedFileAccess.rename(parent.fd, temporary, parent.fd, parts.last())
                                created = false; parent.sync()
                            }
                            output.writeByte(0); output.write(digest); output.flush()
                        } finally { if (created) runCatching { ScopedFileAccess.unlink(parent.fd, temporary, false) } }
                    }
                }
            }
        } finally { active[id]?.stream?.compareAndSet(stream, null); stream.close() }
    }

    private suspend fun importLegacy(id: String, source: LegacyWorkspaces.Source, folder: SharedFolder, progress: (Long) -> Unit) {
        require(folders.all().none { it.name == folder.name }) { "Shared Directory name already exists" }
        if (!folders.root.exists()) require(folders.root.mkdir())
        val job = currentCoroutineContext()
        ScopedDirectory.open(folders.root).use { parent ->
            val temporary = ".legacy-import-$id"
            var staged = false
            try {
                val target = parent.child(temporary, create = true)
                staged = true
                val expected = ScopedDirectory.open(source.directory).use { origin -> target.use { copyTree(origin, it, job::ensureActive, progress) } }
                val actual = ScopedDirectory.open(source.directory).use { copyTree(it, null, job::ensureActive, progress = {}) }
                FileTransfer.verify(expected, actual)
                folders.publishImport(folder) {
                    job.ensureActive()
                    ScopedFileAccess.rename(parent.fd, temporary, parent.fd, folder.id)
                    staged = false; parent.sync()
                }
            } finally {
                if (staged) runCatching { parent.child(temporary).use { it.clear() }; ScopedFileAccess.unlink(parent.fd, temporary, true) }
                // A metadata-write failure must not leave an apparently registered import.
                if (!staged && folders.all().none { it.id == folder.id }) runCatching {
                    parent.child(folder.id).use { it.clear() }; ScopedFileAccess.unlink(parent.fd, folder.id, true)
                }
            }
        }
    }

    /** A crash can leave only our unpublished temporary names, never an existing destination. */
    private fun recoverUnpublished(record: JSONObject) {
        val id = record.getString("id")
        require(UUID.fromString(id).toString() == id)
        if (!folders.root.exists()) return
        ScopedDirectory.open(folders.root).use { root ->
            if (record.getString("mode") == "IMPORT_LEGACY") {
                val imported = record.optString("importFolderId").takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) }
                val unpublished = listOf(".legacy-import-$id") + if (imported != null && folders.all().none { it.id == imported }) listOf(imported) else emptyList()
                unpublished.filter { it in root.names() }.forEach { name ->
                    root.child(name).use { it.clear() }; ScopedFileAccess.unlink(root.fd, name, true)
                }
            } else if (record.getString("mode") == "FROM_VM") {
                val folderId = record.optString("folderId")
                val folder = folders.all().firstOrNull { it.id == folderId } ?: return
                val parts = SharedRelativePath.components(record.getString("path"))
                runCatching { root.child(folder.id).use { folderRoot -> folderRoot.parent(parts.dropLast(1)).use { parent ->
                    if (".term-transfer-$id" in parent.names()) ScopedFileAccess.unlink(parent.fd, ".term-transfer-$id", false)
                } } }
            }
        }
    }

    private suspend fun exportLegacy(id: String, source: LegacyWorkspaces.Source, uri: Uri, progress: (Long) -> Unit) {
        require(uri.scheme == "content") { "Choose a document with Android's file picker" }
        val job = currentCoroutineContext()
        val raw = context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open export document")
        try {
            active[id]?.stream?.set(raw)
            job.ensureActive()
            ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                ScopedDirectory.open(source.directory).use { origin ->
                    val digest = copyTree(origin, null, job::ensureActive, progress, zip)
                    job.ensureActive()
                    zip.putNextEntry(ZipEntry("TERM-SHA256")); zip.write(digest.joinToString("") { "%02x".format(it) }.toByteArray()); zip.closeEntry()
                }
            }
        } finally { active[id]?.stream?.compareAndSet(raw, null); raw.close() }
    }

    /** Reject links/special files, preserve every source byte, and verify the full tree before activation. */
    private fun copyTree(source: ScopedDirectory, destination: ScopedDirectory?, check: () -> Unit,
        progress: (Long) -> Unit, zip: ZipOutputStream? = null): ByteArray {
        val tree = MessageDigest.getInstance("SHA-256")
        var total = 0L
        var nodes = 0
        fun walk(origin: ScopedDirectory, target: ScopedDirectory?, prefix: String, depth: Int) {
            require(depth <= 64) { "Legacy workspace directory depth exceeds limit" }
            val names = origin.names()
            for (name in names) {
                check(); require(++nodes <= 100_000 && name.none(Char::isISOControl)) { "Legacy workspace exceeds safe import limits" }
                val path = prefix + name
                val directory = runCatching { origin.child(name) }.getOrNull()
                val encoded = path.toByteArray()
                tree.update(java.nio.ByteBuffer.allocate(4).putInt(encoded.size).array()); tree.update(encoded)
                if (directory != null) {
                    tree.update(1)
                    zip?.putNextEntry(ZipEntry("data/$path/")); zip?.closeEntry()
                    directory.use { child ->
                        val next = target?.child(name, true)
                        try { walk(child, next, "$path/", depth + 1); next?.sync() } finally { next?.close() }
                    }
                } else {
                    tree.update(2)
                    val descriptor = origin.file(name)
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                        val size = Os.fstat(descriptor.fileDescriptor).st_size
                        tree.update(java.nio.ByteBuffer.allocate(8).putLong(size).array())
                        require(size >= 0 && size <= MAX_LEGACY_BYTES - total) { "Legacy workspace exceeds 2 GiB import limit" }
                        zip?.putNextEntry(ZipEntry("data/$path"))
                        val output = target?.create(name)
                        try {
                            val buffer = ByteArray(64 * 1024)
                            var remaining = size
                            while (remaining > 0) {
                                check()
                                val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                                require(n > 0) { "Source changed during copy" }
                                tree.update(buffer, 0, n); output?.write(buffer, 0, n); zip?.write(buffer, 0, n)
                                remaining -= n; total += n; progress(total)
                            }
                            require(input.read() == -1) { "Source changed during copy" }
                            (output as? FileOutputStream)?.let { it.flush(); it.fd.sync() }
                        } finally { output?.close() }
                    }
                    tree.update(0); zip?.closeEntry()
                }
            }
            require(origin.names() == names) { "Source changed during copy" }
        }
        walk(source, destination, "", 0)
        destination?.sync()
        return tree.digest()
    }
    companion object { private const val MAX_LEGACY_BYTES = 2L * 1024 * 1024 * 1024 }
}
