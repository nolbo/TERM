package dev.terminal.runtime.workspace

import dev.terminal.runtime.session.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class WorkspaceLaunchTitleTest {
    @get:Rule val temporary = TemporaryFolder()
    private val profile = SessionProfile("profile", "Original profile", SessionRuntime.NATIVE, "/system/bin/sh",
        workingDirectory = "/home", startupCommand = "printf ready")
    private fun launch(name: String = "Workspace build", directory: String = "") =
        WorkspaceLaunch(UUID.randomUUID().toString(), name, profile.id, directory)

    @Test fun workspaceNameSetsTheCustomTitleWithoutChangingTheReferencedProfile() {
        val spec = launch(directory = "/project").resolve(profile) { it.resolve() }
        assertEquals("Workspace build", spec.tabTitle)
        assertEquals(profile.name, spec.profileName)
        assertEquals(profile.copy(workingDirectory = "/project"), spec.toProfile())
        assertEquals("/home", profile.workingDirectory)
        assertNull(profile.resolve().tabTitle)
        assertEquals("한".repeat(128), launch("한".repeat(128)).resolve(profile) { it.resolve() }.tabTitle)
    }

    @Test fun unnamedLaunchUsesTheProfileTitleAndKeepsTheDirectoryOverride() {
        listOf("", "   ").forEach { name ->
            val spec = launch(name, "/project").resolve(profile) { it.resolve() }
            assertNull(spec.tabTitle)
            assertEquals(profile.name, spec.profileName)
            assertEquals(profile.id, spec.profileId)
            assertEquals(profile.copy(workingDirectory = "/project"), spec.toProfile())
            assertEquals(spec, SessionLaunchSpec.fromJson(spec.toJson().toString()))
            assertEquals("/home", profile.workingDirectory)
        }
    }

    @Test fun optionalNamesSurviveRegistryRecreationWithoutWeakeningNameLimits() {
        val unnamed = launch("").copy(startWithWorkspace = true)
        var encoded: String? = null
        fun registry() = WorkspaceRegistry({ encoded }, { encoded = it })
        val workspace = Workspace(UUID.randomUUID().toString(), "Workspace", false, listOf(unnamed))
        registry().save(workspace)
        registry().select(workspace.id)
        assertEquals(workspace, registry().snapshot().workspaces.single())
        assertEquals(listOf(unnamed), registry().snapshot().startupLaunches())
        assertEquals(unnamed, WorkspaceLaunch.parse(unnamed.json().apply { remove("name") }))
        assertThrows(IllegalArgumentException::class.java) { workspace.copy(name = "") }
        assertThrows(IllegalArgumentException::class.java) { launch("a".repeat(129)) }
        assertThrows(IllegalArgumentException::class.java) { launch("line\nbreak") }
    }

    @Test fun launchSnapshotsRoundTripTheCustomTitleAndAcceptOlderRequests() {
        val spec = launch().resolve(profile) { it.resolve() }
        val restored = SessionLaunchSpec.fromJson(spec.toJson().toString())
        assertEquals(spec, restored)
        assertEquals(spec.hashCode(), restored.hashCode())
        assertNotEquals(profile.resolve(), restored)
        assertNull(SessionLaunchSpec.fromJson(profile.resolve().toJson().toString()).tabTitle)
        assertThrows(IllegalArgumentException::class.java) { spec.withTabTitle(" ") }
        assertThrows(IllegalArgumentException::class.java) { spec.withTabTitle("a".repeat(129)) }
        assertThrows(IllegalArgumentException::class.java) { spec.withTabTitle("line\nbreak") }
    }

    @Test fun environmentResolutionPreservesTheCustomTitleForLegacyAndCurrentSnapshots() {
        val registry = EnvironmentRegistry(temporary.newFolder())
        val environment = Environment.create(SessionRuntime.NATIVE, "Native environment")
        val saved = registry.transaction { save(profile, environment) }
        val legacy = launch().resolve(saved) { it.resolve() }
        val current = launch().resolve(saved) { registry.transaction { resolve(it) } }
        registry.transaction {
            listOf(legacy, current).forEach { spec ->
                val resolved = resolveLaunch(SessionLaunchSpec.fromJson(spec.toJson().toString()))
                assertEquals(environment.id, resolved.executionEnvironment!!.id)
                assertEquals("Workspace build", resolved.tabTitle)
                assertEquals(profile.name, resolved.profileName)
            }
        }
    }
}
