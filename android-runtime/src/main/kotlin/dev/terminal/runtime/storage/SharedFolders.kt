package dev.terminal.runtime.storage

import dev.terminal.runtime.session.EnvironmentId
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID

data class SharedFolder(val id: String, val name: String, val environments: Set<EnvironmentId>) {
    init {
        require(UUID.fromString(id).toString() == id)
        require(NAME.matches(name)) { "Use 1–64 ASCII letters, digits, underscores or hyphens; start with a letter" }
        require(environments.size <= 128)
    }
    val guestPath get() = "/mnt/term-shares/$name"
    fun json() = JSONObject().put("id", id).put("name", name).put("environments", JSONArray(environments.map { it.value }))
    companion object {
        val NAME = Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")
        fun parse(json: JSONObject) = SharedFolder(json.getString("id"), json.getString("name"), json.getJSONArray("environments").let { array ->
            List(array.length()) { EnvironmentId(array.getString(it)) }.toSet()
        })
    }
}

data class SharedFolderBinding(val folder: SharedFolder, val directory: File)

/** A single :vmhost writer. UUID paths never derive from a display name or Environment path. */
class SharedFolderRegistry(private val files: File, private val read: () -> String?, private val write: (String) -> Unit) {
    val root = File(files, "shared-folders")
    @Synchronized fun all(): List<SharedFolder> {
        val encoded = read() ?: return emptyList()
        require(encoded.toByteArray().size <= 128 * 1024)
        val array = JSONArray(encoded)
        require(array.length() <= 128)
        return List(array.length()) { SharedFolder.parse(array.getJSONObject(it)) }.also { rows ->
            require(rows.map { it.id }.distinct().size == rows.size && rows.map { it.name }.distinct().size == rows.size)
        }
    }
    @Synchronized fun requireFolder(id: String) = all().firstOrNull { it.id == id } ?: error("Shared Folder not found")
    @Synchronized fun disconnectEnvironment(id: EnvironmentId) {
        val rows = all()
        if (rows.any { id in it.environments }) write(JSONArray(rows.map { it.copy(environments = it.environments - id).json() }).toString())
    }
    @Synchronized fun save(folder: SharedFolder, create: Boolean = true) {
        val encoded = encodedWith(folder)
        if (create) directory(folder)
        write(encoded)
    }
    private fun encodedWith(folder: SharedFolder): String {
        val rows = all()
        require(rows.none { it.id != folder.id && it.name == folder.name }) { "Shared Folder name already exists" }
        require(rows.any { it.id == folder.id } || rows.size < 128) { "Shared Folder limit reached" }
        val encoded = JSONArray(rows.filterNot { it.id == folder.id }.plus(folder).map { it.json() }).toString()
        require(encoded.toByteArray().size <= 128 * 1024) { "Shared Folder settings limit reached" }
        return encoded
    }
    /** Serialize validation/publication with rename, connection edits and data deletion. */
    @Synchronized fun publishImport(folder: SharedFolder, publish: () -> Unit) {
        require(all().none { it.id == folder.id }) { "Import identity already exists" }
        val encoded = encodedWith(folder)
        publish()
        write(encoded)
    }
    @Synchronized fun <T> withConnection(id: String, environment: EnvironmentId, block: (SharedFolder) -> T): T {
        val folder = requireFolder(id)
        require(environment in folder.environments) { "Shared Folder permission was revoked" }
        return block(folder)
    }
    /** Caller has proved all owners idle and excluded creation and transfers. */
    @Synchronized fun delete(id: String) {
        val folder = requireFolder(id)
        ScopedDirectory.open(directory(folder)).use { it.clear() }
        ScopedDirectory.open(root).use { com.termux.terminal.ScopedFileAccess.unlink(it.fd, id, true) }
        write(JSONArray(all().filterNot { it.id == id }.map { it.json() }).toString())
    }
    fun directory(folder: SharedFolder): File {
        ensureDirectory(root)
        val target = File(root, folder.id)
        ensureDirectory(target)
        return target
    }
    fun bindings(environment: EnvironmentId): List<SharedFolderBinding> = all().filter { environment in it.environments }
        .map { SharedFolderBinding(it, directory(it)) }
    private fun ensureDirectory(path: File) {
        if (Files.notExists(path.toPath(), NOFOLLOW_LINKS)) Files.createDirectory(path.toPath())
        require(Files.isDirectory(path.toPath(), NOFOLLOW_LINKS) && path.canonicalFile == path.absoluteFile) { "Unsafe Shared Folder root" }
    }
}

object SharedRelativePath {
    fun components(value: String): List<String> {
        require(value.toByteArray().size in 1..4096 && value.none(Char::isISOControl) && !value.startsWith('/')) { "Use a relative file path" }
        return value.split('/').also { parts ->
            require(parts.size <= 64 && parts.all { it.isNotEmpty() && it != "." && it != ".." && it.toByteArray().size <= 255 }) { "Invalid relative file path" }
        }
    }
}
