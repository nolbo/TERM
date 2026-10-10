package dev.terminal.runtime.session

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class EnvironmentStorageAccessTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun profile() = SessionProfile(UUID.randomUUID().toString(), "Storage test", SessionRuntime.ROOTLESS, "/bin/bash")

    @Test fun newEnvironmentsDenyExportsWhileLegacyRecordsRetainTheirBehaviorAndRejectInvalidPolicy() {
        for (runtime in SessionRuntime.entries) {
            val environment = Environment.create(runtime, "Environment")
            assertFalse(environment.internalStorageAccess)
            assertEquals(environment, Environment.fromJson(environment.toJson()))
            assertTrue(Environment.fromJson(environment.toJson().apply { remove("internalStorageAccess") }).internalStorageAccess)
            for (invalid in listOf("true", 1, JSONObject.NULL)) {
                assertTrue(runCatching { Environment.fromJson(environment.toJson().put("internalStorageAccess", invalid)) }.isFailure)
            }
        }
    }

    @Test fun storageChoicePersistsPerEnvironmentAndLaunchSnapshotsCannotAuthorizeIt() {
        val files = temporary.newFolder()
        val registry = EnvironmentRegistry(files)
        val first = Environment.create(SessionRuntime.ROOTLESS, "First")
        val second = Environment.create(SessionRuntime.ROOTLESS, "Second", internalStorageAccess = true)
        val saved = registry.transaction { save(profile(), first).also { save(profile(), second) } }
        val forged = saved.resolve(first.copy(name = "Launch snapshot", internalStorageAccess = true))
        registry.transaction {
            assertFalse(resolveLaunch(forged).executionEnvironment!!.internalStorageAccess)
            setInternalStorageAccess(first.id, true)
        }
        val launched = EnvironmentRegistry(files).transaction { resolveLaunch(forged) }
        assertTrue(launched.executionEnvironment!!.internalStorageAccess)
        registry.transaction { setInternalStorageAccess(first.id, false) }
        EnvironmentRegistry(files).transaction {
            val updated = resolveLaunch(launched).executionEnvironment!!
            assertFalse(updated.internalStorageAccess)
            assertEquals("Launch snapshot", updated.name)
            assertTrue(requireEnvironment(second.id).internalStorageAccess)
            assertFalse(requireEnvironment(first.id).internalStorageAccess)
        }
    }

    @Test fun migrationPreservesCommonPermissionBehaviorAndNativeCannotClaimIndependentRevocation() {
        val native = SessionProfile("native", "Native", SessionRuntime.NATIVE, "/system/bin/sh")
        EnvironmentRegistry(temporary.newFolder()).transaction(listOf(native)) {
            val environment = all().single()
            assertTrue(environment.internalStorageAccess)
            assertTrue(runCatching { setInternalStorageAccess(environment.id, false) }.isFailure)
            assertEquals(environment, requireEnvironment(environment.id))
        }
    }
}
