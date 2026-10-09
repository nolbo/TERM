package dev.terminal.runtime.workspace

import dev.terminal.runtime.session.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class WorkspaceRegistryTest {
    private var encoded: String? = null
    private fun registry() = WorkspaceRegistry({ encoded }, { encoded = it })
    private fun permanent() = Workspace(UUID.randomUUID().toString(), "Project", false)
    @Test fun reorderedGroupsSurviveRecreationEditingAndPromotionWithoutChangingSessionState() {
        val registry = registry()
        val one = permanent().copy(launches = listOf(WorkspaceLaunch(UUID.randomUUID().toString(), "Shell", "profile", startWithWorkspace = true)))
        val two = permanent()
        registry.save(one); registry.save(two)
        val temporary = registry.createTemporary("Temporary")
        registry.move("native:one", one.id); registry.select(temporary)
        val before = registry.snapshot()
        registry.reorder(listOf(temporary, two.id, one.id))
        assertEquals(before.copy(workspaces = listOf(before.workspaces.last(), two, one)), registry().snapshot())

        val renamed = one.copy(name = "Renamed")
        registry().save(renamed)
        registry().save(Workspace(temporary, "Promoted", false))
        val added = permanent()
        registry().save(added)
        val restored = registry().snapshot()
        assertEquals(listOf(temporary, two.id, one.id, added.id), restored.workspaces.map { it.id })
        assertEquals(renamed, restored.workspaces[2])
        assertFalse(restored.workspaces.first().temporary)
        assertEquals(before.memberships, restored.memberships)
        assertEquals(before.selected, restored.selected)
    }
    @Test fun staleOrderIgnoresDeletedGroupsAndRetainsNewGroups() {
        val registry = registry(); val one = permanent(); val two = permanent(); val added = permanent()
        registry.save(one); registry.save(two)
        val requested = listOf(two.id, one.id)
        registry.delete(one.id); registry.save(added)
        registry.reorder(requested)
        assertEquals(listOf(two, added), registry().snapshot().workspaces)
        registry.reorder(listOf(added.id, two.id))
        registry.delete(added.id)
        assertEquals(listOf(two), registry().snapshot().workspaces)
    }
    @Test fun malformedOrOversizedOrderCannotReplaceSavedMetadata() {
        val registry = registry(); val workspace = permanent(); registry.save(workspace)
        val previous = encoded
        listOf(listOf(workspace.id, workspace.id), listOf("../workspace"), List(129) { UUID.randomUUID().toString() }).forEach { order ->
            assertTrue(runCatching { registry.reorder(order) }.isFailure)
            assertEquals(previous, encoded)
        }
    }
    @Test fun membershipsAndFilterSurviveRegistryRecreationAndDeletionOnlyUnassigns() {
        val registry = registry()
        val workspace = permanent()
        registry.save(workspace); registry.move("native:one", workspace.id); registry.select(workspace.id)
        assertEquals(workspace.id, registry().snapshot().memberships["native:one"])
        assertEquals(workspace.id, registry().snapshot().selected)
        registry().delete(workspace.id)
        assertEquals(WorkspaceRegistry.UNASSIGNED, registry().snapshot().selected)
        assertTrue(registry().snapshot().memberships.isEmpty())
    }
    @Test fun everySessionHasAtMostOneWorkspaceAndBothGlobalFiltersAlwaysWork() {
        val registry = registry(); val one = permanent(); val two = permanent()
        registry.save(one); registry.save(two); registry.move("s", one.id); registry.move("s", two.id)
        assertEquals(mapOf("s" to two.id), registry.snapshot().memberships)
        registry.select(WorkspaceRegistry.ALL); assertTrue(registry.snapshot().includes("s")); assertTrue(registry.snapshot().includes("other"))
        registry.select(WorkspaceRegistry.UNASSIGNED); assertFalse(registry.snapshot().includes("s")); assertTrue(registry.snapshot().includes("other"))
        registry.move("s", null); assertTrue(registry.snapshot().includes("s"))
    }
    @Test fun temporaryCollectionRequiresEmptyUnselectedAndAuthoritativeState() {
        val registry = registry(); val id = registry.createTemporary("Temporary")
        registry.move("s", id); registry.reconcile(setOf("s")); assertEquals(1, registry.snapshot().workspaces.size)
        registry.select(id); registry.reconcile(emptySet()); assertEquals(1, registry.snapshot().workspaces.size)
        registry.select(WorkspaceRegistry.ALL); registry.reconcile(null); assertEquals(1, registry.snapshot().workspaces.size)
        registry.reconcile(emptySet(), setOf(id)); assertEquals(1, registry.snapshot().workspaces.size)
        registry.reconcile(emptySet()); assertTrue(registry.snapshot().workspaces.isEmpty())
    }
    @Test fun promotionRetainsIdentityMembershipAndPermanentEmptyWorkspace() {
        val registry = registry(); val id = registry.createTemporary("Temp")
        registry.move("s", id); registry.save(Workspace(id, "Permanent", false)); registry.reconcile(setOf("s"))
        assertEquals(id, registry.snapshot().memberships["s"])
        registry.reconcile(emptySet()); assertEquals(id, registry.snapshot().workspaces.single().id)
    }
    @Test fun launchOverridesCwdWithoutMutatingProfileOrEnvironmentReferences() {
        val environment = Environment.create(SessionRuntime.ROOTLESS, "Debian")
        val profile = SessionProfile("profile", "Shell", SessionRuntime.ROOTLESS, "/bin/bash", listOf("-l"), "/root", environmentId = environment.id)
        val configuration = WorkspaceLaunch(UUID.randomUUID().toString(), "Code", profile.id, "/mnt/term-shares/code")
        val spec = configuration.resolve(profile) { it.resolve(environment) }
        assertEquals("/root", profile.workingDirectory)
        assertEquals("/mnt/term-shares/code", spec.workingDirectory)
        assertEquals(profile.id, spec.profileId); assertEquals(environment.id, spec.environmentId); assertEquals(environment.storage.id, spec.storageId)
        assertEquals("/root", configuration.copy(directory = "").resolve(profile) { it.resolve(environment) }.workingDirectory)
    }
    @Test fun invalidUpdatesPreserveLastSavedState() {
        val registry = registry(); val workspace = permanent(); registry.save(workspace)
        val previous = encoded
        assertTrue(runCatching { registry.move("s", UUID.randomUUID().toString()) }.isFailure)
        assertTrue(runCatching { registry.select("../../bad") }.isFailure)
        assertEquals(previous, encoded)
    }
    @Test fun oversizedLaunchSettingsCannotReplaceReadableState() {
        val registry = registry()
        val launches = List(64) { WorkspaceLaunch(UUID.randomUUID().toString(), "Launch $it", "profile", "/" + "a".repeat(4095)) }
        val first = permanent().copy(launches = launches)
        registry.save(first)
        val previous = encoded
        assertTrue(runCatching { registry.save(permanent().copy(launches = launches)) }.isFailure)
        assertEquals(previous, encoded)
        assertEquals(first, registry().snapshot().workspaces.single())
    }
}
