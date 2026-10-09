package dev.terminal.runtime.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.terminal.runtime.rootless.RootlessStorageFormat
import dev.terminal.runtime.session.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Owned by :vmhost. Activities only submit, observe persisted snapshots and explicitly cancel.
 * Passwords are memory-only and cleared on all paths. Process death requires a user retry.
 */
internal class EnvironmentBackupJobs(
    private val context: Context,
    private val registry: EnvironmentRegistry,
    private val defaults: () -> List<SessionProfile>,
    private val scope: CoroutineScope,
    private val tracked: suspend (suspend () -> Unit) -> Unit,
    private val withLease: suspend (Environment, BackupWorkspace, suspend (() -> Unit) -> Unit) -> Unit,
) {
    private val files = context.filesDir.canonicalFile
    private val journal = File(context.noBackupFilesDir, "environment-backups")
    private val monitor = Any()
    private val records = linkedMapOf<String, JSONObject>()
    private var initialized = false
    private var active: String? = null
    private var cancellation: AtomicBoolean? = null
    private var activeJob: Job? = null
    private var activeResource: java.io.Closeable? = null

    fun snapshots(): String = synchronized(monitor) {
        recover()
        JSONArray(records.values.toList().asReversed()).toString()
    }
    fun submit(id: String, encoded: String, password: CharArray): String = synchronized(monitor) {
        try {
            recover()
            require(UUID.fromString(id).toString() == id)
            records[id]?.let { return it.toString() } // Safe resubmission after an uncertain Binder reply.
            check(active == null) { "Another backup/restore operation is running" }
            require(encoded.toByteArray().size <= 16_384)
            val request = JSONObject(encoded)
            require(request.getString("operation") in setOf("EXPORT", "INSPECT", "RESTORE"))
            val uri = Uri.parse(request.getString("uri"))
            require(uri.scheme == "content") { "Choose a file using the Android document picker" }
            require(password.size <= 1024)
            request.optString("name").takeIf(String::isNotEmpty)?.let {
                require(it.isNotBlank() && it.length <= StorageRegistry.MAX_NAME_LENGTH && it.none(Char::isISOControl))
            }
            if (request.getString("operation") == "EXPORT") {
                EnvironmentId(request.getString("environmentId"))
                BackupKind.valueOf(request.getString("kind")); BackupWorkspace.valueOf(request.getString("workspace"))
            }
            val record = JSONObject().put("id", id).put("request", request).put("state", "RUNNING").put("phase", "QUEUED")
                .put("bytes", 0L).put("total", 0L).put("entries", 0).put("error", "").put("createdAt", System.currentTimeMillis())
            records[id] = record
            persist(record)
            active = id
            val cancelled = AtomicBoolean(false).also { cancellation = it }
            val secret = password.copyOf()
            val worker = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                try {
                    tracked {
                        val job = currentCoroutineContext()
                        val check = { job.ensureActive(); if (cancelled.get()) throw BackupCancelledException() }
                        var lastUpdate = 0L
                        var lastPhase = ""
                        val update: (BackupProgress) -> Unit = { value ->
                            val now = System.nanoTime()
                            if (value.phase != lastPhase || now - lastUpdate >= 250_000_000) {
                                synchronized(monitor) {
                                    record.put("phase", value.phase).put("bytes", value.bytes).put("total", value.total).put("entries", value.entries)
                                    persist(record)
                                }
                                lastUpdate = now; lastPhase = value.phase
                            }
                        }
                        check()
                        val archive = TermBackupArchive(check, update)
                        when (request.getString("operation")) {
                            "EXPORT" -> export(uri, request, secret, archive, check, update)
                            "INSPECT" -> withResource(context.contentResolver.openInputStream(uri)) { input ->
                                val manifest = archive.inspect(input, secret)
                                manifest.checkCompatibility(architecture())
                                synchronized(monitor) { record.put("manifest", summary(manifest)) }
                            }
                            "RESTORE" -> {
                                val restored = withResource(context.contentResolver.openInputStream(uri)) { input ->
                                    EnvironmentRestore(files, registry, defaults(), check, update, archive = archive)
                                        .restore(id, input, secret, architecture(), request.optBoolean("profiles", false), request.optString("name"))
                                }
                                synchronized(monitor) { record.put("resultEnvironmentId", restored.id.value) }
                            }
                        }
                        synchronized(monitor) {
                            record.put("state", "SUCCEEDED").put("phase", "COMPLETE").put("bytes", record.optLong("total"))
                            persist(record)
                        }
                    }
                } catch (failure: Exception) {
                    val interrupted = cancelled.get() || failure is CancellationException || failure is BackupCancelledException
                    if (request.getString("operation") == "EXPORT") deletePartialDocument(uri)
                    synchronized(monitor) {
                        record.put("state", if (interrupted) "CANCELLED" else "FAILED").put("phase", if (interrupted) "CANCELLED" else "FAILED")
                            .put("error", (failure.message ?: failure.javaClass.simpleName).take(2048))
                        persist(record)
                    }
                } finally {
                    secret.fill('\u0000')
                    synchronized(monitor) { if (active == id) { active = null; cancellation = null; activeJob = null } }
                }
            }
            activeJob = worker
            worker.invokeOnCompletion { failure ->
                secret.fill('\u0000')
                synchronized(monitor) {
                    if (record.optString("state") == "RUNNING") {
                        record.put("state", if (failure is CancellationException || cancelled.get()) "CANCELLED" else "FAILED")
                            .put("phase", if (failure is CancellationException || cancelled.get()) "CANCELLED" else "FAILED")
                            .put("error", failure?.message.orEmpty().take(2048))
                        persist(record)
                        if (request.getString("operation") == "EXPORT") deletePartialDocument(uri)
                    }
                    if (active == id) { active = null; activeJob = null; cancellation = null }
                }
            }
            worker.start()
            record.toString()
        } finally { password.fill('\u0000') }
    }

    fun cancel(id: String): Boolean = synchronized(monitor) {
        if (id != active || records[id]?.optString("state") != "RUNNING" || records[id]?.optString("phase") == "ACTIVATING") false
        else {
            cancellation?.set(true)
            records[id]?.put("phase", "CANCELLING")?.let(::persist)
            val resource = activeResource
            scope.launch(Dispatchers.IO) { runCatching { resource?.close() } }
            activeJob?.cancel()
            true
        }
    }

    private suspend fun export(uri: Uri, request: JSONObject, password: CharArray, archive: TermBackupArchive,
        check: () -> Unit, update: (BackupProgress) -> Unit) {
        val environment = registry.transaction(defaults()) { requireEnvironment(EnvironmentId(request.getString("environmentId"))) }
        require(environment.runtime != EnvironmentRuntime.AVF) { "AVF backups are not supported" }
        val kind = BackupKind.valueOf(request.getString("kind"))
        require(kind != BackupKind.FULL || environment.runtime == EnvironmentRuntime.ROOTLESS) { "Full backups require Userspace Linux" }
        val workspace = BackupWorkspace.valueOf(request.getString("workspace"))
        val legacyStorage = environment.storage.id == "legacy" || SessionStorage.isMigratedLegacy(files, environment.sessionRuntime, environment.storage.id)
        val legacy = environment.runtime == EnvironmentRuntime.ROOTLESS && legacyStorage
        require(workspace == BackupWorkspace.NONE || (workspace == BackupWorkspace.LEGACY_SHARED) == legacy) { "Workspace selection does not match this environment" }
        withLease(environment, workspace) { verifyWriters ->
            check(); verifyWriters()
            val root = SessionStorage.root(files, environment.sessionRuntime, environment.storage.id).toPath()
            val source = TermBackupArchive.Source(mapOf("home" to root.resolve("home"), "rootfs" to root.resolve("rootfs"),
                "workspace" to if (legacy) File(files, "workspace").toPath() else root.resolve("workspace")),
                buildSet { add("home"); if (kind == BackupKind.FULL) add("rootfs"); if (workspace != BackupWorkspace.NONE) add("workspace") },
                environment.runtime == EnvironmentRuntime.ROOTLESS)
            val format = if (environment.runtime == EnvironmentRuntime.NATIVE) "native-data-1" else {
                val restored = if (Files.notExists(root.resolve("rootfs"), NOFOLLOW_LINKS)) RootlessStorageFormat.restoredDataFormat(root) else null
                when (restored ?: RootlessStorageFormat.resolveForLaunch(root.resolve("rootfs"))) {
                    RootlessStorageFormat.LaunchFormat.NATIVE -> "native-hardlinks-1"
                    RootlessStorageFormat.LaunchFormat.EMULATED -> "emulated-hardlinks-1"
                    RootlessStorageFormat.LaunchFormat.LEGACY_EMULATED -> "legacy-emulated-hardlinks"
                }
            }
            val inventory = archive.inventory(source)
            // The host UI resolves dynamic built-in defaults (including the Native shell choice).
            // Saved overrides remain authoritative inside the shared manifest transaction.
            val builtIns = request.optJSONArray("defaultProfiles")?.let { encoded ->
                require(encoded.length() <= SessionRuntime.entries.size)
                List(encoded.length()) { SessionLaunchSpec.fromJson(encoded.getJSONObject(it).toString()).toProfile().also { profile ->
                    require(profile.builtIn && profile.environmentId == environment.id && profile.runtime == environment.sessionRuntime &&
                        profile.storageId == environment.storage.id) { "Invalid backup default profile" }
                } }
            }
            val profiles = if (request.optBoolean("profiles", true)) registry.transaction(builtIns ?: defaults()) {
                profiles().filter { it.environmentId == environment.id }
            } else emptyList()
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty().ifBlank { "unknown" }
            val manifest = BackupManifest(kind, environment, architecture(), version, format, workspace, profiles, inventory.bytes, inventory.entries,
                sourceLegacyLayout = legacyStorage)
            update(BackupProgress("WRITING", total = manifest.bytes))
            withResource(context.contentResolver.openOutputStream(uri, "wt")) { output ->
                archive.write(output, password, manifest, source) { verifyWriters() }
                output.flush()
            }
        }
    }

    private fun recover() {
        if (initialized) return
        journal.mkdirs()
        EnvironmentRestore(files, registry, defaults()).recover()
        journal.listFiles()?.filter { it.extension == "json" && it.length() <= 2 * 1024 * 1024 }
            ?.sortedBy { it.name }?.forEach { file ->
                val record = JSONObject(file.readText())
                val id = record.getString("id")
                require(UUID.fromString(id).toString() == id)
                if (record.getString("state") == "RUNNING") {
                    val committed = registry.transaction(defaults()) { completedRestore(id) }
                    if (committed != null) record.put("state", "SUCCEEDED").put("phase", "COMPLETE").put("resultEnvironmentId", committed.id.value)
                    else {
                        record.put("state", "INTERRUPTED").put("phase", "INTERRUPTED").put("error", "The runtime process stopped. Retry with the selected file and password.")
                        if (record.getJSONObject("request").getString("operation") == "EXPORT")
                            deletePartialDocument(Uri.parse(record.getJSONObject("request").getString("uri")))
                    }
                    persist(record)
                }
                records[id] = record
            }
        val ordered = records.values.sortedBy { it.optLong("createdAt") }
        records.clear(); ordered.takeLast(20).forEach { records[it.getString("id")] = it }
        ordered.dropLast(20).forEach { File(journal, "${it.getString("id")}.json").delete() }
        initialized = true
    }
    private fun persist(record: JSONObject) {
        journal.mkdirs()
        val id = record.getString("id")
        val temporary = File(journal, "$id.tmp")
        FileOutputStream(temporary).use { output -> output.write(record.toString().toByteArray()); output.fd.sync() }
        Files.move(temporary.toPath(), File(journal, "$id.json").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    private inline fun <T : java.io.Closeable, R> withResource(resource: T?, block: (T) -> R): R {
        requireNotNull(resource) { "Cannot open the selected document" }
        synchronized(monitor) { activeResource = resource }
        return try { resource.use(block) } finally { synchronized(monitor) { if (activeResource === resource) activeResource = null } }
    }
    private fun deletePartialDocument(uri: Uri) { runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) } }
    private fun architecture() = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
    private fun summary(manifest: BackupManifest): JSONObject = manifest.toJson().apply {
        remove("profiles"); put("profileCount", manifest.profiles.size)
    }
    private class BackupCancelledException : java.io.IOException("Backup operation cancelled")
}
