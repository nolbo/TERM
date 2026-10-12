package dev.terminal.runtime.usb

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.DocumentsContract as Documents
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** A user-picked removable-volume directory, not an unrestricted ContentResolver proxy. */
internal class RemovableDocuments(private val context: Context) {
    private val preferences = context.getSharedPreferences("usb-document-trees", Context.MODE_PRIVATE)
    private val trees = linkedMapOf<String, Uri>()
    private val handles = linkedMapOf<String, Pair<String, Uri>>()
    init {
        preferences.all.forEach { (id, value) -> if (value is String) trees[id] = Uri.parse(value) }
    }
    private fun volume(uri: Uri) = context.getSystemService(StorageManager::class.java).storageVolumes.firstOrNull {
        it.isRemovable && !it.isEmulated && it.uuid.equals(Documents.getTreeDocumentId(uri).substringBefore(':'), ignoreCase = true)
    }
    @Synchronized fun available(id: String): Boolean = runCatching {
        val uri = trees[id] ?: return false
        volume(uri)?.state in setOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY) &&
            context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    }.getOrDefault(false)
    @Synchronized fun writable(id: String): Boolean {
        val uri = trees[id] ?: return false
        return available(id) && volume(uri)?.state == Environment.MEDIA_MOUNTED &&
            context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
    }
    @Synchronized fun add(value: String, flags: Int): String {
        val uri = Uri.parse(value)
        require(uri.scheme == "content" && uri.authority == "com.android.externalstorage.documents" && Documents.isTreeUri(uri)) { "SELECT_REMOVABLE_STORAGE_FOLDER" }
        require(volume(uri) != null) { "SELECT_REMOVABLE_STORAGE_FOLDER" }
        check(trees.size < 16 || uri in trees.values) { "TOO_MANY_STORAGE_ROOTS" }
        val access = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(access and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) { "STORAGE_READ_PERMISSION_REQUIRED" }
        context.contentResolver.takePersistableUriPermission(uri, access)
        val id = trees.entries.firstOrNull { it.value == uri }?.key ?: UUID.randomUUID().toString()
        check(preferences.edit().putString(id, value).commit()) { "STORAGE_SAVE_FAILED" }
        trees[id] = uri
        return id
    }
    @Synchronized fun remove(id: String) {
        val uri = trees.remove(id) ?: return
        handles.entries.removeAll { it.value.first == id }
        preferences.edit().remove(id).apply()
        val permission = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        val flags = (if (permission?.isReadPermission == true) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (permission?.isWritePermission == true) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        if (flags != 0) runCatching { context.contentResolver.releasePersistableUriPermission(uri, flags) }
    }
    @Synchronized fun label(id: String): String = trees[id]?.let { Documents.getTreeDocumentId(it).substringAfter(':').takeLast(160) }?.ifEmpty { "Removable volume" } ?: "Unavailable"
    @Synchronized fun roots() = JSONArray(trees.keys.map { id -> JSONObject().put("id", id).put("name", label(id))
        .put("available", available(id)).put("writable", writable(id)) })
    @Synchronized fun forgetHandles(id: String) { handles.entries.removeAll { it.value.first == id } }
    @Synchronized fun remember(id: String, uri: Uri): String {
        handles.entries.firstOrNull { it.value == (id to uri) }?.let { return it.key }
        check(handles.size < 4096) { "TOO_MANY_DOCUMENT_HANDLES" }
        return UUID.randomUUID().toString().also { handles[it] = id to uri }
    }
    @Synchronized fun resolve(id: String, handle: String): Uri {
        check(available(id)) { "STORAGE_UNAVAILABLE" }
        if (handle == "root") {
            val uri = trees.getValue(id)
            return Documents.buildDocumentUriUsingTree(uri, Documents.getTreeDocumentId(uri))
        }
        val record = handles[handle] ?: error("DOCUMENT_HANDLE_EXPIRED")
        require(record.first == id) { "DOCUMENT_OUTSIDE_GRANTED_TREE" }
        return record.second
    }

    fun session(id: String, write: Boolean) = DocumentSession(context, this, id, write)
}

