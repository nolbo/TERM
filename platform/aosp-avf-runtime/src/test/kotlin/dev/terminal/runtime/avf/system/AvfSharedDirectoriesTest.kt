package dev.terminal.runtime.avf.system

import dev.terminal.runtime.api.SharedDirectoryExport
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.UUID

class AvfSharedDirectoriesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun primaryStorageCanBeExportedAloneWithoutAnEmptyFolderIndexAndCleanupPreservesItsFiles() {
        val files = temporary.newFolder()
        val storage = temporary.newFolder()
        val keep = File(storage, "photo").apply { writeText("original") }
        AvfSharedDirectories.create(files, emptyList(), storage)!!.use { plan ->
            assertFalse(plan.hasFolders)
            assertTrue(plan.hasAndroidStorage)
            assertEquals(AvfSharedDirectories.ANDROID_STORAGE_TAG, plan.exports.single().tag)
            assertEquals(storage, plan.exports.single().directory)
        }
        assertEquals("original", keep.readText())
    }

    @Test fun android17ExportsOnlySelectedFuseFoldersAndFuseIndexWithPrivateSocketsAndNoSdcard() {
        val files = temporary.newFolder()
        val publicRoot = temporary.newFolder()
        val shared = folder(publicRoot, "Project")
        val unconnected = folder(publicRoot, "Unconnected")
        val data = File(shared.directory, "keep").apply { writeText("shared bytes") }
        val plan = AvfSharedDirectories.create(files, listOf(shared), publicRoot = publicRoot, requirePublicFolders = true)!!
        assertTrue(plan.requiresStoragePermission)
        assertFalse(plan.hasAndroidStorage)
        assertEquals(2, plan.exports.size)
        assertTrue(plan.exports.all { it.directory.toPath().startsWith(publicRoot.toPath()) })
        assertTrue(plan.exports.all { it.socket.toPath().startsWith(files.toPath()) })
        assertTrue(plan.exports.none { it.directory == publicRoot || it.directory.path == unconnected.directory })
        assertEquals("1\nts-${shared.id.replace("-", "")}\tProject\n", File(plan.exports.first().directory, "mounts").readText())
        val another = AvfSharedDirectories.create(files, listOf(shared), publicRoot = publicRoot, requirePublicFolders = true)!!
        AvfSharedDirectories.cleanup(files, plan.artifact, publicRoot)
        assertFalse(plan.exports.first().directory.exists())
        assertTrue(another.exports.first().directory.exists())
        assertEquals("shared bytes", data.readText())
        another.close()
    }

    @Test fun android17RejectsPrivateIndexSourcesBeforeProvisioningAndRejectsUnexpectedPublicPaths() {
        val files = temporary.newFolder()
        val publicRoot = temporary.newFolder()
        val privateFolder = folder(files, "Private")
        assertTrue(runCatching { AvfSharedDirectories.create(files, listOf(privateFolder), publicRoot = publicRoot,
            requirePublicFolders = true) }.exceptionOrNull()!!.message!!.contains("공유 저장소"))
        assertFalse(File(files, ".virtiofs").exists())
        assertTrue(publicRoot.listFiles()!!.isEmpty())
        val shared = folder(publicRoot, "Public")
        assertTrue(runCatching { AvfSharedDirectories.create(files, listOf(shared.copy(directory = publicRoot.path)),
            publicRoot = publicRoot, requirePublicFolders = true) }.isFailure)
        File(shared.directory).delete()
        Files.createSymbolicLink(File(shared.directory).toPath(), temporary.newFolder().toPath())
        assertTrue(runCatching { AvfSharedDirectories.validate(files, listOf(shared), publicRoot, true) }.isFailure)
    }

    @Test fun storageAndMaximumFolderConnectionsHaveDistinctTagsAndAllSocketsAreCleaned() {
        val files = temporary.newFolder()
        val folders = (1..128).map { folder(files, "Folder$it") }
        val storage = temporary.newFolder()
        val plan = AvfSharedDirectories.create(files, folders, storage)!!
        assertTrue(plan.hasFolders && plan.hasAndroidStorage)
        assertEquals(130, plan.exports.size)
        assertEquals(130, plan.exports.map { it.tag }.toSet().size)
        val last = plan.exports.last().socket.apply { writeText("socket placeholder") }
        plan.close()
        assertFalse(last.exists())
        assertTrue(storage.isDirectory)
    }

    private fun folder(files: File, name: String): SharedDirectoryExport {
        val id = UUID.randomUUID().toString()
        val directory = File(files, "shared-folders/$id").apply { mkdirs() }
        return SharedDirectoryExport(id, name, directory.path)
    }

    @Test fun noConnectionsProvisionNothingAndLongNamesDoNotOverflowWireTags() {
        val files = temporary.newFolder()
        assertNull(AvfSharedDirectories.create(files, emptyList()))
        assertTrue(files.listFiles()!!.isEmpty())
        val shared = folder(files, "A".repeat(64))
        val data = File(shared.directory, "keep").apply { writeText("user data") }
        val unconnected = folder(files, "Private")
        AvfSharedDirectories.create(files, listOf(shared))!!.use { plan ->
            assertEquals(2, plan.exports.size)
            assertEquals(AvfSharedDirectories.INDEX_TAG, plan.exports[0].tag)
            assertEquals(File(shared.directory), plan.exports[1].directory)
            assertTrue(plan.exports.all { it.tag.length <= 36 && it.socket.path.toByteArray().size <= 107 })
            assertTrue(plan.exports.none { it.directory == File(unconnected.directory) || it.directory == files })
            assertEquals("1\nts-${shared.id.replace("-", "")}\t${shared.name}\n",
                File(plan.exports[0].directory, "mounts").readText())
        }
        assertEquals("user data", data.readText())
    }

    @Test fun independentBootsHaveIndependentSocketsAndRenameKeepsTheSameFolderTag() {
        val files = temporary.newFolder()
        val shared = folder(files, "Code")
        AvfSharedDirectories.create(files, listOf(shared))!!.use { first ->
            AvfSharedDirectories.create(files, listOf(shared.copy(name = "Renamed")))!!.use { second ->
                assertEquals(first.exports[1].tag, second.exports[1].tag)
                assertNotEquals(first.exports[1].socket, second.exports[1].socket)
                assertTrue(File(first.exports[0].directory, "mounts").readText().contains("\tCode\n"))
            }
        }
    }

    @Test fun appPrivatePathsSymlinkRootsAndDuplicateConnectionsAreRejected() {
        val files = temporary.newFolder()
        val shared = folder(files, "Code")
        assertTrue(runCatching { AvfSharedDirectories.create(files, listOf(shared.copy(directory = files.path))) }.isFailure)
        assertTrue(runCatching { AvfSharedDirectories.create(files, listOf(shared, shared)) }.isFailure)
        assertTrue(runCatching { AvfSharedDirectories.create(files, listOf(shared.copy(name = "../private"))) }.isFailure)
        File(shared.directory).delete()
        Files.createSymbolicLink(File(shared.directory).toPath(), temporary.newFolder().toPath())
        assertTrue(runCatching { AvfSharedDirectories.create(files, listOf(shared)) }.isFailure)
    }

    @Test fun cleanupDoesNotFollowGuestCreatedLinksOrTouchUserData() {
        val files = temporary.newFolder()
        val shared = folder(files, "Code")
        val plan = AvfSharedDirectories.create(files, listOf(shared))!!
        val outside = temporary.newFile().apply { writeText("private") }
        val table = File(plan.exports[0].directory, "mounts")
        table.delete()
        Files.createSymbolicLink(table.toPath(), outside.toPath())
        plan.close()
        assertEquals("private", outside.readText())
        assertTrue(File(shared.directory).isDirectory)
    }

    @Test fun anOwnerRestartCanCleanAStoppedBootByItsPrivateArtifactIdentity() {
        val files = temporary.newFolder()
        val shared = folder(files, "Code")
        val plan = AvfSharedDirectories.create(files, listOf(shared))!!
        val index = plan.exports.first().directory
        AvfSharedDirectories.cleanup(files, plan.artifact)
        assertFalse(index.exists())
        assertTrue(File(shared.directory).isDirectory)
        assertTrue(runCatching { AvfSharedDirectories.cleanup(files, "../../private") }.isFailure)
    }
}
