package dev.terminal.runtime.storage

import dev.terminal.runtime.session.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.UUID

class SharedFoldersTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun removingAnEnvironmentOnlyDisconnectsItsOwnReferencesAndRetainsData() {
        var saved: String? = null
        val registry = SharedFolderRegistry(temporary.newFolder(), { saved }, { saved = it })
        val removed = EnvironmentId(UUID.randomUUID().toString()); val remaining = EnvironmentId(UUID.randomUUID().toString())
        val folder = SharedFolder(UUID.randomUUID().toString(), "Project", setOf(removed, remaining))
        registry.save(folder)
        File(registry.directory(folder), "keep").writeText("shared data")
        registry.disconnectEnvironment(removed)
        assertEquals(setOf(remaining), registry.requireFolder(folder.id).environments)
        registry.save(registry.requireFolder(folder.id).copy(name = "Renamed"))
        assertEquals("shared data", File(registry.directory(folder), "keep").readText())
    }
    @Test fun oversizedConnectionSettingsPreserveTheLastReadableRegistry() {
        var saved: String? = null
        val files = temporary.newFolder()
        val registry = SharedFolderRegistry(files, { saved }, { saved = it })
        val environments = (0 until 128).map { EnvironmentId(UUID.randomUUID().toString()) }.toSet()
        var refused = false
        for (index in 0 until 128) {
            val before = saved
            val folder = SharedFolder(UUID.randomUUID().toString(), "Folder$index", environments)
            if (runCatching { registry.save(folder) }.isFailure) {
                assertEquals(before, saved)
                assertFalse(File(registry.root, folder.id).exists())
                assertEquals(index, registry.all().size)
                refused = true
                break
            }
        }
        assertTrue(refused)
    }
    @Test fun rejectTraversalControlCharactersAndUnsafeMountNames() {
        for (path in listOf("", "/etc/passwd", "../x", "a/../b", "a//b", "a/./b", "a\u0000b", "a\nb")) {
            assertTrue(path, runCatching { SharedRelativePath.components(path) }.isFailure)
        }
        assertEquals(listOf("project", "file"), SharedRelativePath.components("project/file"))
        for (name in listOf("..", "a/b", "a:b", "-flag", "a b")) assertFalse(SharedFolder.NAME.matches(name))
    }
    @Test fun namesAndConnectionsDoNotChangeStorageOwnership() {
        val files = temporary.newFolder(); var saved: String? = null
        val registry = SharedFolderRegistry(files, { saved }, { saved = it })
        val environment = Environment.create(SessionRuntime.NATIVE, "Native")
        val folder = SharedFolder(UUID.randomUUID().toString(), "Code", setOf(environment.id))
        registry.save(folder)
        val root = registry.directory(folder); File(root, "keep").writeText("user data")
        registry.save(folder.copy(name = "Renamed", environments = emptySet()))
        assertEquals(root, registry.directory(registry.all().single()))
        assertEquals("user data", File(root, "keep").readText())
        assertFalse(root.toPath().startsWith(SessionStorage.root(files, SessionRuntime.NATIVE, environment.storage.id).toPath()))
    }
    @Test fun symlinkSharedRootCannotExposeOtherHostStorage() {
        val files = temporary.newFolder(); val outside = temporary.newFolder()
        Files.createSymbolicLink(File(files, "shared-folders").toPath(), outside.toPath())
        val registry = SharedFolderRegistry(files, { null }, {})
        assertTrue(runCatching { registry.save(SharedFolder(UUID.randomUUID().toString(), "Code", emptySet())) }.isFailure)
        assertTrue(outside.listFiles()!!.isEmpty())
    }
    @Test fun deletionPreservesUuidWorkspaceAndNeverMovesLegacyCommonData() {
        val files = temporary.newFolder()
        val environment = Environment.create(SessionRuntime.ROOTLESS, "Debian")
        val root = SessionStorage.root(files, environment.sessionRuntime, environment.storage.id)
        val original = File(root, "workspace").apply { mkdirs() }
        File(original, "keep").writeText("project")
        val common = File(files, "workspace").apply { mkdir() }
        File(common, "legacy").writeText("shared")
        LegacyWorkspaces.preserveBeforeDelete(files, environment.storageKey)
        assertEquals("project", File(original, "keep").readText())
        val registry = StorageRegistry(files)
        registry.transaction { delete(environment.storageKey) }
        val sources = LegacyWorkspaces.sources(files, emptyList())
        assertEquals(2, sources.size)
        assertEquals("project", File(sources.single { it.id.startsWith("retained:") }.directory, "keep").readText())
        assertEquals("shared", File(common, "legacy").readText())
        assertTrue(original.exists())
        assertEquals(original, sources.single { it.id.startsWith("retained:") }.directory)
        assertTrue(registry.transaction { keys().isEmpty() })
    }
    @Test fun oldFolderCopyReservesOnlyItsActualEnvironmentUsers() {
        val files = temporary.newFolder()
        val own = Environment.create(SessionRuntime.ROOTLESS, "One")
        val sibling = Environment.create(SessionRuntime.ROOTLESS_UBUNTU, "Two")
        val legacy = Environment.create(SessionRuntime.ROOTLESS_ARCH, "Old", storageId = "legacy")
        val source = LegacyWorkspaces.Source("environment:${own.id}", "Old data", File(SessionStorage.root(files, own.sessionRuntime, own.storage.id), "workspace"), own)
        assertEquals(listOf(own), LegacyWorkspaces.users(files, source, listOf(own, sibling, legacy)))
        assertEquals(listOf(legacy), LegacyWorkspaces.users(files, LegacyWorkspaces.Source("legacy", "Common", File(files, "workspace")), listOf(own, sibling, legacy)))
    }
    @Test fun unsafeWorkspaceBlocksDeletionBeforeHomeOrRootfsAreTouched() {
        val files = temporary.newFolder()
        val registry = EnvironmentRegistry(files)
        val environment = Environment.create(SessionRuntime.ROOTLESS, "Preserve linked data")
        registry.transaction {
            val profile = save(SessionProfile(UUID.randomUUID().toString(), "Fixture", environment.sessionRuntime, "/bin/sh"), environment)
            deleteProfile(profile.id, false)
        }
        val root = registry.storage.root(environment.storageKey)
        val payload = File(root, "rootfs/payload").apply { parentFile.mkdirs(); writeText("package data") }
        File(root, "home/settings").apply { parentFile.mkdirs(); writeText("home data") }
        val workspace = File(root, "workspace").apply { mkdirs() }
        Files.createSymbolicLink(File(workspace, "link").toPath(), payload.toPath())
        var removedOwner = false
        assertTrue(runCatching { registry.transaction { delete(environment.id) { removedOwner = true } } }.isFailure)
        assertFalse(removedOwner)
        assertEquals("package data", payload.readText())
        assertEquals("home data", File(root, "home/settings").readText())
        assertNotNull(registry.transaction { find(environment.id) })
    }
    @Test fun aPromotedButUncommittedRestoreIsNotOfferedAsRetainedUserData() {
        val files = temporary.newFolder()
        val registry = EnvironmentRegistry(files)
        val environment = Environment.create(SessionRuntime.ROOTLESS, "Pending restore")
        registry.transaction { reserveRestore(UUID.randomUUID().toString(), environment) }
        val workspace = File(registry.storage.root(environment.storageKey), "workspace").apply { mkdirs() }
        File(workspace, "private").writeText("unpublished restore")
        assertTrue(registry.transaction { all().isEmpty() })
        assertTrue(LegacyWorkspaces.sources(files, emptyList()).isEmpty())
        assertEquals("unpublished restore", File(workspace, "private").readText())
    }
    @Test fun importsValidateBeforePublicationAndConnectionsAuthorizeOnlySelectedVms() {
        var saved: String? = null
        val registry = SharedFolderRegistry(temporary.newFolder(), { saved }, { saved = it })
        val vm = EnvironmentId(UUID.randomUUID().toString())
        val folder = SharedFolder(UUID.randomUUID().toString(), "Files", setOf(vm))
        registry.save(folder)
        var published = false
        assertTrue(runCatching { registry.publishImport(folder.copy(id = UUID.randomUUID().toString())) { published = true } }.isFailure)
        assertFalse(published)
        assertEquals(folder, registry.withConnection(folder.id, vm) { it })
        registry.save(folder.copy(environments = emptySet()))
        assertTrue(runCatching { registry.withConnection(folder.id, vm) { error("should not run") } }.exceptionOrNull() is IllegalArgumentException)
    }
}
