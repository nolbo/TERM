package dev.terminal.app

import android.content.*
import android.os.*
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.workspace.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WorkspaceLaunchTitleDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(message, condition())
    }
    private fun rows(runtime: IRuntimeService) = JSONArray(runtime.sessionsJson).let { array ->
        List(array.length()) { array.getJSONObject(it) }
    }
    private fun visibleText(value: String): Boolean {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.isVisibleToUser && node.text?.contains(value) == true) return true
            return (0 until node.childCount).any { visit(node.getChild(it)) }
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun tap(predicate: (AccessibilityNodeInfo) -> Boolean) {
        var target: AccessibilityNodeInfo? = null
        await("Workspace entry is ready") {
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.clearCache()
            fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.isVisibleToUser && predicate(node)) return node
                for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
                return null
            }
            target = visit(instrumentation.uiAutomation.rootInActiveWindow)
            while (target != null && !target!!.isClickable) target = target!!.parent
            target?.isEnabled == true
        }
        assertTrue(target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test fun automaticAndManualLaunchesHaveCustomTitlesThatSurviveShellChangesAndRecreation() {
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
        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            val before = WorkspaceRegistry.State.parse(runtime.workspacesJson)
            val profiles = SessionProfiles(context)
            val profile = profiles.all().first { it.runtime == SessionRuntime.NATIVE }
            val suffix = UUID.randomUUID().toString().take(8)
            val automatic = WorkspaceLaunch(UUID.randomUUID().toString(), "Auto $suffix", profile.id, startWithWorkspace = true)
            val manual = WorkspaceLaunch(UUID.randomUUID().toString(), "Manual $suffix", profile.id)
            val workspace = Workspace(UUID.randomUUID().toString(), "Titles $suffix", false, listOf(automatic, manual))
            val owned = mutableSetOf<String>()
            fun members() = rows(runtime).filter {
                WorkspaceRegistry.State.parse(runtime.workspacesJson).memberships[it.getString("id")] == workspace.id
            }.also { members -> owned += members.map { it.getString("id") } }
            assertEquals("", runtime.saveWorkspace(workspace.json().toString()))
            assertEquals("", runtime.selectWorkspace(WorkspaceRegistry.UNASSIGNED))
            try {
                ActivityScenario.launch(MainActivity::class.java).use {
                    tap { it.contentDescription?.toString() == context.getString(R.string.workspace_selector,
                        context.getString(R.string.workspace_unassigned)) }
                    tap { it.text?.toString() == workspace.name }
                    await("Automatic launch starts with its custom name") {
                        members().singleOrNull()?.let { it.optString("title") == automatic.name && it.optBoolean("explicitTitle") } == true
                    }
                    await("Automatic custom name is displayed") { visibleText(automatic.name) }
                }
                val intent = Intent(context, MainActivity::class.java)
                    .putExtra("workspace-id", workspace.id)
                    .putExtra("workspace-launch", manual.resolve(profile, profiles::resolve).toJson().toString())
                ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                    await("Manual launch starts with its custom name") {
                        members().size == 2 && members().any { it.optString("title") == manual.name && it.optBoolean("explicitTitle") }
                    }
                    val session = members().single { it.getString("title") == manual.name }
                    val id = session.getString("id")
                    assertEquals(profile.name, session.getJSONObject("launchSpec").getString("name"))
                    await("Manual custom name is displayed") { visibleText(manual.name) }
                    runtime.writeSessionInput(id, "printf '\\033]0;Shell title fixture\\007'\n".toByteArray())
                    await("Shell title appears as the subtitle") { visibleText("Shell title fixture") }
                    assertTrue(visibleText(manual.name))
                    scenario.recreate()
                    await("Assigned title survives Activity recreation") { visibleText(manual.name) }
                    runtime.renameSession(id, "Renamed $suffix")
                    await("A later user rename takes precedence") {
                        members().any { it.getString("id") == id && it.optString("title") == "Renamed $suffix" } && visibleText("Renamed $suffix")
                    }
                    assertEquals(profile, profiles.find(profile.id))
                }
            } finally {
                members()
                owned.forEach { runtime.terminateSession(it) }
                await("Fixture sessions exit") { rows(runtime).none { it.getString("id") in owned } }
                assertEquals("", runtime.deleteWorkspace(workspace.id))
                assertEquals("", runtime.selectWorkspace(before.selected))
            }
        } finally { context.unbindService(connection) }
    }
}
