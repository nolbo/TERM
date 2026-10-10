package dev.terminal.app

import android.content.*
import android.os.*
import android.view.KeyEvent
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
class SessionWorkspaceMenuDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun label(id: Int) = context.getString(id)
    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && predicate(node)) return node
            for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        val succeeded = condition()
        val details = if (succeeded) "" else buildString {
            fun visit(node: AccessibilityNodeInfo?) {
                if (node == null || length > 2000) return
                node.text?.let { append("\n${it.toString().take(100)} [selected=${node.isSelected}, checked=${node.isChecked}]") }
                for (index in 0 until node.childCount) visit(node.getChild(index))
            }
            visit(instrumentation.uiAutomation.rootInActiveWindow)
        }
        assertTrue(message + details, succeeded)
    }
    private fun text(value: String) = node { it.text?.toString()?.contains(value) == true }
    private fun target(value: String): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        await("Visible action: $value") {
            result = node { candidate -> candidate.text?.toString()?.contains(value) == true &&
                generateSequence(candidate) { it.parent }.any { it.isClickable || it.className == "android.widget.Button" } }
            while (result != null && !result!!.isClickable && result!!.className != "android.widget.Button") result = result!!.parent
            result != null
        }
        return result!!
    }
    private fun tap(value: String) = assertTrue(target(value).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    private fun selectTab(value: String) {
        await("Session tab is selected: $value") {
            var tab = text(value)
            while (tab != null && !tab.isSelected && !tab.isClickable) tab = tab.parent
            tab?.isSelected == true || tab?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
    }
    private fun description(value: String) {
        var result: AccessibilityNodeInfo? = null
        await("Icon action: $value") { result = node { it.contentDescription?.toString() == value }; result != null }
        while (!result!!.isClickable) result = result!!.parent
        assertTrue(result!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun selected(value: String): Boolean {
        var row = text(value) ?: return false
        while (!row.isClickable && row.parent != null) row = row.parent
        fun checked(node: AccessibilityNodeInfo): Boolean = node.isChecked || node.isSelected ||
            (0 until node.childCount).any { node.getChild(it)?.let(::checked) == true }
        return checked(row)
    }
    private fun state(runtime: IRuntimeService) = WorkspaceRegistry.State.parse(runtime.workspacesJson)
    private fun rows(runtime: IRuntimeService) = JSONArray(runtime.sessionsJson).let { array ->
        List(array.length()) { array.getJSONObject(it) }
    }

    @Test fun tabMenuAndMoveDialogSupportConfirmationCancelRecreationAndTemporaryCreation() {
        val ready = CountDownLatch(1)
        lateinit var runtime: IRuntimeService
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                runtime = IRuntimeService.Stub.asInterface(binder); ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            val before = state(runtime)
            val profiles = SessionProfiles(context)
            val profile = profiles.all().first { it.runtime == SessionRuntime.NATIVE }
            val suffix = UUID.randomUUID().toString().take(8)
            val source = Workspace(UUID.randomUUID().toString(), "Source $suffix", false)
            val destination = Workspace(UUID.randomUUID().toString(), "A workspace with a longer name that wraps over multiple lines $suffix", false)
            val movingTitle = "Session $suffix"
            val owned = mutableSetOf<String>()
            var temporaryId: String? = null
            assertEquals("", runtime.saveWorkspace(source.json().toString()))
            assertEquals("", runtime.saveWorkspace(destination.json().toString()))
            assertEquals("", runtime.selectWorkspace(source.id))
            try {
                listOf("Keeper $suffix", movingTitle).forEach { title ->
                    val launch = WorkspaceLaunch(UUID.randomUUID().toString(), title, profile.id)
                    runtime.createSessionInWorkspace(launch.resolve(profile, profiles::resolve).toJson().toString(),
                        UUID.randomUUID().toString(), source.id, false, 24, 80)
                }
                await("Fixture sessions are running") {
                    owned += rows(runtime).filter { state(runtime).memberships[it.getString("id")] == source.id }.map { it.getString("id") }
                    owned.size == 2
                }
                val movingId = rows(runtime).single { it.getString("id") in owned && it.getString("title") == movingTitle }.getString("id")
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    description(context.getString(R.string.workspace_selector, label(R.string.workspace_unassigned)))
                    tap(source.name)
                    await("The source Workspace is explicitly selected") { state(runtime).selected == source.id }
                    node { it.actionList.any { action -> action.label?.toString() == label(R.string.toolbar_default) } }?.let { handle ->
                        assertTrue(handle.performAction(handle.actionList.first { it.label?.toString() == label(R.string.toolbar_default) }.id))
                    }
                    var collapsedTab: AccessibilityNodeInfo? = null
                    await("Collapsed tab long-press action") {
                        collapsedTab = node { (it.text?.contains(movingTitle) == true || it.text?.contains("Keeper $suffix") == true) &&
                            generateSequence(it) { parent -> parent.parent }.any { parent -> parent.isLongClickable } }
                        while (collapsedTab != null && !collapsedTab!!.isLongClickable) collapsedTab = collapsedTab!!.parent
                        collapsedTab != null
                    }
                    assertTrue(collapsedTab!!.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
                    await("Long press opens the same action menu") { text(label(R.string.rename_session_action)) != null && text(label(R.string.workspace_move)) != null }
                    assertEquals(source.id, state(runtime).memberships[movingId])
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    await("Long-press menu closes") { text(label(R.string.workspace_move)) == null }
                    var handle: AccessibilityNodeInfo? = null
                    await("Toolbar expansion action") { handle = node { it.actionList.any { action -> action.label?.toString() == label(R.string.toolbar_expand) } }; handle != null }
                    assertTrue(handle!!.performAction(handle!!.actionList.first { it.label?.toString() == label(R.string.toolbar_expand) }.id))
                    selectTab(movingTitle)
                    fun menu() = description(context.getString(R.string.workspace_session_menu, movingTitle))
                    menu()
                    await("A menu exposes both actions") { text(label(R.string.rename_session_action)) != null && text(label(R.string.workspace_move)) != null }
                    assertNull("Opening the menu does not open a modal", text(label(R.string.cancel)))
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    await("Back dismisses the menu") { text(label(R.string.workspace_move)) == null }
                    assertEquals(source.id, state(runtime).memberships[movingId])
                    menu(); tap(label(R.string.rename_session_action))
                    await("Rename is opened only after choosing its action") { node { it.isEditable } != null }
                    tap(label(R.string.cancel))
                    fun moveDialog() {
                        menu(); tap(label(R.string.workspace_move))
                        await("Move dialog has finished opening") {
                            node { it.text?.toString() == label(R.string.workspace_move_action) } != null && text(label(R.string.cancel)) != null
                        }
                    }
                    moveDialog()
                    await("Current workspace is the initial radio selection") { selected(source.name) }
                    assertFalse(target(label(R.string.workspace_move_action)).isEnabled)
                    tap(destination.name)
                    assertEquals("Selection is a draft", source.id, state(runtime).memberships[movingId])
                    tap(label(R.string.cancel))
                    assertEquals(source.id, state(runtime).memberships[movingId])
                    moveDialog(); tap(destination.name)
                    scenario.recreate()
                    await("Move selection survives Activity recreation") { selected(destination.name) }
                    tap(label(R.string.workspace_create_temporary))
                    await("Creation replaces the move dialog") { node { it.isEditable } != null }
                    assertNull("Creation does not stack dialogs", text(label(R.string.workspace_move_action)))
                    tap(label(R.string.cancel))
                    await("Creation cancellation restores the move draft") { selected(destination.name) }
                    tap(label(R.string.workspace_move_action))
                    await("Confirmation applies membership without terminating sessions") { state(runtime).memberships[movingId] == destination.id }
                    assertTrue(rows(runtime).map { it.getString("id") }.containsAll(owned))

                    description(context.getString(R.string.workspace_selector, source.name)); tap(destination.name)
                    await("Destination filter") { state(runtime).selected == destination.id }
                    selectTab(movingTitle); moveDialog()
                    tap(label(R.string.workspace_unassigned)); tap(label(R.string.workspace_move_action))
                    await("Unassigned is a normal destination") { movingId !in state(runtime).memberships }
                    description(context.getString(R.string.workspace_selector, destination.name)); tap(label(R.string.workspace_unassigned))
                    await("Unassigned filter") { state(runtime).selected == WorkspaceRegistry.UNASSIGNED }
                    selectTab(movingTitle); moveDialog()
                    assertTrue(selected(label(R.string.workspace_unassigned)))
                    tap(label(R.string.workspace_create_temporary))
                    var input: AccessibilityNodeInfo? = null
                    await("Temporary workspace name field") { input = node { it.isEditable }; input != null }
                    val temporaryName = "Temporary $suffix"
                    assertTrue(input!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, temporaryName)
                    }))
                    await("A valid temporary name enables saving") { target(label(R.string.save)).isEnabled }
                    tap(label(R.string.save))
                    await("Explicit creation moves the session to the new group") {
                        temporaryId = state(runtime).workspaces.firstOrNull { it.name == temporaryName && it.temporary }?.id
                        temporaryId != null && state(runtime).memberships[movingId] == temporaryId
                    }
                    assertTrue(rows(runtime).map { it.getString("id") }.containsAll(owned))
                }
            } finally {
                owned += rows(runtime).filter { it.getString("title") == movingTitle || it.getString("title") == "Keeper $suffix" }.map { it.getString("id") }
                owned.forEach { runtime.terminateSession(it) }
                await("Fixture sessions exit") { rows(runtime).none { it.getString("id") in owned } }
                listOfNotNull(source.id, destination.id, temporaryId).forEach { assertEquals("", runtime.deleteWorkspace(it)) }
                assertEquals("", runtime.selectWorkspace(before.selected))
            }
        } finally { context.unbindService(connection) }
    }
}
