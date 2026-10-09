package dev.terminal.runtime.workspace

import dev.terminal.runtime.session.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class WorkspaceRegistryTest {
    private var encoded: String? = null
    private fun registry() = WorkspaceRegistry({ encoded }, { encoded = it })
    private fun permanent() = Workspace(UUID.randomUUID().toString(), "Project", false)
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
