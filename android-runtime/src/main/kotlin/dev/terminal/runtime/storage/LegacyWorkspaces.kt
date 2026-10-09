package dev.terminal.runtime.storage

import dev.terminal.runtime.session.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import org.json.JSONObject

/** Old filesystem workspaces are data sources, never logical Workspace records. */
object LegacyWorkspaces {
    data class Source(val id: String, val name: String, val directory: File, val environment: Environment? = null) {
        fun json() = JSONObject().put("id", id).put("name", name)
    }
    fun sources(filesDirectory: File, environments: List<Environment>): List<Source> = buildList {
        val files = filesDirectory.canonicalFile
        val manifest = File(files, "storage-registry.json")
        val pending = if (manifest.exists()) JSONObject(manifest.readText()).optJSONObject("pendingEnvironmentRestores") else null
        val unpublishedStorageIds = pending?.keys()?.asSequence()?.map {
            Environment.fromJson(pending.getJSONObject(it)).storage.id
        }?.toSet().orEmpty()
        val legacy = File(files, "workspace")
        if (Files.exists(legacy.toPath(), NOFOLLOW_LINKS)) add(Source("legacy", "Legacy /workspace (shared)", legacy))
        environments.filter { it.runtime == EnvironmentRuntime.ROOTLESS }.forEach { environment ->
            val directory = File(SessionStorage.root(files, environment.sessionRuntime, environment.storage.id), "workspace")
            if (Files.exists(directory.toPath(), NOFOLLOW_LINKS)) add(Source("environment:${environment.id}", environment.name + " /workspace", directory, environment))
        }
        // A deleted Environment is tombstoned, but its old workspace stays at the
        // original UUID path. Discover only fixed allowlisted storage locations.
        ROOTLESS_RUNTIMES.forEach { runtime ->
            val parent = File(files, "profile-storage/${runtime.wireName}")
            if (Files.exists(parent.toPath(), NOFOLLOW_LINKS)) {
                requireRealDirectory(parent)
                val children = requireNotNull(parent.listFiles()) { "Cannot inspect retained workspaces" }
                children.filter { it.name != "legacy" && it.name !in unpublishedStorageIds && runCatching { SessionStorage.validate(it.name) }.isSuccess }
                    .sortedBy { it.name }.forEach { child ->
                        addRetained(runtime, child.name, File(child, "workspace"))
                    }
            }
            val legacyRoot = SessionStorage.legacyRoot(files, runtime)
            addRetained(runtime, SessionStorage.migratedLegacyId(files, runtime) ?: "legacy", File(legacyRoot, "workspace"))
        }
        // Read folders retained by earlier builds as well; never move them again.
        val retained = File(files, "retained-workspaces")
        if (Files.exists(retained.toPath(), NOFOLLOW_LINKS)) {
            require(retained.canonicalFile == retained.absoluteFile && Files.isDirectory(retained.toPath(), NOFOLLOW_LINKS))
            retained.listFiles()?.filter { it.name.matches(Regex("(ROOTLESS|ROOTLESS_UBUNTU|ROOTLESS_ARCH)-[0-9a-f-]{36}")) }?.sortedBy { it.name }?.forEach {
                add(Source("retained:${it.name}", "Retained /workspace · ${it.name}", it))
            }
        }
        val vmFolders = File(files, "shared-vms")
        if (Files.exists(vmFolders.toPath(), NOFOLLOW_LINKS)) {
            require(vmFolders.canonicalFile == vmFolders.absoluteFile && Files.isDirectory(vmFolders.toPath(), NOFOLLOW_LINKS))
            vmFolders.listFiles()?.filter { it.name in setOf("default", "legacy") || runCatching { SessionStorage.validate(it.name) }.isSuccess }
                ?.sortedBy { it.name }?.forEach { add(Source("legacy-vm:${it.name}", "Legacy AVF host folder · ${it.name}", it)) }
        }
    }
    private fun MutableList<Source>.addRetained(runtime: SessionRuntime, id: String, directory: File) {
        if (Files.exists(directory.toPath(), NOFOLLOW_LINKS) && none { it.directory.absoluteFile == directory.absoluteFile })
            add(Source("retained:${runtime.wireName}-$id", "Retained /workspace · ${runtime.wireName}-$id", directory))
    }
    private fun requireRealDirectory(directory: File) {
        require(directory.canonicalFile == directory.absoluteFile && Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) { "Unsafe legacy workspace directory" }
    }
    private val ROOTLESS_RUNTIMES = setOf(SessionRuntime.ROOTLESS, SessionRuntime.ROOTLESS_UBUNTU, SessionRuntime.ROOTLESS_ARCH)

    fun users(files: File, source: Source, environments: List<Environment>): List<Environment> = environments.filter { environment ->
        when {
            source.id == "legacy" -> environment.runtime == EnvironmentRuntime.ROOTLESS &&
                (environment.storage.id == "legacy" || SessionStorage.isMigratedLegacy(files, environment.sessionRuntime, environment.storage.id))
            source.id.startsWith("legacy-vm:") -> environment.runtime == EnvironmentRuntime.AVF &&
                SessionStorage.avfInstanceId(files, environment.storage.id) == source.id.substringAfter(':')
            else -> environment.runtime == EnvironmentRuntime.ROOTLESS &&
                File(SessionStorage.root(files, environment.sessionRuntime, environment.storage.id), "workspace").absoluteFile == source.directory.absoluteFile
        }
    }

    fun verifyNoPendingMigration(files: File, source: Source) {
        val parents = if (source.id == "legacy") listOf(File(files, "rootless"))
            else if (source.directory.name == "workspace") listOf(requireNotNull(source.directory.parentFile?.parentFile)) else emptyList()
        parents.forEach { check(!dev.terminal.runtime.rootless.RootlessNativeMigration.pending(it.toPath())) { "Recover the interrupted Rootless storage migration first" } }
    }

    /** Called under the deletion lease BEFORE software removal. Validation never moves user data.
     * The caller must retain this exact child when removing the rest of the storage tree.
     */
    fun preserveBeforeDelete(filesDirectory: File, key: StorageRegistry.Key): Boolean {
        val files = filesDirectory.canonicalFile
        if (key.runtime !in ROOTLESS_RUNTIMES) return false
        val storage = SessionStorage.root(files, key.runtime, key.storageId)
        check(!dev.terminal.runtime.rootless.RootlessNativeMigration.pending(requireNotNull(storage.parentFile).toPath())) { "Recover the interrupted Rootless storage migration before deleting this environment" }
        val source = File(storage, "workspace")
        if (Files.notExists(source.toPath(), NOFOLLOW_LINKS)) return false
        requireRealDirectory(source)
        // An emulated hardlink can point at rootfs/.l2s.*. Moving only the directory would
        // preserve the link but delete its contents with the rootfs. Fail before any removal.
        Files.walkFileTree(source.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(path: java.nio.file.Path, attributes: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                check(!attributes.isSymbolicLink) { "기존 /workspace에 심볼릭 링크 또는 에뮬레이션 링크가 있어 환경 삭제를 차단했습니다. 환경의 Full 백업으로 링크 대상까지 보관한 뒤 해당 파일을 정리하세요." }
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
        return true
    }
}
