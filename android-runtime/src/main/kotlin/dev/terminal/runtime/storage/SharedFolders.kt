package dev.terminal.runtime.storage

import dev.terminal.runtime.session.EnvironmentId
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID

data class SharedFolder(val id: String, val name: String, val environments: Set<EnvironmentId>, val androidStorage: Boolean = false) {
    init {
        require(UUID.fromString(id).toString() == id)
        require(NAME.matches(name)) { "Use 1–64 ASCII letters, digits, underscores or hyphens; start with a letter" }
        require(environments.size <= 128)
    }
    val guestPath get() = "/mnt/term-shares/$name"
    fun json() = JSONObject().put("id", id).put("name", name).put("environments", JSONArray(environments.map { it.value }))
        .put("androidStorage", androidStorage)
    companion object {
        val NAME = Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")
        fun parse(json: JSONObject) = SharedFolder(json.getString("id"), json.getString("name"), json.getJSONArray("environments").let { array ->
            List(array.length()) { EnvironmentId(array.getString(it)) }.toSet()
        }, if (json.has("androidStorage")) (json.get("androidStorage").also { require(it is Boolean) } as Boolean) else false)
    }
}

data class SharedFolderBinding(val folder: SharedFolder, val directory: File)

/** A single :vmhost writer. UUID paths never derive from a display name or Environment path. */
class SharedFolderRegistry(private val files: File, private val read: () -> String?, private val write: (String) -> Unit,
    private val androidRoot: () -> File = { error("Android shared storage is unavailable") },
    private val storageGranted: () -> Boolean = { false },
) {
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
    @Synchronized fun requireFolder(id: String) = all().firstOrNull { it.id == id } ?: error("Shared Directory not found")
    @Synchronized fun disconnectEnvironment(id: EnvironmentId) {
        val rows = all()
        if (rows.any { id in it.environments }) write(JSONArray(rows.map { it.copy(environments = it.environments - id).json() }).toString())
    }
    @Synchronized fun save(folder: SharedFolder, create: Boolean = true) {
        val encoded = encodedWith(folder)
        if (create && !folder.androidStorage) directory(folder)
        write(encoded)
    }
    /** Call under the preparation reservation after proving every session, VM and file writer idle.
     * Publication is last. The private original is retained, even after a successful conversion.
     * A durable private marker permits retrying only our own unpublished destination after a crash.
     */
    @Synchronized fun promoteToAndroidStorage(folder: SharedFolder,
        copyVerified: (File, File) -> Unit = SharedFolderTree::copyVerified,
    ) {
        check(storageGranted()) { "Android 17 AVF 공유에는 모든 파일 접근 권한이 필요합니다. 공유 디렉토리 설정에서 권한을 허용하세요." }
        val current = all().firstOrNull { it.id == folder.id }
        require(current?.androidStorage != true && !folder.androidStorage)
        val promoted = folder.copy(androidStorage = true)
        val encoded = encodedWith(promoted) // Validate before touching user data.
        val publicRoot = androidRoot().absoluteFile
        AndroidSharedFolderStorage.ensureRoot(publicRoot)
        val parent = File(publicRoot, "shared-folders").also(::ensureDirectory)
        val destination = File(parent, folder.id)
        val journal = File(files, ".shared-folder-promotions").also(::ensureDirectory)
        val marker = File(journal, folder.id)
        if (Files.exists(marker.toPath(), NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(marker.toPath(), NOFOLLOW_LINKS) && marker.length() == 0L)
            clearChildIfPresent(parent, folder.id)
        } else {
            require(Files.notExists(destination.toPath(), NOFOLLOW_LINKS)) { "Shared storage destination already exists; original data was preserved" }
            java.io.FileOutputStream(marker).use { it.fd.sync() }
            ScopedDirectory.open(journal).use { it.sync() }
        }
        // Never recover or delete an unmarked directory. A failure leaves the marker for a retry.
        Files.createDirectory(destination.toPath())
        val source = directory(folder)
        copyVerified(source, destination)
        ScopedDirectory.open(parent).use { it.sync() }
        check(storageGranted()) { "공유 디렉토리 전환 중 모든 파일 접근 권한이 해제되었습니다. 원본은 보존됩니다." }
        write(encoded)
        Files.deleteIfExists(marker.toPath())
    }
    /** Reverse the retired public-storage workaround using its latest data, never the stale backup.
     * Caller holds idle-writer/preparation reservations. Public source is retained after publication.
     */
    @Synchronized fun restorePrivateStorage(folder: SharedFolder) {
        require(folder.androidStorage && requireFolder(folder.id).androidStorage)
        check(storageGranted()) { "최신 공유 저장소 데이터를 앱 전용으로 복사하려면 모든 파일 접근 권한이 필요합니다." }
        val encoded = encodedWith(folder.copy(androidStorage = false))
        val source = directory(folder)
        ensureDirectory(root)
        val journal = File(files, ".shared-folder-private-restores").also(::ensureDirectory)
        val marker = File(journal, folder.id)
        val stage = ".private-${folder.id}"
        val backup = ".original-${folder.id}"
        if (Files.exists(marker.toPath(), NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(marker.toPath(), NOFOLLOW_LINKS) && marker.length() == 0L)
            clearChildIfPresent(root, stage)
        } else {
            require(Files.notExists(File(root, stage).toPath(), NOFOLLOW_LINKS) &&
                Files.notExists(File(root, backup).toPath(), NOFOLLOW_LINKS)) { "Private restore destination already exists" }
            java.io.FileOutputStream(marker).use { it.fd.sync() }
            ScopedDirectory.open(journal).use { it.sync() }
        }
        val target = File(root, stage)
        Files.createDirectory(target.toPath())
        SharedFolderTree.copyVerified(source, target)
        check(storageGranted()) { "저장소 접근 권한이 해제되었습니다. 최신 원본은 보존됩니다." }
        ScopedDirectory.open(root).use { parent ->
            if (folder.id in parent.names()) {
                if (backup !in parent.names()) dev.terminal.emulator.ScopedFileAccess.rename(parent.fd, folder.id, parent.fd, backup)
                else clearChildIfPresent(root, folder.id) // Only a journaled, unpublished retry copy.
            }
            dev.terminal.emulator.ScopedFileAccess.rename(parent.fd, stage, parent.fd, folder.id)
            parent.sync()
        }
        // A crash after the old public metadata commit may have left its marker.
        // Public metadata still owns that source if the private publication fails.
        Files.deleteIfExists(File(File(files, ".shared-folder-promotions"), folder.id).toPath())
        write(encoded)
        Files.deleteIfExists(marker.toPath())
    }
    private fun encodedWith(folder: SharedFolder): String {
        val rows = all()
        require(rows.none { it.id != folder.id && it.name == folder.name }) { "Shared Directory name already exists" }
        require(rows.any { it.id == folder.id } || rows.size < 128) { "Shared Directory limit reached" }
        val encoded = JSONArray(rows.filterNot { it.id == folder.id }.plus(folder).map { it.json() }).toString()
        require(encoded.toByteArray().size <= 128 * 1024) { "Shared Directory settings limit reached" }
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
        require(environment in folder.environments) { "Shared Directory permission was revoked" }
        return block(folder)
    }
    /** Caller has proved all owners idle and excluded creation and transfers. */
    @Synchronized fun delete(id: String) {
        val folder = requireFolder(id)
        val marker = File(File(files, ".shared-folder-promotions"), id)
        val pending = Files.exists(marker.toPath(), NOFOLLOW_LINKS)
        if (pending) require(Files.isRegularFile(marker.toPath(), NOFOLLOW_LINKS) && marker.length() == 0L)
        if (folder.androidStorage || pending) {
            check(storageGranted()) { "공유 디렉토리 데이터를 삭제하려면 모든 파일 접근 권한이 필요합니다." }
            clearChildIfPresent(File(androidRoot(), "shared-folders"), id)
        }
        clearChildIfPresent(root, id) // Includes the retained private original after conversion.
        clearChildIfPresent(root, ".private-$id")
        clearChildIfPresent(root, ".original-$id")
        Files.deleteIfExists(marker.toPath())
        Files.deleteIfExists(File(File(files, ".shared-folder-private-restores"), id).toPath())
        write(JSONArray(all().filterNot { it.id == id }.map { it.json() }).toString())
    }
    /** Metadata/UI lookup must remain possible after permission revocation or volume loss. */
    fun path(folder: SharedFolder) = File(if (folder.androidStorage) File(androidRoot(), "shared-folders") else root, folder.id)
    fun directory(folder: SharedFolder): File {
        if (folder.androidStorage) {
            check(storageGranted()) { "공유 디렉토리의 모든 파일 접근 권한이 해제되었습니다. Android 설정에서 다시 허용하세요." }
            val target = path(folder)
            require(Files.isDirectory(target.toPath(), NOFOLLOW_LINKS) && target.canonicalFile == target.absoluteFile) {
                "Android 공유 디렉토리가 없습니다. 데이터를 보호하기 위해 빈 폴더를 다시 만들지 않습니다."
            }
            return target
        }
        ensureDirectory(root)
        val target = File(root, folder.id)
        ensureDirectory(target)
        return target
    }
    fun bindings(environment: EnvironmentId): List<SharedFolderBinding> = all().filter { environment in it.environments }
        .map { SharedFolderBinding(it, directory(it)) }
    fun exports(environment: EnvironmentId) = bindings(environment).map {
        dev.terminal.runtime.api.SharedDirectoryExport(it.folder.id, it.folder.name, it.directory.path)
    }
    private fun ensureDirectory(path: File) {
        if (Files.notExists(path.toPath(), NOFOLLOW_LINKS)) Files.createDirectory(path.toPath())
        require(Files.isDirectory(path.toPath(), NOFOLLOW_LINKS) && path.canonicalFile == path.absoluteFile) { "Unsafe Shared Directory root" }
    }
    private fun clearChildIfPresent(parent: File, name: String) {
        if (Files.notExists(parent.toPath(), NOFOLLOW_LINKS)) return
        ScopedDirectory.open(parent).use { directory ->
            if (name in directory.names()) {
                directory.child(name).use { it.clear() }
                dev.terminal.emulator.ScopedFileAccess.unlink(directory.fd, name, true)
                directory.sync()
            }
        }
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
