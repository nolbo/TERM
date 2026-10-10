package dev.terminal.app

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import dev.terminal.runtime.session.EnvironmentRuntime
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.storage.SharedFolder
import dev.terminal.runtime.workspace.Workspace
import dev.terminal.runtime.workspace.WorkspaceLaunch
import dev.terminal.runtime.workspace.WorkspaceRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real settings Activities: field dialogs edit drafts; only the page Save writes runtime metadata. */
@RunWith(AndroidJUnit4::class)
class WorkspaceSettingsUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun label(id: Int) = context.getString(id)
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
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
    private fun node(scroll: Boolean = false, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            find(predicate)?.let { return it }
            if (scroll) find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(50)
        }
        throw AssertionError("Workspace/Shared Folder settings element missing")
    }
    private fun text(value: String, scroll: Boolean = false) = node(scroll) { it.text?.toString() == value }
    private fun control(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable && current.parent != null) current = current.parent
        return current
    }
    private fun checkable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isCheckable && current.parent != null) current = current.parent
        return current
    }
    private fun click(target: AccessibilityNodeInfo) {
        val text = target.text?.toString()
        val description = target.contentDescription?.toString()
        val className = target.className?.toString()
        // ACTION_SET_TEXT and service reconnection update Compose asynchronously.
        val ready = node { candidate ->
            candidate.className?.toString() == className &&
                ((text != null && candidate.text?.toString() == text) ||
                    (description != null && candidate.contentDescription?.toString() == description)) &&
                control(candidate).isClickable && control(candidate).isEnabled
        }
        assertTrue("Click ${text ?: description}", control(ready).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.uiAutomation.waitForIdle(100, 3_000)
    }
    private fun action(id: Int) = node { it.contentDescription?.toString() == label(id) }
    private fun field() = node { it.className?.toString() == "android.widget.EditText" }
    private fun input(value: String) {
        assertTrue(field().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        node { it.className?.toString() == "android.widget.EditText" && it.text?.toString() == value }
    }
    private fun disabledSave() = node {
        it.text?.toString() == label(R.string.save) && !control(it).isEnabled
    }
    private fun changeName(value: String, labelId: Int = R.string.workspace_name) {
        click(text(label(labelId)))
        input(value)
        click(text(label(if (labelId == R.string.workspace_name) R.string.confirm else R.string.save)))
        await("Field dialog closes before the next page action") {
            find { it.className?.toString() == "android.widget.EditText" } == null
        }
        text(value)
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(message, condition())
    }
    private fun activity(type: Class<out Activity>, stage: Stage = Stage.RESUMED): Activity? {
        var result: Activity? = null
        instrumentation.runOnMainSync {
            result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).firstOrNull { type.isInstance(it) }
        }
        return result
    }
    private fun assertResumed(type: Class<out Activity>) {
        await("${type.simpleName} owns the current screen") { activity(type) != null }
    }
    private fun recreate(type: Class<out Activity>, stage: Stage = Stage.RESUMED) {
        val previous = requireNotNull(activity(type, stage))
        instrumentation.runOnMainSync { previous.recreate() }
        if (stage == Stage.RESUMED) await("${type.simpleName} recreated") {
            activity(type)?.let { it !== previous } == true
        }
    }
    private fun withRuntime(work: (IRuntimeService) -> Unit) {
        val ready = CountDownLatch(1)
        lateinit var runtime: IRuntimeService
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                runtime = IRuntimeService.Stub.asInterface(binder); ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        try { assertTrue(ready.await(10, TimeUnit.SECONDS)); work(runtime) } finally { context.unbindService(connection) }
    }
    private fun workspaces(runtime: IRuntimeService) = WorkspaceRegistry.State.parse(runtime.workspacesJson).workspaces
    private fun folder(runtime: IRuntimeService, id: String): JSONObject? = JSONObject(runtime.sharedFoldersJson).getJSONArray("folders").let { array ->
        (0 until array.length()).map { array.getJSONObject(it) }.firstOrNull { it.getString("id") == id }
    }

    @Test fun workspaceAndLaunchDraftsSurviveRecreationAndSaveTogetherWithoutChangingProfiles() = withRuntime { runtime ->
        val profiles = SessionProfiles(context)
        val native = profiles.all().first { it.runtime == SessionRuntime.NATIVE }
        val alternate = profiles.all().first { it.id != native.id }
        val launch = WorkspaceLaunch(UUID.randomUUID().toString(), "UI launch", native.id)
        val workspace = Workspace(UUID.randomUUID().toString(), "UiWorkspace${UUID.randomUUID().toString().take(8)}", false, listOf(launch))
        assertEquals("", runtime.saveWorkspace(workspace.json().toString()))
        try {
            ActivityScenario.launch(WorkspacesActivity::class.java).use { scenario ->
                click(text(workspace.name, scroll = true))
                assertResumed(WorkspaceEditActivity::class.java)
                text(label(R.string.workspace_settings))
                changeName(workspace.name + "Edited")
                assertEquals(workspace, workspaces(runtime).single { it.id == workspace.id })
                click(text(label(R.string.workspace_icon)))
                assertResumed(WorkspaceEditActivity::class.java)
                assertNull(find { it.text?.toString() == label(R.string.workspace_icon_code) })
                click(action(R.string.workspace_icon_code))
                node { it.contentDescription?.toString() == label(R.string.workspace_icon_code) && checkable(it).isChecked }
                click(text(label(R.string.cancel)))
                await("Cancelled icon selection leaves the workspace draft unchanged") {
                    find { it.text?.toString() == label(R.string.cancel) } == null
                }
                action(R.string.workspace_icon_dashboard)
                assertEquals(workspace, workspaces(runtime).single { it.id == workspace.id })
                click(text(label(R.string.workspace_icon)))
                node { it.contentDescription?.toString() == label(R.string.workspace_icon_dashboard) && checkable(it).isChecked }
                click(action(R.string.workspace_icon_code))
                recreate(WorkspaceEditActivity::class.java)
                // A selected radio option is checkable but Android marks it non-clickable.
                node { it.contentDescription?.toString() == label(R.string.workspace_icon_code) && checkable(it).isChecked }
                assertNull(find { it.text?.toString() == label(R.string.save) })
                click(text(label(R.string.confirm)))
                assertResumed(WorkspaceEditActivity::class.java)
                action(R.string.workspace_icon_code)
                assertEquals(workspace, workspaces(runtime).single { it.id == workspace.id })
                click(text(launch.name, scroll = true))
                assertResumed(WorkspaceLaunchEditActivity::class.java)
                text(label(R.string.workspace_launch_settings))
                click(text(label(R.string.workspace_directory)))
                input("relative/path")
                assertFalse(control(disabledSave()).isEnabled)
                recreate(WorkspaceLaunchEditActivity::class.java)
                assertEquals("relative/path", field().text.toString())
                assertFalse(control(disabledSave()).isEnabled)
                input("/tmp")
                click(text(label(R.string.save)))
                click(text(label(R.string.profiles)))
                click(text(alternate.name))
                click(text(label(R.string.cancel)))
                text(native.name)
                click(text(label(R.string.profiles)))
                click(text(alternate.name))
                click(text(label(R.string.save)))
                text(alternate.name)
                // The pending result and workspace draft must also survive a stopped parent recreation.
                recreate(WorkspaceEditActivity::class.java, Stage.STOPPED)
                click(action(R.string.save)) // Save the launch into the workspace draft.
                assertResumed(WorkspaceEditActivity::class.java)
                text(label(R.string.workspace_settings))
                assertEquals(workspace, workspaces(runtime).single { it.id == workspace.id })
                recreate(WorkspaceEditActivity::class.java)
                text(workspace.name + "Edited")
                action(R.string.workspace_icon_code)
                click(action(R.string.back))
                text(label(R.string.profile_discard))
                click(text(label(R.string.cancel)))
                text(workspace.name + "Edited")
                click(action(R.string.save))
                await("Whole page Save publishes the draft") { workspaces(runtime).single { it.id == workspace.id }.name == workspace.name + "Edited" }
                val saved = workspaces(runtime).single { it.id == workspace.id }
                assertEquals("deployed_code", saved.icon)
                assertEquals(launch.copy(profileId = alternate.id, directory = "/tmp"), saved.launches.single())
                assertEquals(native, profiles.find(native.id))
                assertEquals(alternate, profiles.find(alternate.id))
                assertResumed(WorkspacesActivity::class.java)
                scenario.recreate()
                text(label(R.string.workspace_list))
                click(text(saved.name, scroll = true))
                assertResumed(WorkspaceEditActivity::class.java)
                click(action(R.string.delete))
                text(label(R.string.workspace_delete_summary))
                click(text(label(R.string.cancel)))
                assertEquals(saved, workspaces(runtime).single { it.id == workspace.id })
                click(action(R.string.delete))
                click(text(label(R.string.delete)))
                await("Workspace delete only removes its metadata") { workspaces(runtime).none { it.id == workspace.id } }
            }
        } finally { if (workspaces(runtime).any { it.id == workspace.id }) assertEquals("", runtime.deleteWorkspace(workspace.id)) }
    }

    @Test fun newWorkspaceIsCreatedOnlyByPageSaveAndBackDiscardsNameDraft() = withRuntime { runtime ->
        val name = "UiNewWorkspace${UUID.randomUUID().toString().take(8)}"
        var ownedId: String? = null
        try {
            ActivityScenario.launch(WorkspacesActivity::class.java).use {
                click(text(label(R.string.workspace_create)))
                assertResumed(WorkspaceEditActivity::class.java)
                assertTrue(control(text(label(R.string.save))).isEnabled)
                click(text(label(R.string.workspace_name)))
                val suggested = field().text.toString()
                assertTrue(suggested.startsWith(label(R.string.workspace_default_name)))
                assertTrue(workspaces(runtime).none { it.name == suggested })
                click(text(label(R.string.cancel)))
                await("Cancelled name dialog closes before reopening the field") {
                    find { it.className?.toString() == "android.widget.EditText" } == null
                }
                changeName(name)
                assertTrue(workspaces(runtime).none { it.name == name })
                click(action(R.string.back))
                click(text(label(R.string.palette_discard)))
                assertResumed(WorkspacesActivity::class.java)
                assertTrue(workspaces(runtime).none { it.name == name })
                click(text(label(R.string.workspace_create)))
                assertResumed(WorkspaceEditActivity::class.java)
                changeName(name)
                click(text(label(R.string.save)))
                await("New workspace saved") { workspaces(runtime).any { it.name == name } }
                val saved = workspaces(runtime).single { it.name == name }
                ownedId = saved.id
                assertFalse(saved.temporary)
                assertTrue(saved.launches.isEmpty())
                assertResumed(WorkspacesActivity::class.java)
                text(name, scroll = true)
            }
        } finally {
            (ownedId ?: workspaces(runtime).firstOrNull { it.name == name }?.id)?.let { assertEquals("", runtime.deleteWorkspace(it)) }
        }
    }

    @Test fun childLaunchAddDeleteAndIconCancelOnlyChangeTheParentDraft() = withRuntime { runtime ->
        val profile = SessionProfiles(context).all().first()
        val launch = WorkspaceLaunch(UUID.randomUUID().toString(), "Draft launch", profile.id)
        val workspace = Workspace(UUID.randomUUID().toString(), "UiDraft${UUID.randomUUID().toString().take(8)}", false, listOf(launch))
        assertEquals("", runtime.saveWorkspace(workspace.json().toString()))
        try {
            ActivityScenario.launch(WorkspacesActivity::class.java).use {
                click(text(workspace.name, scroll = true))
                assertResumed(WorkspaceEditActivity::class.java)
                click(text(label(R.string.workspace_icon)))
                assertResumed(WorkspaceEditActivity::class.java)
                click(action(R.string.workspace_icon_network))
                click(text(label(R.string.cancel)))
                await("Icon modal closes without changing the parent draft") {
                    find { it.text?.toString() == label(R.string.cancel) } == null
                }
                assertResumed(WorkspaceEditActivity::class.java)
                action(R.string.workspace_icon_dashboard)
                click(text(label(R.string.workspace_add_launch), scroll = true))
                assertResumed(WorkspaceLaunchEditActivity::class.java)
                changeName("Added launch", R.string.workspace_launch_name)
                click(text(label(R.string.save), scroll = true))
                assertResumed(WorkspaceEditActivity::class.java)
                text("Added launch", scroll = true)
                assertEquals(workspace, workspaces(runtime).single { it.id == workspace.id })
                click(text(launch.name, scroll = true))
                assertResumed(WorkspaceLaunchEditActivity::class.java)
                click(action(R.string.delete))
                click(text(label(R.string.delete)))
                assertResumed(WorkspaceEditActivity::class.java)
                assertEquals(workspace, workspaces(runtime).single { it.id == workspace.id })
                click(action(R.string.back))
                click(text(label(R.string.palette_discard)))
                assertResumed(WorkspacesActivity::class.java)
                assertEquals(workspace, workspaces(runtime).single { it.id == workspace.id })
            }
        } finally { assertEquals("", runtime.deleteWorkspace(workspace.id)) }
    }

    @Test fun sharedFolderNameAndConnectionsAreDraftedTogetherAndKeepTheSameDataPath() = withRuntime { runtime ->
        val environments = SessionProfiles(context).environments()
        val native = environments.first { it.runtime == EnvironmentRuntime.NATIVE }
        val rootless = environments.first { it.runtime == EnvironmentRuntime.ROOTLESS }
        val shared = SharedFolder(UUID.randomUUID().toString(), "UiShare${UUID.randomUUID().toString().take(8)}", setOf(native.id, rootless.id))
        assertEquals("", runtime.saveSharedFolder(shared.json().toString()))
        val hostPath = folder(runtime, shared.id)!!.getString("hostPath")
        File(hostPath, "fixture.txt").writeText("UI edits preserve folder identity and data")
        try {
            ActivityScenario.launch(SharedFoldersActivity::class.java).use {
                click(text(shared.name, scroll = true))
                assertResumed(SharedFolderEditActivity::class.java)
                text(label(R.string.shared_folder_settings))
                text(hostPath, scroll = true)
                // Reopen from the list so the first field is in view.
                click(action(R.string.back))
                text(label(R.string.shared_folder_list))
                click(text(shared.name, scroll = true))
                assertResumed(SharedFolderEditActivity::class.java)
                click(text(label(R.string.workspace_name)))
                input("../bad")
                node { it.text?.toString() == label(R.string.confirm) && !control(it).isEnabled }
                input(shared.name + "Edited")
                recreate(SharedFolderEditActivity::class.java)
                assertEquals(shared.name + "Edited", field().text?.toString())
                click(text(label(R.string.confirm)))
                click(node(scroll = true) { it.text?.toString() == native.name && control(it).isClickable })
                assertEquals(shared, SharedFolder.parse(folder(runtime, shared.id)!!))
                recreate(SharedFolderEditActivity::class.java)
                text(shared.name + "Edited")
                click(action(R.string.save))
                await("Name and connections saved together") { folder(runtime, shared.id)?.getString("name") == shared.name + "Edited" }
                val saved = folder(runtime, shared.id)!!
                assertEquals(shared.copy(name = shared.name + "Edited", environments = setOf(rootless.id)), SharedFolder.parse(saved))
                assertEquals(hostPath, saved.getString("hostPath"))
                assertEquals("UI edits preserve folder identity and data", File(hostPath, "fixture.txt").readText())
                text(label(R.string.shared_folder_list))
                assertResumed(SharedFoldersActivity::class.java)
                click(text(saved.getString("name"), scroll = true))
                assertResumed(SharedFolderEditActivity::class.java)
                text("/mnt/term-shares/" + saved.getString("name"), scroll = true)
                click(action(R.string.delete))
                text(label(R.string.shared_folder_delete_summary))
                click(text(label(R.string.cancel)))
                assertTrue(File(hostPath, "fixture.txt").exists())
                click(action(R.string.back))
                assertResumed(SharedFoldersActivity::class.java)
            }
        } finally { assertEquals("", runtime.deleteSharedFolder(shared.id)) }
    }
}
