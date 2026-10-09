package dev.terminal.app

import android.app.Activity
import android.content.*
import android.os.*
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import dev.terminal.runtime.session.*
import dev.terminal.runtime.workspace.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WorkspaceStartupDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        val succeeded = condition()
        val details = if (succeeded) "" else buildString {
            fun visit(node: AccessibilityNodeInfo?) {
                if (node == null || length > 2500) return
                node.text?.let { append("\n${it.toString().take(90)} [visible=${node.isVisibleToUser}, checkable=${node.isCheckable}, enabled=${node.isEnabled}]") }
                for (index in 0 until node.childCount) visit(node.getChild(index))
            }
            visit(instrumentation.uiAutomation.rootInActiveWindow)
        }
        assertTrue(message + details, succeeded)
    }
    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
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
    private fun tap(scroll: Boolean = false, predicate: (AccessibilityNodeInfo) -> Boolean) {
        var target: AccessibilityNodeInfo? = null
        await("UI action is available") {
            target = node(predicate)
            if (target == null && scroll) node { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            while (target != null && !target!!.isClickable) target = target!!.parent
            target?.isEnabled == true
        }
        assertTrue(target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    // Compose can expose supporting text separately or merge it with the row label.
    private fun text(value: String) = tap { it.text?.toString()?.startsWith(value) == true }
    private fun startupChoice(name: String) = tap(scroll = true) {
        it.text?.toString()?.startsWith(name) == true &&
            generateSequence(it) { node -> node.parent }.any { node -> node.isCheckable }
    }
    private fun save() = tap {
        it.contentDescription?.toString() == context.getString(R.string.save) || it.text?.toString() == context.getString(R.string.save)
    }
    private fun recreateEditor() {
        var activity: Activity? = null
        instrumentation.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).first()
            activity!!.recreate()
        }
        await("Editor recreation completes") {
            var recreated = false
            instrumentation.runOnMainSync {
                recreated = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it !== activity }
            }
            recreated
        }
    }
    private fun withRuntime(work: (IRuntimeService) -> Unit) {
        val ready = CountDownLatch(1)
        lateinit var runtime: IRuntimeService
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                runtime = IRuntimeService.Stub.asInterface(binder)
                ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        try { assertTrue(ready.await(10, TimeUnit.SECONDS)); work(runtime) } finally { context.unbindService(connection) }
    }
    private fun state(runtime: IRuntimeService) = WorkspaceRegistry.State.parse(runtime.workspacesJson)
    private fun rows(runtime: IRuntimeService) = JSONArray(runtime.sessionsJson).let { array ->
        List(array.length()) { array.getJSONObject(it) }
    }
    private fun fixture(): Workspace {
        val profile = SessionProfiles(context).all().first { it.runtime == SessionRuntime.NATIVE }
        return Workspace(UUID.randomUUID().toString(), "Startup ${UUID.randomUUID().toString().take(8)}", false,
            listOf("First", "Manual", "Last").map { name ->
                WorkspaceLaunch(UUID.randomUUID().toString(), "$name ${UUID.randomUUID().toString().take(8)}", profile.id, context.filesDir.path)
            })
    }

    @Test fun multiSelectionSupportsCancelRecreationAndWholeWorkspaceSave() = withRuntime { runtime ->
        val workspace = fixture()
        assertEquals("", runtime.saveWorkspace(workspace.json().toString()))
        try {
            ActivityScenario.launch(WorkspacesActivity::class.java).use {
                text(workspace.name)
                text(context.getString(R.string.workspace_startup_launches))
                // Wait for the dialog's checkbox rows, which distinguishes them from
                // identically named navigation rows still visible during the transition.
                startupChoice(workspace.launches[0].name)
                startupChoice(workspace.launches[2].name)
                recreateEditor()
                save() // Dialog Save changes only the Workspace draft.
                await("Two configurations are selected in the draft") {
                    node { it.text?.toString() == context.getString(R.string.workspace_startup_count, 2) } != null
                }
                assertEquals(workspace, state(runtime).workspaces.single { it.id == workspace.id })
                text(context.getString(R.string.workspace_startup_launches))
                startupChoice(workspace.launches[0].name)
                text(context.getString(R.string.cancel))
                recreateEditor()
                await("Cancel and recreation retain both selected configurations") {
                    node { it.text?.toString() == context.getString(R.string.workspace_startup_count, 2) } != null
                }
                save()
                await("Whole Workspace Save persists the selection") {
                    state(runtime).workspaces.single { it.id == workspace.id }.launches.count { it.startWithWorkspace } == 2
                }
                val selected = state(runtime).workspaces.single { it.id == workspace.id }.launches.filter { it.startWithWorkspace }
                assertEquals(listOf(workspace.launches[0].id, workspace.launches[2].id), selected.map { it.id })
                assertTrue(rows(runtime).none { state(runtime).memberships[it.getString("id")] == workspace.id })
            }
        } finally { assertEquals("", runtime.deleteWorkspace(workspace.id)) }
    }

    @Test fun openingStartsOnlySelectedConfigurationsAndRecreationAndReentryReuseSessions() = withRuntime { runtime ->
        val before = state(runtime)
        val workspace = fixture().let { original -> original.copy(launches = original.launches.mapIndexed { index, launch ->
            launch.copy(startWithWorkspace = index != 1)
        }) }
        val profile = SessionProfiles(context).find(workspace.launches.first().profileId)
        val owned = mutableSetOf<String>()
        assertEquals("", runtime.saveWorkspace(workspace.json().toString()))
        assertEquals("", runtime.selectWorkspace(workspace.id))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await("Both selected configurations start in their Workspace") {
                    owned += rows(runtime).filter { state(runtime).memberships[it.getString("id")] == workspace.id }.map { it.getString("id") }
                    owned.size == 2
                }
                assertEquals(profile, SessionProfiles(context).find(workspace.launches.first().profileId))
                scenario.recreate()
                tap { it.contentDescription?.toString() == context.getString(R.string.workspace_selector, workspace.name) }
                text(context.getString(R.string.workspace_all))
                await("All sessions filter is selected") { state(runtime).selected == WorkspaceRegistry.ALL }
                tap { it.contentDescription?.toString() == context.getString(R.string.workspace_selector, context.getString(R.string.workspace_all)) }
                text(workspace.name)
                await("Occupied Workspace is selected again") { state(runtime).selected == workspace.id }
                instrumentation.waitForIdleSync()
                val ids = rows(runtime).filter { state(runtime).memberships[it.getString("id")] == workspace.id }.mapTo(mutableSetOf()) { it.getString("id") }
                assertEquals(owned, ids)
                assertEquals(2, ids.size)
                val launches = rows(runtime).filter { it.getString("id") in owned }.map { it.getJSONObject("launchSpec") }
                assertTrue(launches.all { it.getString("cwd") == context.filesDir.path })
            }
        } finally {
            // Recheck ownership so even a failed startup assertion cannot leak test sessions.
            owned += rows(runtime).filter { state(runtime).memberships[it.getString("id")] == workspace.id }.map { it.getString("id") }
            owned.forEach { runtime.terminateSession(it) }
            await("Fixture sessions exit") { rows(runtime).none { it.getString("id") in owned } }
            assertEquals("", runtime.deleteWorkspace(workspace.id))
            assertEquals("", runtime.selectWorkspace(before.selected))
        }
    }
}
