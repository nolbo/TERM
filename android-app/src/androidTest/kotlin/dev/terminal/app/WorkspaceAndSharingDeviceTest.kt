package dev.terminal.app

import android.content.*
import android.os.*
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.ipc.*
import dev.terminal.runtime.lifecycle.VmRuntimeService
import dev.terminal.runtime.session.*
import dev.terminal.runtime.storage.*
import dev.terminal.runtime.workspace.*
import org.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WorkspaceAndSharingDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun await(message: String, timeout: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(message, condition())
    }
    private fun withRuntime(work: (IRuntimeService) -> Unit) {
        val ready = CountDownLatch(1); lateinit var service: IRuntimeService
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { service = IRuntimeService.Stub.asInterface(binder); ready.countDown() }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        try { assertTrue(ready.await(10, TimeUnit.SECONDS)); work(service) } finally { context.unbindService(connection) }
    }
    private fun rows(runtime: IRuntimeService) = JSONArray(runtime.sessionsJson).let { a -> List(a.length()) { a.getJSONObject(it) } }
    private fun spec(runtime: SessionRuntime): SessionLaunchSpec = SessionProfiles(context).let { profiles -> profiles.resolve(profiles.all().first { it.runtime == runtime }) }

    private fun uiNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && predicate(node)) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun tapUi(predicate: (AccessibilityNodeInfo) -> Boolean) {
        var target: AccessibilityNodeInfo? = null
        await("Session menu control is ready") {
            target = uiNode(predicate)
            while (target != null && !target!!.isClickable) target = target!!.parent
            target?.isEnabled == true
        }
        assertTrue(target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test fun newSessionWorkspaceConfigurationUsesTheExistingLaunchPathAndCwdWithoutEditingItsProfile() = withRuntime { runtime ->
        val profiles = SessionProfiles(context)
        val profile = profiles.all().first { it.runtime == SessionRuntime.NATIVE }
        val original = WorkspaceRegistry.State.parse(runtime.workspacesJson)
        val before = rows(runtime).mapTo(mutableSetOf()) { it.getString("id") }
        val directory = File(context.filesDir, "workspace-launch-ui-${UUID.randomUUID()}").apply { assertTrue(mkdir()) }
        val launch = WorkspaceLaunch(UUID.randomUUID().toString(), "Native workspace launch", profile.id, directory.path)
        val workspace = Workspace(UUID.randomUUID().toString(), "Launch menu fixture", false, listOf(launch), "terminal")
        var owned: String? = null
        try {
            assertEquals("", runtime.saveWorkspace(workspace.json().toString()))
            assertEquals("", runtime.selectWorkspace(WorkspaceRegistry.UNASSIGNED))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val runtimeField = MainActivity::class.java.getDeclaredField("runtime").apply { isAccessible = true }
                await("Main Activity connects to the existing runtime owner") {
                    var connected = false
                    scenario.onActivity { connected = runtimeField.get(it) != null }
                    connected
                }
                tapUi { it.contentDescription?.toString() == context.getString(R.string.workspace_selector,
                    context.getString(R.string.workspace_unassigned)) }
                tapUi { it.text?.toString() == workspace.name }
                await("The Workspace is explicitly selected") {
                    WorkspaceRegistry.State.parse(runtime.workspacesJson).selected == workspace.id
                }
                val newLabel = context.getString(R.string.new_session)
                tapUi { it.contentDescription?.toString() == newLabel || it.text?.toString() == newLabel }
                await("Current Workspace category appears in New session") {
                    uiNode { it.text?.toString() == context.getString(R.string.workspace_new_session_group, workspace.name) } != null
                }
                assertEquals("Opening the menu cannot create a session", before, rows(runtime).mapTo(mutableSetOf()) { it.getString("id") })
                tapUi { it.text?.toString() == launch.name }
                await("Workspace launch creates one Native session") {
                    rows(runtime).any { it.getString("id") !in before && it.getString("state") == "RUNNING" }
                }
                val id = rows(runtime).single { it.getString("id") !in before }.getString("id").also { owned = it }
                assertEquals(workspace.id, WorkspaceRegistry.State.parse(runtime.workspacesJson).memberships[id])
                assertEquals(profile, profiles.find(profile.id))
                runtime.writeSessionInput(id, "printf workspace-launch > cwd-proof.txt\n".toByteArray())
                await("The launched shell uses the Workspace cwd override") {
                    File(directory, "cwd-proof.txt").takeIf { it.isFile }?.readText() == "workspace-launch"
                }
                assertEquals(before + id, rows(runtime).mapTo(mutableSetOf()) { it.getString("id") })
            }
        } finally {
            owned?.let { runtime.terminateSession(it) }
            runtime.selectWorkspace(original.selected)
            runtime.deleteWorkspace(workspace.id)
            directory.deleteRecursively()
        }
    }

    @Test fun workspaceSwitchDeleteMoveAndRecreationKeepTheSameNativeProcess() = withRuntime { runtime ->
        ActivityScenario.launch(MainActivity::class.java).use { main ->
            val original = WorkspaceRegistry.State.parse(runtime.workspacesJson)
            val before = rows(runtime).mapTo(mutableSetOf()) { it.getString("id") }
            val workspace = Workspace(UUID.randomUUID().toString(), "Workspace device test", false)
            val empty = Workspace(UUID.randomUUID().toString(), "Empty device test", false)
            var owned: String? = null
            val marker = "workspace-${UUID.randomUUID()}"
            val output = StringBuffer()
            val listener = object : ITerminalStateListener.Stub() {
                override fun onCheckpoint(state: ParcelFileDescriptor) { ParcelFileDescriptor.AutoCloseInputStream(state).use { it.copyTo(java.io.ByteArrayOutputStream()) } }
                override fun onOutput(bytes: ByteArray) { output.append(bytes.toString(Charsets.UTF_8)) }
                override fun onResize(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {}
                override fun onExited(code: Int) {}
                override fun onError(message: String) { output.append(message) }
            }
            try {
                assertEquals("", runtime.saveWorkspace(workspace.json().toString())); assertEquals("", runtime.saveWorkspace(empty.json().toString()))
                assertEquals("", runtime.selectWorkspace(workspace.id))
                runtime.createSessionInWorkspace(spec(SessionRuntime.NATIVE).toJson().toString(), UUID.randomUUID().toString(), workspace.id, false, 24, 80)
                await("Native session created in workspace") { rows(runtime).any { it.getString("id") !in before && it.getString("state") == "RUNNING" } }
                val id = rows(runtime).single { it.getString("id") !in before }.getString("id").also { owned = it }
                runtime.attachTerminal(id, listener, 24, 80, 10, 20, "{}")
                runtime.writeSessionInput(id, "TERM_WORKSPACE_TEST='$marker'\nprintf 'before:%s\\n' \"\$TERM_WORKSPACE_TEST\"\n".toByteArray())
                await("Shell accepted marker") { output.contains("before:$marker") }
                assertEquals(workspace.id, WorkspaceRegistry.State.parse(runtime.workspacesJson).memberships[id])
                val count = rows(runtime).size
                assertEquals("", runtime.selectWorkspace(empty.id)); main.recreate()
                assertEquals(count, rows(runtime).size) // An empty filter never creates a session.
                assertEquals("", runtime.deleteWorkspace(workspace.id))
                assertFalse(WorkspaceRegistry.State.parse(runtime.workspacesJson).memberships.containsKey(id))
                assertEquals("", runtime.moveSessionToWorkspace(id, empty.id))
                ActivityScenario.launch(WorkspacesActivity::class.java).use { it.recreate() }
                ActivityScenario.launch<WorkspaceEditActivity>(WorkspaceEditActivity.intent(context, empty.id)).use { it.recreate() }
                val draft = WorkspaceLaunch(UUID.randomUUID().toString(), "Lifecycle", SessionProfiles(context).all().first().id)
                ActivityScenario.launch<WorkspaceLaunchEditActivity>(WorkspaceLaunchEditActivity.intent(context, draft.json().toString())).use { it.recreate() }
                ActivityScenario.launch(SharedFoldersActivity::class.java).use { it.recreate() }
                assertEquals(empty.id, WorkspaceRegistry.State.parse(runtime.workspacesJson).memberships[id])
                runtime.writeSessionInput(id, "printf 'after:%s\\n' \"\$TERM_WORKSPACE_TEST\"\n".toByteArray())
                await("Same shell variable survived workspace and UI changes") { output.contains("after:$marker") }
                assertTrue(rows(runtime).map { it.getString("id") }.containsAll(before + id))
            } finally {
                owned?.let { runCatching { runtime.detachTerminal(it, listener) }; runtime.terminateSession(it) }
                runtime.selectWorkspace(original.selected); runtime.deleteWorkspace(workspace.id); runtime.deleteWorkspace(empty.id)
            }
        }
    }

    @Test fun nativeAndInstalledRootlessShareDataWithoutRestartingExistingSessions() = withRuntime { runtime ->
        ActivityScenario.launch(MainActivity::class.java).use {
            val profiles = SessionProfiles(context)
            val native = spec(SessionRuntime.NATIVE)
            val prepare = InstrumentationRegistry.getArguments().getString("prepareRootless") == "true"
            val rootlessProfile = profiles.all().firstOrNull { profile -> profile.runtime in setOf(SessionRuntime.ROOTLESS, SessionRuntime.ROOTLESS_UBUNTU, SessionRuntime.ROOTLESS_ARCH) &&
                profile.environmentId?.let { JSONObject(runtime.getEnvironmentSoftwareJson(it.value)).optBoolean("installed") } == true }
                ?: profiles.all().firstOrNull { prepare && it.runtime == SessionRuntime.ROOTLESS }
            assumeTrue("An installed Userspace Linux environment is required", rootlessProfile != null)
            val linux = profiles.resolve(checkNotNull(rootlessProfile))
            val folder = SharedFolder(UUID.randomUUID().toString(), "Test${UUID.randomUUID().toString().take(8)}", setOf(checkNotNull(native.environmentId), checkNotNull(linux.environmentId)))
            val owned = mutableSetOf<String>()
            val before = rows(runtime).mapTo(mutableSetOf()) { row -> row.getString("id") }
            try {
                assertEquals("", runtime.saveSharedFolder(folder.json().toString()))
                val folders = JSONObject(runtime.sharedFoldersJson).getJSONArray("folders")
                val path = (0 until folders.length()).map { folders.getJSONObject(it) }.single { row -> row.getString("id") == folder.id }.getString("hostPath")
                val hostFile = File(path, "native.txt")
                val nativeSpec = SessionLaunchSpec(native.toProfile().copy(startupCommand = "printf native > '$path/native.txt'"), native.executionEnvironment)
                runtime.createSessionInWorkspace(nativeSpec.toJson().toString(), UUID.randomUUID().toString(), null, false, 24, 80)
                await("Native writes shared host path") { hostFile.exists() }
                val nativeId = rows(runtime).single { row -> row.getString("id") !in before }.getString("id"); owned += nativeId
                val linuxSpec = SessionLaunchSpec(linux.toProfile().copy(startupCommand = "cat '${folder.guestPath}/native.txt' > '${folder.guestPath}/linux.txt'"), linux.executionEnvironment)
                runtime.createSessionInWorkspace(linuxSpec.toJson().toString(), UUID.randomUUID().toString(), null, prepare, 24, 80)
                await("PRoot shares the same file data", if (prepare) 600_000 else 15_000) { File(path, "linux.txt").exists() }
                owned += rows(runtime).filter { row -> row.getString("id") !in before }.map { row -> row.getString("id") }
                assertEquals("native", File(path, "linux.txt").readText())
                val originalIds = rows(runtime).map { row -> row.getString("id") }
                assertEquals("", runtime.saveSharedFolder(folder.copy(name = folder.name + "New", environments = emptySet()).json().toString()))
                ActivityScenario.launch(SharedFoldersActivity::class.java).use { activity -> activity.recreate() }
                assertEquals(originalIds, rows(runtime).map { row -> row.getString("id") })
                val linuxId = rows(runtime).single { row -> row.getString("id") in owned && row.getString("runtime") != "NATIVE" }.getString("id")
                runtime.writeSessionInput(linuxId, "printf kept > '${folder.guestPath}/kept.txt'\n".toByteArray())
                await("Existing Rootless binding remains after disconnecting future sessions") { File(path, "kept.txt").exists() }
                runtime.createSessionInWorkspace(linux.toJson().toString(), UUID.randomUUID().toString(), null, false, 24, 80)
                await("New Rootless session created after policy change") { rows(runtime).count { row -> row.getString("id") !in before } == 3 }
                val newId = rows(runtime).single { row -> row.getString("id") !in before && row.getString("id") !in owned }.getString("id"); owned += newId
                val proof = "no-bind-${folder.id}"
                val home = File(SessionStorage.root(context.filesDir, linux.runtime, linux.storageId), "home/$proof")
                runtime.writeSessionInput(newId, "test ! -e '${folder.guestPath}/native.txt' && printf unbound > '/root/$proof'\n".toByteArray())
                await("New session receives no old Shared Folder binding") { home.exists() }
                home.delete()
            } finally {
                owned.forEach(runtime::terminateSession)
                await("Owned sessions terminated") { rows(runtime).none { row -> row.getString("id") in owned } }
                // Preserve a test folder if unrelated live work prevents safe deletion.
                runtime.deleteSharedFolder(folder.id)
            }
        }
    }

    @Test fun scopedHostIoRejectsLinksAndPinnedParentsSurvivePathReplacement() {
        val root = File(context.cacheDir, "shared-io-${UUID.randomUUID()}").apply { mkdir() }.canonicalFile
        val outside = File(context.cacheDir, "shared-outside-${UUID.randomUUID()}").apply { mkdir() }.canonicalFile
        File(outside, "secret").writeText("private")
        try {
            Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())
            Files.createSymbolicLink(File(root, "secret").toPath(), File(outside, "secret").toPath())
            ScopedDirectory.open(root).use { directory ->
                assertTrue(runCatching { directory.child("escape") }.isFailure)
                assertTrue(runCatching { directory.read("secret") }.isFailure)
                val pinned = directory.child("data", create = true)
                pinned.use { original ->
                    assertTrue(File(root, "data").renameTo(File(root, "original")))
                    Files.createSymbolicLink(File(root, "data").toPath(), outside.toPath())
                    original.create("safe").use { it.write("scoped".toByteArray()) }
                    assertEquals("scoped", File(root, "original/safe").readText())
                    assertFalse(File(outside, "safe").exists())
                }
            }
            assertEquals("private", File(outside, "secret").readText())
        } finally { ScopedDirectory.open(root).use { it.clear() }; root.delete(); outside.deleteRecursively() }
    }

    @Test fun legacyImportVerifiesCopiesPreservesSourcesAndEnvironmentDeletionRetainsOldData() = withRuntime { runtime ->
        assumeTrue("Legacy import requires all sessions to be idle", rows(runtime).none { it.getString("state") != "EXITED" })
        val profiles = SessionProfiles(context)
        val environment = Environment.create(SessionRuntime.ROOTLESS, "Legacy workspace test")
        val profile = SessionProfile(UUID.randomUUID().toString(), "Legacy workspace test", SessionRuntime.ROOTLESS,
            "/bin/sh", storageId = environment.storage.id, environmentId = environment.id)
        profiles.save(profile, environment)
        val source = File(SessionStorage.root(context.filesDir.canonicalFile, environment.sessionRuntime, environment.storage.id), "workspace").apply { mkdirs() }
        File(source, "project.txt").writeText("keep original")
        val folderName = "Legacy${UUID.randomUUID().toString().take(8)}"
        var folderId: String? = null
        fun operation(id: String): JSONObject? = JSONObject(runtime.sharedFoldersJson).getJSONArray("operations").let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.getString("id") == id }
        }
        try {
            val request = UUID.randomUUID().toString()
            assertEquals("", runtime.startSharedFolderOperation(request, JSONObject().put("mode", "IMPORT_LEGACY")
                .put("sourceId", "environment:${environment.id}").put("name", folderName).toString()))
            await("Legacy data copied and verified") { operation(request)?.getString("state") != "RUNNING" }
            assertEquals(operation(request).toString(), "COMPLETED", operation(request)?.getString("state"))
            val folders = JSONObject(runtime.sharedFoldersJson).getJSONArray("folders")
            val folder = (0 until folders.length()).map { folders.getJSONObject(it) }.single { it.getString("name") == folderName }
            folderId = folder.getString("id")
            assertEquals("keep original", File(source, "project.txt").readText())
            assertEquals("keep original", File(folder.getString("hostPath"), "project.txt").readText())
            val denied = runtime.startSharedFolderOperation(UUID.randomUUID().toString(), JSONObject().put("mode", "TO_VM").put("folderId", folderId)
                .put("environmentId", environment.id.value).put("path", "project.txt").toString())
            assertTrue("Unconnected environment cannot transfer host data", denied.isNotEmpty())
            assertEquals("", runtime.saveSharedFolder(SharedFolder.parse(folder).copy(environments = setOf(environment.id)).json().toString()))
            Files.createSymbolicLink(File(source, "link").toPath(), File(source, "project.txt").toPath())
            val failed = UUID.randomUUID().toString()
            assertEquals("", runtime.startSharedFolderOperation(failed, JSONObject().put("mode", "IMPORT_LEGACY")
                .put("sourceId", "environment:${environment.id}").put("name", folderName + "Bad").toString()))
            await("Unsafe link import fails") { operation(failed)?.getString("state") != "RUNNING" }
            assertEquals("FAILED", operation(failed)?.getString("state"))
            assertEquals("keep original", File(source, "project.txt").readText())
            profiles.delete(profile.id)
            assertTrue("Links that could depend on deleted rootfs must block environment deletion", runtime.deleteEnvironment(environment.id.value).isNotEmpty())
            assertEquals("keep original", File(source, "project.txt").readText())
            Files.delete(File(source, "link").toPath()) // Only this test-created link; production never deletes it automatically.
            assertEquals("", runtime.deleteEnvironment(environment.id.value))
            val afterDelete = JSONObject(runtime.sharedFoldersJson).getJSONArray("folders").let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.single { it.getString("id") == folderId }
            }
            assertTrue(SharedFolder.parse(afterDelete).environments.isEmpty())
            assertEquals("", runtime.saveSharedFolder(SharedFolder.parse(afterDelete).copy(name = folderName + "Renamed").json().toString()))
            val retained = LegacyWorkspaces.sources(context.filesDir, emptyList()).single { it.id == "retained:${environment.sessionRuntime.wireName}-${environment.storage.id}" }
            assertEquals(source, retained.directory)
            assertEquals("keep original", File(retained.directory, "project.txt").readText())
            assertEquals("keep original", File(folder.getString("hostPath"), "project.txt").readText())
        } finally {
            profiles.delete(profile.id)
            runCatching { runtime.deleteEnvironment(environment.id.value) }
            folderId?.let { runtime.deleteSharedFolder(it) }
            val retained = source
            if (retained.exists()) { ScopedDirectory.open(retained).use { it.clear() }; retained.delete() }
        }
    }
}