internal class DocumentSession(private val context: Context, private val trees: RemovableDocuments,
    val root: String, private val write: Boolean) {
    @Volatile private var cancelled = false
    @Volatile private var descriptor: ParcelFileDescriptor? = null
    private val cancellation = CancellationSignal()
    private val cancellationQueued = AtomicBoolean()
    private val cancellationStarted = AtomicBoolean()
    private val resolver get() = context.contentResolver
    private fun checkActive() {
        check(!cancelled && trees.available(root)) { "STORAGE_UNAVAILABLE_OR_CANCELLED" }
        if (write) check(trees.writable(root)) { "STORAGE_WRITE_PERMISSION_REQUIRED" }
    }
    private fun info(uri: Uri, handle: String): JSONObject {
        checkActive()
        resolver.query(uri, arrayOf(Documents.Document.COLUMN_DISPLAY_NAME, Documents.Document.COLUMN_MIME_TYPE,
            Documents.Document.COLUMN_SIZE), null, null, null, cancellation)?.use { cursor ->
            check(cursor.moveToFirst()) { "DOCUMENT_NOT_FOUND" }
            return JSONObject().put("id", handle).put("name", cursor.getString(0).take(255))
                .put("directory", cursor.getString(1) == Documents.Document.MIME_TYPE_DIR)
                .put("size", if (cursor.isNull(2)) -1L else cursor.getLong(2))
        }
        error("DOCUMENT_QUERY_FAILED")
    }
    fun list(parent: String): JSONObject {
        val uri = trees.resolve(root, parent)
        check(info(uri, parent).getBoolean("directory")) { "NOT_A_DIRECTORY" }
        val children = Documents.buildChildDocumentsUriUsingTree(uri, Documents.getDocumentId(uri))
        val entries = JSONArray()
        var truncated = false
        resolver.query(children, arrayOf(Documents.Document.COLUMN_DOCUMENT_ID, Documents.Document.COLUMN_DISPLAY_NAME,
            Documents.Document.COLUMN_MIME_TYPE, Documents.Document.COLUMN_SIZE), null, null, null, cancellation)?.use { cursor ->
            while (cursor.moveToNext()) {
                checkActive()
                if (entries.length() == 48) { truncated = true; break }
                val child = Documents.buildDocumentUriUsingTree(uri, cursor.getString(0))
                entries.put(JSONObject().put("id", trees.remember(root, child)).put("name", cursor.getString(1).take(128))
                    .put("directory", cursor.getString(2) == Documents.Document.MIME_TYPE_DIR)
                    .put("size", if (cursor.isNull(3)) -1L else cursor.getLong(3)))
            }
        } ?: error("DOCUMENT_QUERY_FAILED")
        return JSONObject().put("entries", entries).put("truncated", truncated)
    }
    private fun open(uri: Uri, mode: String): ParcelFileDescriptor {
        checkActive()
        val fd = resolver.openFileDescriptor(uri, mode, cancellation) ?: error("DOCUMENT_OPEN_FAILED")
        synchronized(this) {
            if (cancelled) { fd.close(); error("STORAGE_CANCELLED") }
            descriptor = fd
        }
        return fd
    }
    fun get(handle: String, emit: (JSONObject) -> Unit) {
        val uri = trees.resolve(root, handle)
        val info = info(uri, handle)
        val size = info.getLong("size")
        require(!info.getBoolean("directory") && size in 0..IphonePolicy.FILE_LIMIT.toLong()) { "FILE_TOO_LARGE_OR_SIZE_UNKNOWN" }
        ParcelFileDescriptor.AutoCloseInputStream(open(uri, "r")).use { input ->
                emit(JSONObject().put("event", "start").put("size", size))
                var offset = 0L
                val bytes = ByteArray(IphonePolicy.CHUNK)
                while (offset < size) {
                    checkActive()
                    val count = input.read(bytes, 0, minOf(bytes.size.toLong(), size - offset).toInt())
                    check(count > 0) { "FILE_CHANGED_OR_READ_FAILED" }
                    emit(JSONObject().put("event", "data").put("offset", offset)
                        .put("data", bytes.take(count).joinToString("") { "%02x".format(it.toInt() and 255) }))
                    offset += count
                }
                check(input.read() == -1) { "FILE_CHANGED_DURING_READ" }
        }
        descriptor = null
        checkActive()
        emit(JSONObject().put("bytes", size).put("completed", true))
    }
    fun put(parent: String, name: String, size: Int, emit: (JSONObject) -> Unit, receive: (Int) -> ByteArray) {
        check(write) { "STORAGE_WRITE_NOT_GRANTED" }
        checkActive()
        val directory = trees.resolve(root, parent)
        check(info(directory, parent).getBoolean("directory")) { "NOT_A_DIRECTORY" }
        val filename = "${UUID.randomUUID()}-${IphonePolicy.uploadName(name)}"
        val created = Documents.createDocument(resolver, directory, "application/octet-stream", filename) ?: error("CREATE_DOCUMENT_FAILED")
        val handle = trees.remember(root, created)
        emit(JSONObject().put("event", "ready").put("id", handle).put("name", filename).put("size", size))
        ParcelFileDescriptor.AutoCloseOutputStream(open(created, "w")).use { output ->
                var offset = 0
                while (offset < size) {
                    checkActive()
                    val bytes = receive(offset)
                    require(bytes.isNotEmpty() && bytes.size <= IphonePolicy.CHUNK && offset + bytes.size <= size) { "INVALID_FILE_CHUNK" }
                    output.write(bytes); offset += bytes.size
                    emit(JSONObject().put("event", "accepted").put("offset", offset))
                }
                output.flush(); output.fd.sync()
        }
        descriptor = null
        check(info(created, handle).getLong("size") == size.toLong()) { "UPLOAD_SIZE_MISMATCH" }
        emit(JSONObject().put("bytes", size).put("completed", true).put("id", handle))
    }
    fun cancel() {
        cancelled = true
        if (!cancellationStarted.compareAndSet(false, true)) return
        runCatching { descriptor?.close() }
        // CancellationSignal is used by provider operations; caller invokes this off UI thread.
        cancellation.cancel()
    }
    fun cancelAsync(executor: Executor) {
        cancelled = true
        if (cancellationQueued.compareAndSet(false, true)) executor.execute { cancel() }
    }
}
