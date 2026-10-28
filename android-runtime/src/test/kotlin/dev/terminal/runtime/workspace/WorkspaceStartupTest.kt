package dev.terminal.runtime.workspace

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class WorkspaceStartupTest {
    private fun launch(name: String, start: Boolean = false) =
        WorkspaceLaunch(UUID.randomUUID().toString(), name, "profile", "/tmp", start)
    private fun workspace(launches: List<WorkspaceLaunch>) = Workspace(UUID.randomUUID().toString(), "Project", false, launches)

    @Test fun oldLaunchMetadataDoesNotOptIntoAutomaticStartup() {
        val original = launch("Manual")
        val json = original.json().apply { remove("startWithWorkspace") }
        assertEquals(original, WorkspaceLaunch.parse(json))
        val workspace = workspace(listOf(WorkspaceLaunch.parse(json)))
        assertTrue(WorkspaceRegistry.State(listOf(workspace), selected = workspace.id).startupLaunches().isEmpty())
    }

    @Test fun multipleStartupChoicesSurviveRegistryRecreationAndKeepConfigurationOrder() {
        var encoded: String? = null
        fun registry() = WorkspaceRegistry({ encoded }, { encoded = it })
        val one = launch("One", true)
        val manual = launch("Manual")
        val two = launch("Two", true)
        val workspace = workspace(listOf(one, manual, two))
        registry().save(workspace)
        registry().select(workspace.id)
        assertEquals(listOf(one, two), registry().snapshot().startupLaunches())
        assertEquals(workspace, registry().snapshot().workspaces.single())
        val restored = WorkspaceRegistry.State.parse(JSONObject(encoded!!).toString())
        assertEquals(listOf(one, two), restored.startupLaunches())
    }

    @Test fun occupiedOrPreparingWorkspacesAndGlobalFiltersNeverCreateReplacementSessions() {
        val workspace = workspace(listOf(launch("One", true), launch("Two", true)))
        val empty = WorkspaceRegistry.State(listOf(workspace), selected = workspace.id)
        assertEquals(2, empty.startupLaunches().size)
        assertTrue(empty.copy(memberships = mapOf("session" to workspace.id)).startupLaunches().isEmpty())
        assertTrue(empty.copy(memberships = mapOf("workspace-launch:pending" to workspace.id)).startupLaunches().isEmpty())
        assertTrue(empty.startupLaunches(setOf(workspace.id)).isEmpty())
        assertEquals(2, empty.startupLaunches(setOf(UUID.randomUUID().toString())).size)
        assertTrue(empty.copy(selected = WorkspaceRegistry.ALL).startupLaunches().isEmpty())
        assertTrue(empty.copy(selected = WorkspaceRegistry.UNASSIGNED).startupLaunches().isEmpty())
    }

    @Test fun removingConfigurationsOrClearingSelectionsRemovesTheirStartupBehavior() {
        val one = launch("One", true)
        val two = launch("Two", true)
        val workspace = workspace(listOf(one, two)).copy(launches = listOf(two))
        assertEquals(listOf(two), WorkspaceRegistry.State(listOf(workspace), selected = workspace.id).startupLaunches())
        val disabled = workspace.copy(launches = listOf(two.copy(startWithWorkspace = false)))
        assertTrue(WorkspaceRegistry.State(listOf(disabled), selected = disabled.id).startupLaunches().isEmpty())
    }

    @Test fun ownerRestartUnblocksLocalStartupDespiteAnUnrecoveredVm() {
        val local = workspace(listOf(launch("Local", true)))
        val remote = workspace(listOf(launch("VM", true)))
        val temporary = Workspace(UUID.randomUUID().toString(), "Temporary", true)
        var encoded = WorkspaceRegistry.State(listOf(local, remote, temporary), mapOf(
            "native-dead" to local.id, "rootless-dead" to local.id,
            "ubuntu-dead" to local.id, "archlinux-dead" to local.id,
            "avf-unrecovered" to remote.id,
        ), local.id).json().toString()
        val registry = WorkspaceRegistry({ encoded }, { encoded = it })
        assertTrue(registry.snapshot().startupLaunches().isEmpty())
        registry.reconcileAfterOwnerRestart(emptySet())
        // Unknown AVF state still prevents full collection, but cannot retain dead local PTYs.
        registry.reconcile(null)
        val restored = WorkspaceRegistry.State.parse(encoded)
        assertEquals(local.launches, restored.startupLaunches())
        assertEquals(local.id, restored.selected)
        assertEquals(mapOf("avf-unrecovered" to remote.id), restored.memberships)
        assertEquals(listOf(local, remote, temporary), restored.workspaces)
        assertTrue(restored.copy(selected = remote.id).startupLaunches().isEmpty())
    }

    @Test fun ownerRestartPreservesArchivedLocalSessionsAndUnknownOwners() {
        val workspace = workspace(listOf(launch("Local", true)))
        val memberships = mapOf("native-retained" to workspace.id, "rootless-retained" to workspace.id,
            "future-runtime-session" to workspace.id, "avf-unrecovered" to workspace.id)
        var encoded = WorkspaceRegistry.State(listOf(workspace), memberships, workspace.id).json().toString()
        val registry = WorkspaceRegistry({ encoded }, { encoded = it })
        registry.reconcileAfterOwnerRestart(setOf("native-retained", "rootless-retained"))
        assertEquals(memberships, registry.snapshot().memberships)
        assertTrue(registry.snapshot().startupLaunches().isEmpty())
    }
}
