package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Rect
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.*
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import dev.terminal.runtime.workspace.Workspace
import dev.terminal.runtime.workspace.WorkspaceLaunch
import dev.terminal.runtime.workspace.WorkspaceRegistry
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ToolbarUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun label(id: Int) = context.getString(id)
    private fun settle() { SystemClock.sleep(1500); instrumentation.waitForIdleSync() }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && predicate(node)) return node
            for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun waitForNode(message: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            find(predicate)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Missing $message; " + dump())
    }
    private fun text(value: String) = waitForNode("text: $value") { it.text?.toString() == value }
    private fun tabText(value: String) = waitForNode("tab text: $value") { node ->
        if (node.text?.toString()?.lineSequence()?.any { it == value } != true) false else {
            var parent = node.parent
            var clickable = false
            while (parent != null) {
                if (parent.isClickable || parent.isSelected) { clickable = true; break }
                parent = parent.parent
            }
            clickable
        }
    }
    private fun description(value: String) = waitForNode("description: $value") { it.contentDescription?.toString() == value }
    private fun dump(): String {
        val values = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.text != null || node.contentDescription != null) values += "[${node.text}|${node.contentDescription}|${node.isVisibleToUser}]"
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(instrumentation.uiAutomation.rootInActiveWindow)
        return values.joinToString().take(4000)
    }
    private fun click(node: AccessibilityNodeInfo, pressMillis: Long = 0, settleAfter: Boolean = true) {
        val rect = bounds(node)
        tap(rect.exactCenterX(), rect.exactCenterY(), pressMillis, settleAfter)
    }
    private fun tap(x: Float, y: Float, pressMillis: Long = 0, settleAfter: Boolean = true) {
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN && pressMillis > 0) SystemClock.sleep(pressMillis)
        }
        if (settleAfter) settle()
    }
    private fun drag(x: Float, y: Float, dx: Float, dy: Float, cancel: Boolean = false, longPress: Boolean = false, holdAtEnd: Long = 0, duringDrag: (() -> Unit)? = null) {
        val start = SystemClock.uptimeMillis()
        for (i in 0..20) {
            val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; 20 -> if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x + dx * i / 20, y + dy * i / 20, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            if (i == 0 && longPress) SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 150)
            if (i == 19 && holdAtEnd > 0) SystemClock.sleep(holdAtEnd)
            if (i == 10 && duringDrag != null) {
                SystemClock.sleep(250)
                duringDrag()
            }
            SystemClock.sleep(20)
        }
        settle()
    }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also { node.getBoundsInScreen(it) }
    private fun handle() = description(label(R.string.toolbar_handle))

    private fun swipe(x: Float, y: Float, dx: Float) {
        val start = SystemClock.uptimeMillis()
        for (i in 0..10) {
            val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; 10 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x + dx * i / 10, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, false)) } finally { event.recycle() }
            SystemClock.sleep(10)
        }
        settle()
    }

    private fun assertNoHandleHover(node: AccessibilityNodeInfo) {
        val rect = bounds(node)
        val density = context.resources.displayMetrics.density
        // Include the whole hit area and the space where a delayed hover tooltip can appear.
        val sample = Rect(rect.left, maxOf(0, rect.top - (80 * density).toInt()), rect.right, rect.bottom)
        var mouseX = rect.exactCenterX() + 14 * density
        var mouseY = rect.exactCenterY()
        fun pixels(): IntArray {
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            return IntArray(sample.width() * sample.height()).also { pixels ->
                bitmap.getPixels(pixels, 0, sample.width(), sample.left, sample.top, sample.width(), sample.height())
                bitmap.recycle()
                // Ignore only the pointer sprite, which is unrelated to the handle indication.
                for (y in sample.top until sample.bottom) for (x in sample.left until sample.right) {
                    if (x >= mouseX - 4 * density && x <= mouseX + 28 * density &&
                        y >= mouseY - 4 * density && y <= mouseY + 36 * density) {
                        pixels[(y - sample.top) * sample.width() + x - sample.left] = 0
                    }
                }
            }
        }
        fun hover(action: Int, y: Float) {
            val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
            val coordinates = MotionEvent.PointerCoords().apply { x = mouseX; this.y = y }
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, action, 1, arrayOf(properties), arrayOf(coordinates),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        for ((x, y) in listOf(
            rect.exactCenterX() + 14 * density to rect.exactCenterY(),
            rect.left + 2 * density to rect.top + 2 * density,
            rect.right - 2 * density to rect.exactCenterY(),
        )) {
            mouseX = x
            mouseY = y
            val before = pixels()
            hover(MotionEvent.ACTION_HOVER_ENTER, y)
            try {
                hover(MotionEvent.ACTION_HOVER_MOVE, y)
                for (delay in listOf(80L, 270L, 750L)) {
                    SystemClock.sleep(delay)
                    instrumentation.waitForIdleSync()
                    assertArrayEquals("Hover must not change the handle, hit area, or show a tooltip", before, pixels())
                }
            } finally { hover(MotionEvent.ACTION_HOVER_EXIT, y) }
            SystemClock.sleep(400)
        }

    }
    private fun action(label: String) {
        val node = handle()
        var current: AccessibilityNodeInfo? = node
        var performed = false
        while (current != null && !performed) {
            val action = current.actionList.firstOrNull { it.label?.toString() == label }
            if (action != null) performed = current.performAction(action.id)
            current = current.parent
        }
        assertTrue("Missing action $label: " + dump(), performed)
        settle()
    }

    @Test fun expandedEmptyWorkspacesHideCloseAllAndKeepTemporaryDeletion() {
        val permanent = Workspace(UUID.randomUUID().toString(), "Empty permanent", false)
        val temporary = Workspace(UUID.randomUUID().toString(), "Empty temporary", true)
        val state = mutableStateOf(WorkspaceRegistry.State(listOf(permanent, temporary), selected = permanent.id))
        val tabs = mutableStateOf(emptyList<SessionUi>())
        val expansion = mutableStateOf(1f)
        var deletedWorkspace: String? = null
        var closeClicks = 0
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        ToolbarSessionsTool(sessions = tabs.value, activeSessionId = "fixture", active = true,
                            terminatingSessionIds = emptySet(), profiles = emptyList(), onSelectProfile = {},
                            onAddProfile = {}, profilePickerVisible = false, onSelect = {}, onRename = {},
                            onTerminate = {}, onNew = {}, onDismissProfilePicker = {}, onSettings = {},
                            expansionProgress = expansion.value, workspaceState = state.value, workspaceSessionStateKnown = true,
                            onCloseWorkspaceSessions = { closeClicks++ },
                            onDeleteEmptyTemporaryWorkspace = { deletedWorkspace = it })
                    }
                } }
            } }
            fun assertNoClose(name: String) {
                assertNull("Empty expanded filters hide close all", find { it.contentDescription?.toString() ==
                    context.getString(R.string.workspace_close_sessions_description, name) })
            }
            settle()
            text(permanent.name)
            assertNoClose(permanent.name)
            val runDescription = label(R.string.workspace_run_default_sessions)
            description(runDescription)
            scenario.onActivity { expansion.value = 0f }
            settle()
            description(runDescription)
            scenario.onActivity {
                expansion.value = 1f
                state.value = state.value.copy(selected = WorkspaceRegistry.UNASSIGNED)
            }
            settle()
            description(context.getString(R.string.workspace_selector, label(R.string.workspace_unassigned)))
            assertNull("Unassigned filters never display a header name", find { it.text?.toString() == label(R.string.workspace_unassigned) })
            assertNoClose(label(R.string.workspace_unassigned))
            assertNull("Unassigned filters have no Workspace startup action", find { it.contentDescription?.toString() == runDescription })
            scenario.onActivity { state.value = state.value.copy(selected = temporary.id) }
            settle()
            assertNoClose(temporary.name)
            assertNull("Temporary Workspaces have no startup action", find { it.contentDescription?.toString() == runDescription })
            val deleteDescription = context.getString(R.string.workspace_delete_temporary_description, temporary.name)
            click(description(deleteDescription))
            settle()
            assertEquals(temporary.id, deletedWorkspace)
            assertEquals(0, closeClicks)
            scenario.onActivity { state.value = state.value.copy(memberships = mapOf("workspace-launch:pending" to temporary.id)) }
            settle()
            assertNoClose(temporary.name)
            assertNull("Pending launches cannot offer deletion", find { it.contentDescription?.toString() == deleteDescription })
            scenario.onActivity {
                tabs.value = listOf(SessionUi("fixture", "NATIVE", "Existing shell", "RUNNING"))
                state.value = state.value.copy(memberships = mapOf("fixture" to temporary.id))
            }
            settle()
            assertNull(find { it.contentDescription?.toString() == deleteDescription })
            click(description(context.getString(R.string.workspace_close_sessions_description, temporary.name)))
            settle()
            assertEquals(1, closeClicks)
        }
    }

    @Test fun workspaceMenuStaysAboveItsButtonInCompactAndExpandedToolbars() {
        val workspace = Workspace(UUID.randomUUID().toString(), "Anchor workspace", false)
        val state = mutableStateOf(WorkspaceRegistry.State(listOf(workspace), selected = WorkspaceRegistry.UNASSIGNED))
        val tabs = mutableStateOf(emptyList<SessionUi>())
        val expansion = mutableFloatStateOf(0f)
        val density = context.resources.displayMetrics.density
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Box(Modifier.align(androidx.compose.ui.Alignment.BottomCenter).fillMaxWidth().height(224.dp)) {
                            ToolbarSessionsTool(sessions = tabs.value, activeSessionId = "fixture", active = true,
                                terminatingSessionIds = emptySet(), profiles = emptyList(), onSelectProfile = {},
                                onAddProfile = {}, profilePickerVisible = false, onSelect = {}, onRename = {},
                                onTerminate = {}, onNew = {}, onDismissProfilePicker = {}, onSettings = {},
                                expansionProgress = expansion.floatValue, workspaceState = state.value,
                                onSelectWorkspace = { state.value = state.value.copy(selected = it) })
                        }
                    }
                } }
            } }
            for ((occupied, progress, selection) in listOf(
                Triple(true, 0f, WorkspaceRegistry.UNASSIGNED),
                Triple(false, 0f, WorkspaceRegistry.UNASSIGNED),
                Triple(false, 0f, workspace.id),
                Triple(false, 1f, WorkspaceRegistry.UNASSIGNED))) {
                scenario.onActivity {
                    tabs.value = if (occupied) listOf(SessionUi("fixture", "NATIVE", "Existing shell", "RUNNING")) else emptyList()
                    expansion.floatValue = progress
                    state.value = state.value.copy(selected = selection)
                }
                settle()
                val name = if (selection == workspace.id) workspace.name else label(R.string.workspace_unassigned)
                val anchor = description(context.getString(R.string.workspace_selector, name))
                val anchorBounds = bounds(anchor)
                if (selection == WorkspaceRegistry.UNASSIGNED) {
                    assertNull("Unassigned headers remain icon-only", find { it.text?.toString() == name })
                }
                click(anchor)
                text(label(R.string.workspace_create_temporary))
                val menuBounds = bounds(requireNotNull(instrumentation.uiAutomation.rootInActiveWindow))
                assertTrue("Menu must touch its button vertically: anchor=$anchorBounds menu=$menuBounds",
                    kotlin.math.abs(menuBounds.bottom - anchorBounds.top) <= 16 * density)
                assertTrue("Menu must align with its button horizontally: anchor=$anchorBounds menu=$menuBounds",
                    kotlin.math.abs(menuBounds.left - anchorBounds.left) <= 16 * density)
                click(text(label(R.string.workspace_unassigned)))
            }
        }
    }

    @Test fun workspaceSelectorShowsItsNameWhenExpandedOrEmptyAndNewSessionGroupsOnlyTheSelectedWorkspaceLaunches() {
        val profile = SessionProfiles(context).builtIns().first().copy(name = "Menu profile")
        val firstLaunch = WorkspaceLaunch(UUID.randomUUID().toString(), "First workspace launch", profile.id)
        val secondLaunch = WorkspaceLaunch(UUID.randomUUID().toString(), "Second workspace launch", profile.id, "/tmp")
        val missing = WorkspaceLaunch(UUID.randomUUID().toString(), "Removed profile launch", "removed-profile")
        val first = Workspace(UUID.randomUUID().toString(), "First workspace", false, listOf(firstLaunch))
        val second = Workspace(UUID.randomUUID().toString(), "Second workspace", false, listOf(secondLaunch, missing), "lan")
        val state = mutableStateOf(WorkspaceRegistry.State(listOf(first, second), selected = first.id))
        val picking = mutableStateOf(false)
        val expansion = mutableFloatStateOf(0f)
        var selectedLaunch: WorkspaceLaunch? = null
        var selectedProfile: dev.terminal.runtime.session.SessionProfile? = null
        var closeWorkspaceClicks = 0
        val fixtureTabs = mutableStateOf(listOf(SessionUi("fixture", "NATIVE", "Existing shell", "RUNNING")))
        val sessionStateKnown = mutableStateOf(true)
        var deletedWorkspace: String? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        ToolbarSessionsTool(fixtureTabs.value,
                            "fixture", true, emptySet(), listOf(profile),
                            { selectedProfile = it; picking.value = false }, {}, picking.value,
                            {}, {}, {}, { picking.value = true }, { picking.value = false }, {},
                            expansionProgress = expansion.floatValue, workspaceState = state.value,
                            onSelectWorkspace = { state.value = state.value.copy(selected = it) },
                            onLaunchWorkspace = { selectedLaunch = it; picking.value = false },
                            onCloseWorkspaceSessions = { closeWorkspaceClicks++ },
                            onDeleteEmptyTemporaryWorkspace = { deletedWorkspace = it },
                            workspaceSessionStateKnown = sessionStateKnown.value)
                    }
                } }
            } }
            settle()
            fun selector(name: String) = description(context.getString(R.string.workspace_selector, name))
            assertNull("The header must not render the workspace name", find { it.text?.toString() == first.name })
            val selectorBounds = bounds(selector(first.name))
            assertNull(find { it.contentDescription?.toString() ==
                context.getString(R.string.workspace_close_sessions_description, first.name) })
            assertTrue("Selector uses a compact icon touch target", selectorBounds.width() <= 52 * context.resources.displayMetrics.density)
            assertTrue("Selector precedes the session tabs", selectorBounds.right <= bounds(tabText("Existing shell")).left)
            scenario.onActivity { expansion.floatValue = 1f }
            settle()
            text(first.name)
            assertTrue("Expanded selector stays on the left", bounds(selector(first.name)).right <= bounds(description(label(R.string.new_session))).left)
            val closeWorkspace = description(context.getString(R.string.workspace_close_sessions_description, first.name))
            val closeBounds = bounds(closeWorkspace)
            assertTrue("Workspace name precedes close all", bounds(selector(first.name)).right <= closeBounds.left)
            assertEquals("Close all immediately precedes the new-session button",
                48f * context.resources.displayMetrics.density,
                bounds(description(label(R.string.new_session))).exactCenterX() - closeBounds.exactCenterX(), 1f)
            click(closeWorkspace)
            assertEquals(1, closeWorkspaceClicks)
            click(selector(first.name))
            assertNull("All sessions must not be offered", find { it.text?.toString() == label(R.string.workspace_all) })
            text(label(R.string.workspace_unassigned))
            assertNull(find { it.text?.toString() in setOf(firstLaunch.name, context.getString(R.string.workspace_run, firstLaunch.name)) })
            click(text(second.name))
            assertEquals(second.id, state.value.selected)
            assertNull("Switching a workspace never launches a session", selectedLaunch)
            text(second.name)
            click(description(label(R.string.new_session)))
            val heading = text(context.getString(R.string.workspace_new_session_group, second.name))
            val launchRow = text(secondLaunch.name)
            val profilesHeading = text(label(R.string.profiles))
            assertTrue(bounds(heading).bottom <= bounds(launchRow).top)
            assertTrue(bounds(launchRow).bottom <= bounds(profilesHeading).top)
            assertNull(find { it.text?.toString() == firstLaunch.name })
            text(label(R.string.workspace_profile_missing))
            click(text(missing.name))
            assertNull("A removed Profile cannot be launched", selectedLaunch)
            click(text(secondLaunch.name))
            assertEquals(secondLaunch, selectedLaunch)
            assertNull(selectedProfile)
            click(selector(second.name))
            click(text(label(R.string.workspace_unassigned)))
            assertEquals(WorkspaceRegistry.UNASSIGNED, state.value.selected)
            click(description(label(R.string.new_session)))
            text(label(R.string.select_profile))
            text(profile.name)
            assertNull(find { it.text?.toString() in setOf(firstLaunch.name, secondLaunch.name, missing.name) })
            click(description(label(R.string.back)))
            val temporary = Workspace(UUID.randomUUID().toString(), "Empty temporary", true)
            val deleteDescription = context.getString(R.string.workspace_delete_temporary_description, temporary.name)
            scenario.onActivity {
                state.value = WorkspaceRegistry.State(listOf(temporary), selected = temporary.id)
                fixtureTabs.value = emptyList()
            }
            settle()
            assertNull(find { it.contentDescription?.toString() ==
                context.getString(R.string.workspace_close_sessions_description, temporary.name) })
            click(description(deleteDescription))
            assertEquals(temporary.id, deletedWorkspace)
            assertEquals(1, closeWorkspaceClicks)
            scenario.onActivity {
                state.value = state.value.copy(memberships = mapOf("workspace-launch:pending" to temporary.id))
            }
            settle()
            assertNull("Pending launches cannot offer deletion", find { it.contentDescription?.toString() == deleteDescription })
            assertNull("Empty Workspaces with pending launches hide close all", find { it.contentDescription?.toString() ==
                context.getString(R.string.workspace_close_sessions_description, temporary.name) })
            scenario.onActivity {
                state.value = state.value.copy(memberships = emptyMap())
                sessionStateKnown.value = false
            }
            settle()
            assertNull("Unknown session state cannot offer deletion", find { it.contentDescription?.toString() == deleteDescription })
            assertNull("Empty Workspaces with unknown state hide close all", find { it.contentDescription?.toString() ==
                context.getString(R.string.workspace_close_sessions_description, temporary.name) })
            scenario.onActivity {
                sessionStateKnown.value = true
                fixtureTabs.value = listOf(SessionUi("fixture", "NATIVE", "Existing shell", "RUNNING"))
                state.value = state.value.copy(memberships = mapOf("fixture" to temporary.id))
            }
            settle()
            assertNull(find { it.contentDescription?.toString() == deleteDescription })
            click(description(context.getString(R.string.workspace_close_sessions_description, temporary.name)))
            assertEquals(2, closeWorkspaceClicks)
            scenario.onActivity {
                fixtureTabs.value = emptyList()
                state.value = WorkspaceRegistry.State(listOf(first), selected = first.id)
            }
            settle()
            assertNull("Permanent Workspaces cannot offer deletion", find { it.contentDescription?.toString() ==
                context.getString(R.string.workspace_delete_temporary_description, first.name) })
            assertNull("Expanded empty permanent Workspaces hide close all", find { it.contentDescription?.toString() ==
                context.getString(R.string.workspace_close_sessions_description, first.name) })
            scenario.onActivity { state.value = state.value.copy(selected = WorkspaceRegistry.UNASSIGNED) }
            settle()
            selector(label(R.string.workspace_unassigned))
            assertNull("Expanded unassigned filters remain icon-only", find { it.text?.toString() == label(R.string.workspace_unassigned) })
            assertNull("Expanded empty unassigned filters hide close all", find { it.contentDescription?.toString() ==
                context.getString(R.string.workspace_close_sessions_description, label(R.string.workspace_unassigned)) })
            scenario.onActivity { state.value = state.value.copy(selected = first.id) }
            scenario.onActivity { expansion.floatValue = 0f }
            settle()
            text(first.name)
            assertNull("Close all remains exclusive to the expanded header", find { it.contentDescription?.toString() ==
                context.getString(R.string.workspace_close_sessions_description, first.name) })
            scenario.onActivity { state.value = state.value.copy(selected = WorkspaceRegistry.UNASSIGNED) }
            settle()
            selector(label(R.string.workspace_unassigned))
            assertNull("Empty unassigned filters remain icon-only", find { it.text?.toString() == label(R.string.workspace_unassigned) })
            description(label(R.string.new_session))
            scenario.onActivity { fixtureTabs.value = listOf(SessionUi("fixture", "NATIVE", "Existing shell", "RUNNING")) }
            settle()
            assertNull("Compact populated filters use only the workspace icon", find { it.text?.toString() == label(R.string.workspace_unassigned) })
            tabText("Existing shell")
        }
    }

    @Test fun newSessionSheetAddsAWorkspaceCategoryAndPreservesTheOrdinaryProfilePath() {
        val profile = SessionProfiles(context).builtIns().first().copy(name = "Sheet profile")
        val launch = WorkspaceLaunch(UUID.randomUUID().toString(), "Sheet workspace launch", profile.id)
        val workspace = mutableStateOf<Workspace?>(Workspace(UUID.randomUUID().toString(), "Sheet workspace", false, listOf(launch)))
        val visible = mutableStateOf(true)
        var selectedLaunch: WorkspaceLaunch? = null
        var selectedProfile: dev.terminal.runtime.session.SessionProfile? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    if (visible.value) NewSessionSheet(listOf(profile),
                        { selectedProfile = it; visible.value = false }, {}, { visible.value = false },
                        workspace = workspace.value, onSelectWorkspaceLaunch = { selectedLaunch = it; visible.value = false })
                } }
            } }
            settle()
            val heading = text(context.getString(R.string.workspace_new_session_group, workspace.value!!.name))
            assertTrue(bounds(heading).bottom <= bounds(text(launch.name)).top)
            assertTrue(bounds(text(launch.name)).bottom <= bounds(text(label(R.string.profiles))).top)
            click(text(launch.name))
            assertEquals(launch, selectedLaunch)
            assertNull(selectedProfile)
            assertFalse(visible.value)
            scenario.onActivity { workspace.value = null; visible.value = true }
            settle()
            assertNull(find { it.text?.toString() == launch.name })
            text(label(R.string.profiles))
            click(text(profile.name))
            assertEquals(profile, selectedProfile)
            assertFalse(visible.value)
        }
    }

    @Test fun sessionToolHasTopActionsVerticalTabsAndKeepsMandatoryNavigation() {
        val selected = mutableStateOf("first")
        var renamed: String? = null
        var terminated: String? = null
        var newClicks = 0
        var settingsClicks = 0
        var informationSession: String? = null
        val sessions = listOf(SessionUi("first", "NATIVE", "First shell", "RUNNING"),
            SessionUi("second", "ROOTLESS", "Second shell", "RUNNING"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    Box(Modifier.fillMaxSize()) {
                        ToolbarSessionsTool(sessions, selected.value, true, emptySet(), emptyList(),
                            {}, {}, false, { selected.value = it }, { renamed = it.id },
                            { terminated = it.id }, { newClicks++ }, {}, { settingsClicks++ },
                            TerminalChromeColors(Color.Black), onInfo = { informationSession = it.id })
                    }
                } }
            } }
            settle()
            assertNull(find { it.text?.toString() == label(R.string.toolbar_sessions) })
            val first = bounds(tabText("First shell"))
            val second = bounds(tabText("Second shell"))
            assertTrue("Tabs form a vertical list", second.top > first.bottom)
            assertEquals(first.left, second.left)
            assertTrue(bounds(description(label(R.string.new_session))).bottom <= first.top)
            assertTrue(bounds(description(label(R.string.settings))).bottom <= first.top)
            click(description(label(R.string.new_session)))
            click(description(label(R.string.settings)))
            assertEquals(1, newClicks)
            assertEquals(1, settingsClicks)
            description(context.getString(R.string.terminate_session_description, "First shell"))
            click(description(context.getString(R.string.terminate_session_description, "Second shell")))
            assertEquals("second", terminated)
            assertEquals("first", selected.value)
            assertNull(find { it.contentDescription?.toString() ==
                context.getString(R.string.workspace_session_menu, "Second shell") })
            click(description(context.getString(R.string.workspace_session_menu, "First shell")))
            assertNull("Opening the menu must not rename the session", renamed)
            click(text(label(R.string.rename_session_action)))
            assertEquals("first", renamed)
            click(description(context.getString(R.string.workspace_session_menu, "First shell")))
            click(text(label(R.string.session_info_title)))
            assertEquals("first", informationSession)
            assertEquals("first", selected.value)
            click(tabText("Second shell"))
            assertEquals("second", selected.value)
            assertNull(find { it.contentDescription?.toString() ==
                context.getString(R.string.workspace_session_menu, "First shell") })
            click(description(context.getString(R.string.workspace_session_menu, "Second shell")))
            click(text(label(R.string.rename_session_action)))
            assertEquals("second", renamed)
            renamed = null
            click(description(context.getString(R.string.terminate_session_description, "Second shell")))
            assertEquals("second", terminated)
            assertNull(find { it.actionList.any { action ->
                action.label?.toString() == context.getString(R.string.workspace_session_menu, "Second shell")
            } })
        }
    }

    @Test fun longPressReordersTabsWithoutSelectingOrRenamingAndCancelRestoresOrder() {
        val tabs = mutableStateOf((0..9).map { SessionUi("tab-$it", "NATIVE", "Shell $it", "RUNNING") })
        var renamed = 0
        var selected = "tab-0"
        var commits = 0
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    Box(Modifier.fillMaxWidth().height(350.dp)) {
                        ToolbarSessionsTool(tabs.value, "tab-0", true, emptySet(), emptyList(),
                            {}, {}, false, { selected = it }, { renamed++ }, {}, {}, {}, {},
                            onReorder = { ids -> tabs.value = orderSessionTabs(tabs.value, ids); commits++ })
                    }
                } }
            } }
            settle()
            val first = bounds(tabText("Shell 0"))
            val third = bounds(tabText("Shell 2"))
            drag(first.exactCenterX(), first.exactCenterY(), 0f,
                third.exactCenterY() - first.exactCenterY(), longPress = true)
            assertEquals(listOf("tab-1", "tab-2", "tab-0"), tabs.value.take(3).map { it.id })
            assertEquals(1, commits)
            assertEquals("tab-0", selected)
            assertEquals(0, renamed)
            val afterMove = tabs.value
            val second = bounds(tabText("Shell 1"))
            drag(second.exactCenterX(), second.exactCenterY(), 0f,
                third.exactCenterY() - first.exactCenterY(), cancel = true, longPress = true)
            assertEquals(afterMove, tabs.value)
            assertEquals(1, commits)
            // Holding near the bottom reveals tabs beyond the initially visible rows.
            val start = bounds(tabText("Shell 1"))
            val density = context.resources.displayMetrics.density
            drag(start.exactCenterX(), start.exactCenterY(), 0f,
                330 * density - start.exactCenterY(), longPress = true, holdAtEnd = 800)
            assertTrue("Drag auto-scrolls to later tabs", tabs.value.indexOfFirst { it.id == "tab-1" } >= 5)
            assertEquals(0, renamed)
        }
    }

    @Test fun liftedSessionTabScalesItsCapsuleWithoutClippingEitherSide() {
        val sessions = (0..3).map { SessionUi("tab-$it", "NATIVE", "Shell $it", "RUNNING") }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    Box(Modifier.fillMaxWidth().height(350.dp)) {
                        ToolbarSessionsTool(sessions, "tab-0", true, emptySet(), emptyList(),
                            {}, {}, false, {}, {}, {}, {}, {}, {}, TerminalChromeColors(Color.Black))
                    }
                } }
            } }
            settle()
            fun row() = bounds(waitForNode("Selected tab") { it.isSelected })
            fun paintedEdges(y: Int): Pair<Int, Int> {
                val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    val container = TerminalChromeColors(Color.Black).container.toArgb()
                    val pixels = (0 until screenshot.width).filter { screenshot.getPixel(it, y) == container }
                    assertTrue("Selected capsule must be visible", pixels.isNotEmpty())
                    return pixels.first() to pixels.last()
                } finally { screenshot.recycle() }
            }
            val resting = row()
            val restingEdges = paintedEdges(resting.centerY())
            val source = bounds(tabText("Shell 0"))
            val target = bounds(tabText("Shell 2"))
            drag(source.exactCenterX(), source.exactCenterY(), 0f,
                target.exactCenterY() - source.exactCenterY(), cancel = true, longPress = true) {
                instrumentation.waitForIdleSync()
                val lifted = row()
                val middle = paintedEdges(lifted.centerY())
                val expectedWidth = (restingEdges.second - restingEdges.first + 1) * 1.02
                assertEquals("Scale the visible capsule, including its left and right edges",
                    expectedWidth, (middle.second - middle.first + 1).toDouble(), 2.0)
                val density = context.resources.displayMetrics.density
                val top = paintedEdges(lifted.top + (3 * density).toInt())
                assertTrue("Both corners remain rounded", top.first > middle.first && top.second < middle.second)
                assertTrue("Neither side is cropped", kotlin.math.abs(
                    (top.first - middle.first) - (middle.second - top.second)) <= 2)
            }
        }
    }

    @Test fun sessionListKeepsManualScrollWhenInteractionOrActiveTabChanges() {
        val selected = mutableStateOf("tab-0")
        val active = mutableStateOf(true)
        val showTabs = mutableStateOf(true)
        val sessions = (0..14).map { SessionUi("tab-$it", "NATIVE", "Shell $it", "RUNNING") }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    Box(Modifier.fillMaxWidth().height(350.dp)) {
                        ToolbarSessionsTool(sessions, selected.value, active.value, emptySet(), emptyList(),
                            {}, {}, false, { selected.value = it }, {}, {}, {}, {}, {},
                            expansionProgress = if (showTabs.value) 1f else 0f)
                    }
                } }
            } }
            settle()
            val markerBefore = bounds(tabText("Shell 4"))
            val density = context.resources.displayMetrics.density
            drag(markerBefore.exactCenterX(), markerBefore.exactCenterY(), 0f, -156 * density)
            val scrolled = bounds(tabText("Shell 4"))
            assertTrue("Manual scroll moves away from the active tab", scrolled.top < markerBefore.top)
            scenario.onActivity { active.value = false }
            settle()
            scenario.onActivity { active.value = true }
            settle()
            assertEquals("Becoming interactive must preserve the position", scrolled, bounds(tabText("Shell 4")))
            scenario.onActivity { selected.value = "tab-7" }
            settle()
            assertEquals("Changing the active tab must preserve the position", scrolled, bounds(tabText("Shell 4")))
            scenario.onActivity { showTabs.value = false }
            settle()
            tabText("Shell 7")
            scenario.onActivity { showTabs.value = true }
            settle()
            assertEquals("Collapsing the list must preserve the position", scrolled, bounds(tabText("Shell 4")))
            click(tabText("Shell 4"))
            assertEquals("tab-4", selected.value)
            assertEquals("Selecting a visible tab must preserve the position", scrolled, bounds(tabText("Shell 4")))
        }
    }

    @Test fun settingsCannotRemoveSessionTool() {
        val prefs = context.getSharedPreferences("toolbar-mandatory-test", 0)
        prefs.edit().clear().commit()
        var opened: String? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    DroidTheme { TerminalHapticFeedbackProvider { ToolbarSettings(prefs, {}) { opened = it } } }
                } }
                settle()
                text(label(R.string.toolbar_sessions))
                click(text(label(R.string.toolbar_sessions)))
                assertEquals("toolbar-sessions", opened)
                click(description(label(R.string.toolbar_edit)))
                description(context.getString(R.string.toolbar_move, label(R.string.toolbar_sessions)))
                assertNull(find { it.contentDescription?.toString() ==
                    context.getString(R.string.toolbar_remove, label(R.string.toolbar_sessions)) })
                click(description(context.getString(R.string.toolbar_remove, label(R.string.settings_hotkeys_title))))
                assertEquals(listOf(SESSIONS_TOOL), readToolbarConfig(prefs).tools)
            }
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun sessionSettingsDefaultToConfirmationAndPersistAcrossRecreation() {
        val prefs = context.getSharedPreferences(SettingsDetailActivity.TERMINAL_PREFERENCES, 0)
        val previous = if (prefs.contains(KEY_SKIP_SESSION_CLOSE_CONFIRMATION))
            prefs.getBoolean(KEY_SKIP_SESSION_CLOSE_CONFIRMATION, false) else null
        prefs.edit().remove(KEY_SKIP_SESSION_CLOSE_CONFIRMATION).commit()
        try {
            val intent = Intent(context, SettingsDetailActivity::class.java)
                .putExtra(SettingsDetailActivity.EXTRA_CATEGORY, "toolbar-sessions")
            ActivityScenario.launch<SettingsDetailActivity>(intent).use { scenario ->
                settle()
                text(label(R.string.session_skip_close_confirmation_summary))
                assertFalse(prefs.getBoolean(KEY_SKIP_SESSION_CLOSE_CONFIRMATION, false))
                click(text(label(R.string.session_skip_close_confirmation)))
                assertTrue(prefs.getBoolean(KEY_SKIP_SESSION_CLOSE_CONFIRMATION, false))
                scenario.recreate()
                settle()
                val toggle = waitForNode("enabled session close switch") { it.isCheckable && it.isChecked }
                // Tapping the switch and the rest of its row must each toggle exactly once.
                click(toggle)
                assertFalse(prefs.getBoolean(KEY_SKIP_SESSION_CLOSE_CONFIRMATION, true))
                scenario.recreate()
                settle()
                waitForNode("disabled session close switch") { it.isCheckable && !it.isChecked }
            }
        } finally {
            val editor = prefs.edit()
            if (previous == null) editor.remove(KEY_SKIP_SESSION_CLOSE_CONFIRMATION)
            else editor.putBoolean(KEY_SKIP_SESSION_CLOSE_CONFIRMATION, previous)
            editor.commit()
        }
    }

    @Test fun sessionClosePreferenceControlsConfirmationAndKeepsOtherSessions() {
        val prefs = context.getSharedPreferences(SettingsDetailActivity.TERMINAL_PREFERENCES, 0)
        val previous = if (prefs.contains(KEY_SKIP_SESSION_CLOSE_CONFIRMATION))
            prefs.getBoolean(KEY_SKIP_SESSION_CLOSE_CONFIRMATION, false) else null
        prefs.edit().remove(KEY_SKIP_SESSION_CLOSE_CONFIRMATION).commit()
        val connected = LinkedBlockingQueue<IRuntimeService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                connected.offer(IRuntimeService.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        var bound = false
        var service: IRuntimeService? = null
        val owned = mutableSetOf<String>()
        fun await(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertTrue(message, condition())
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                bound = context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE)
                assertTrue(bound)
                val runtime = requireNotNull(connected.poll(10, TimeUnit.SECONDS))
                service = runtime
                fun running(): Set<String> {
                    val sessions = JSONArray(runtime.sessionsJson)
                    return (0 until sessions.length()).map { sessions.getJSONObject(it) }
                        .filter { it.getString("state") == "RUNNING" }.map { it.getString("id") }.toSet()
                }

                fun create(title: String): String {
                    val before = running()
                    runtime.createSession("NATIVE", "/system/bin/sh", "", 24, 80)
                    await("Test session created") { (running() - before).size == 1 }
                    val id = (running() - before).single()
                    owned += id
                    runtime.renameSession(id, title)
                    return id
                }
                val firstTitle = "Close confirmation first"
                val secondTitle = "Close confirmation second"
                val first = create(firstTitle)
                val second = create(secondTitle)
                settle()
                action(label(R.string.toolbar_expand))
                fun close(title: String) = click(description(context.getString(R.string.terminate_session_description, title)))
                close(firstTitle)
                text(label(R.string.terminate_session_title))
                assertTrue(running().containsAll(owned))
                click(text(label(R.string.cancel)))
                assertTrue("Cancel keeps both sessions running", running().containsAll(owned))
                prefs.edit().putBoolean(KEY_SKIP_SESSION_CLOSE_CONFIRMATION, true).commit()
                close(firstTitle)
                await("Skip confirmation terminates the selected session") { first !in running() }
                assertNull(find { it.text?.toString() == label(R.string.terminate_session_title) })
                assertTrue("Closing one tab keeps its sibling running", second in running())
                prefs.edit().putBoolean(KEY_SKIP_SESSION_CLOSE_CONFIRMATION, false).commit()
                close(secondTitle)
                text(label(R.string.terminate_session_title))
                assertTrue("Turning the option off restores confirmation", second in running())
                click(text(label(R.string.terminate)))
                await("Confirmed close terminates the session") { second !in running() }
            }
        } finally {
            owned.forEach { id -> runCatching { service?.terminateSession(id) } }
            if (bound) context.unbindService(connection)
            val editor = prefs.edit()
            if (previous == null) editor.remove(KEY_SKIP_SESSION_CLOSE_CONFIRMATION)
            else editor.putBoolean(KEY_SKIP_SESSION_CLOSE_CONFIRMATION, previous)
            editor.commit()
        }
    }

    @Test fun defaultSessionTabsHandOffEdgeSwipesFromSelectedAndInactiveTabs() {
        assertDefaultSessionTabEdgeSwipes(LayoutDirection.Ltr)
    }

    @Test fun defaultSessionTabsHandOffEdgeSwipesInRtl() {
        assertDefaultSessionTabEdgeSwipes(LayoutDirection.Rtl)
    }

    @Test fun defaultSessionTabsPrioritizeListScrollingAndRespectDisabledPaging() {
        val navigation = mutableStateOf(true)
        val sessions = (0..3).map { SessionUi("scroll-$it", "NATIVE", "Scrollable session $it with a long title", "RUNNING") }
        var currentTool: String? = null
        var tabActions = 0
        var headerScroll: ScrollState? = null
        var positionTabs: ((Boolean) -> Unit)? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    val scroll = rememberScrollState()
                    val scope = rememberCoroutineScope()
                    SideEffect {
                        headerScroll = scroll
                        positionTabs = { forward -> scope.launch { scroll.scrollTo(if (forward) scroll.maxValue else 0) } }
                    }
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.weight(1f))
                        TerminalToolbar(ToolbarConfig(tools = listOf(SESSIONS_TOOL, "next")),
                            ToolbarSheetMode.Default, {}, 400.dp, TerminalChromeColors(Color.Black),
                            pageNavigationEnabled = navigation.value) { id, active ->
                            SideEffect { if (active) currentTool = id }
                            if (id == SESSIONS_TOOL) ToolbarSessionsTool(sessions, sessions.first().id, active,
                                emptySet(), emptyList(), {}, {}, false, { tabActions++ },
                                { tabActions++ }, { tabActions++ }, {}, {}, {}, headerTabScroll = scroll)
                            else Text("next tool", Modifier.fillMaxSize())
                        }
                    }
                } }
            } }
            settle()
            val density = context.resources.displayMetrics.density
            val before = bounds(tabText(sessions.first().title))
            swipe(before.right - 16 * density, before.exactCenterY(), -96 * density)
            val first = find { it.text?.toString() == sessions.first().title }
            assertTrue("The inner list must scroll before changing tools", first == null || bounds(first).width() < before.width())
            assertEquals(SESSIONS_TOOL, currentTool)

            val page = bounds(handle())
            scenario.onActivity { requireNotNull(positionTabs)(true) }
            settle()
            assertEquals(requireNotNull(headerScroll).maxValue, requireNotNull(headerScroll).value)
            assertEquals("Positioning tabs must keep the sessions tool selected", SESSIONS_TOOL, currentTool)
            scenario.onActivity { navigation.value = false }
            settle()
            val edge = bounds(tabText(sessions.last().title))
            val x = edge.right - 24 * density
            swipe(x, edge.exactCenterY(), -(x - page.left - 8 * density))
            assertEquals("Disabling page navigation must also block edge swipes", SESSIONS_TOOL, currentTool)
            assertEquals(0, tabActions)
        }
    }

    @Test fun defaultSessionTabsReachTheEndBeforeANewGestureChangesTools() {
        val sessions = (0..5).map {
            SessionUi("end-scroll-$it", "NATIVE", "End scroll session $it with a long title", "RUNNING")
        }
        val selected = mutableStateOf(sessions.first().id)
        val pair = TerminalPaneState(sessions[0].id, sessions[1].id)
        var currentTool: String? = null
        var tabActions = 0
        var headerScroll: ScrollState? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    val scroll = rememberScrollState()
                    SideEffect { headerScroll = scroll }
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.weight(1f))
                        TerminalToolbar(ToolbarConfig(tools = listOf(SESSIONS_TOOL, "next")),
                            ToolbarSheetMode.Default, {}, 400.dp, TerminalChromeColors(Color.Black)) { id, active ->
                            SideEffect { if (active) currentTool = id }
                            if (id == SESSIONS_TOOL) ToolbarSessionsTool(sessions, selected.value, active,
                                emptySet(), emptyList(), {}, {}, false,
                                { selected.value = it; tabActions++ }, { tabActions++ }, { tabActions++ }, {}, {}, {},
                                headerTabScroll = scroll, paneState = pair)
                            else Text("next tool", Modifier.fillMaxSize())
                        }
                    }
                } }
            } }
            settle()
            var scroller: AccessibilityNodeInfo? = tabText(sessions.first().title)
            while (scroller != null && !scroller.isScrollable) scroller = scroller.parent
            val viewport = bounds(requireNotNull(scroller))
            val page = bounds(handle())
            val density = context.resources.displayMetrics.density
            val x = viewport.right - 12 * density
            val y = viewport.exactCenterY()
            val scroll = requireNotNull(headerScroll)
            val beforeDrag = scroll.value
            val firstBounds = bounds(tabText(sessions.first().title))
            // Start the regression at a real, non-edge position without programmatic scrolling.
            swipe(x, y, -48 * density)
            assertTrue("The first drag moves the list into its middle: $beforeDrag -> ${scroll.value}/${scroll.maxValue}, viewport=$viewport first=$firstBounds start=($x,$y) tool=$currentTool",
                scroll.value > 0 && scroll.value < scroll.maxValue)
            assertEquals("A middle drag keeps the sessions tool selected", SESSIONS_TOOL, currentTool)

            var gestures = 0
            while (scroll.value < scroll.maxValue && gestures < 8) {
                val before = scroll.value
                swipe(x, y, -(viewport.width() - 24 * density))
                val currentViewport = find { it.isScrollable && bounds(it).height() == viewport.height() }?.let(::bounds)
                val lastVisible = find { it.text?.toString() == sessions.last().title }?.let(::bounds)
                assertTrue("Every swipe advances the tab list: gesture=$gestures $before -> ${scroll.value}/${scroll.maxValue} tool=$currentTool viewport=$viewport current=$currentViewport last=$lastVisible actions=$tabActions",
                    scroll.value > before)
                assertEquals("Reaching the edge during a list gesture must not change tools", SESSIONS_TOOL, currentTool)
                gestures++
            }
            assertTrue("At least one swipe starts in the middle", gestures > 0)
            assertEquals("Actual finger swipes reach the list's exact end", scroll.maxValue, scroll.value)
            val last = bounds(tabText(sessions.last().title))
            assertTrue("The final tab is visible inside the tab viewport",
                last.width() > 0 && last.left >= viewport.left && last.right <= viewport.right)
            assertEquals("Scrolling preserves the selected split session", sessions.first().id, selected.value)
            assertEquals("Scrolling does not invoke tab actions", 0, tabActions)

            // Only a fresh gesture that starts at the edge may move to the next tool.
            val edgeX = last.right - 24 * density
            swipe(edgeX, last.exactCenterY(), -(edgeX - page.left - 8 * density))
            waitForNode("The next tool snaps after a fresh edge gesture") {
                it.text?.toString() == "next tool" && bounds(it).let { area ->
                    area.left == page.left && area.right == page.right
                }
            }
            assertEquals("The new edge gesture keeps existing tool navigation", "next", currentTool)
            assertEquals("Tool navigation preserves the selected session", sessions.first().id, selected.value)
            assertEquals("Tool navigation does not invoke tab actions", 0, tabActions)
        }
    }

    private fun assertDefaultSessionTabEdgeSwipes(layoutDirection: LayoutDirection) {
        val density = context.resources.displayMetrics.density
        val sessions = (0..2).map { SessionUi("edge-$it", "NATIVE", "Edge session $it with a long title", "RUNNING") }
        for (forward in listOf(true, false)) for (selectedEdge in listOf(false, true)) {
            val edge = if (forward) sessions.last() else sessions.first()
            val selected = if (selectedEdge) edge.id else if (forward) sessions.first().id else sessions.last().id
            val starts = if (selectedEdge) listOf("title", "icon", "padding", "close") else listOf("title", "icon", "padding")
            for (start in starts) {
                val config = mutableStateOf(ToolbarConfig(tools = listOf(SESSIONS_TOOL)))
                var tabActions = 0
                var currentTool: String? = null
                var headerScroll: ScrollState? = null
                var positionTabs: ((Boolean) -> Unit)? = null
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity -> activity.setContent {
                        DroidTheme { TerminalHapticFeedbackProvider {
                            val scroll = rememberScrollState()
                            val scope = rememberCoroutineScope()
                            SideEffect {
                                headerScroll = scroll
                                positionTabs = { toEnd -> scope.launch { scroll.scrollTo(if (toEnd) scroll.maxValue else 0) } }
                            }
                            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                                Column(Modifier.fillMaxSize()) {
                                    Spacer(Modifier.weight(1f))
                                    TerminalToolbar(config.value, ToolbarSheetMode.Default, {}, 400.dp,
                                        TerminalChromeColors(Color.Black)) { id, active ->
                                        SideEffect { if (active) currentTool = id }
                                        if (id == SESSIONS_TOOL) ToolbarSessionsTool(sessions, selected, active,
                                            emptySet(), emptyList(), {}, {}, false, { tabActions++ },
                                            { tabActions++ }, { tabActions++ }, {}, {}, {}, headerTabScroll = scroll)
                                        else Text("$id tool", Modifier.fillMaxSize())
                                    }
                                }
                            }
                        } }
                    } }
                    settle()
                    scenario.onActivity { config.value = ToolbarConfig(tools = listOf("before", "previous", SESSIONS_TOOL, "next", "after")) }
                    settle()
                    val page = bounds(handle())
                    // ScrollState.scrollTo positions only the tabs, without nested-scroll overflow.
                    scenario.onActivity { requireNotNull(positionTabs)(forward) }
                    settle()
                    assertEquals(if (forward) requireNotNull(headerScroll).maxValue else 0, requireNotNull(headerScroll).value)
                    assertEquals("Positioning tabs must keep the sessions tool selected", SESSIONS_TOOL, currentTool)
                    val body = bounds(tabText(edge.title))
                    val rect = if (start == "close") bounds(description(context.getString(R.string.terminate_session_description, edge.title))) else body
                    val rtl = layoutDirection == LayoutDirection.Rtl
                    val x = when (start) {
                        "icon" -> if (rtl) rect.right - 22 * density else rect.left + 22 * density
                        "title" -> if (rtl) rect.left + 24 * density else rect.right - 24 * density
                        else -> rect.exactCenterX()
                    }
                    val y = if (start == "padding") rect.top + 3 * density else rect.exactCenterY()
                    val moveLeft = forward != rtl
                    val distance = if (moveLeft) x - page.left - 8 * density else page.right - x - 8 * density
                    val case = "$layoutDirection forward=$forward selected=$selectedEdge start=$start"
                    swipe(x, y, if (moveLeft) -distance else distance)
                    val targetLabel = if (forward) "next tool" else "previous tool"
                    val target = bounds(waitForNode("$targetLabel snapped after edge swipe: $case") {
                        it.text?.toString() == targetLabel && bounds(it).let { area -> area.left == page.left && area.right == page.right }
                    })
                    assertEquals("The target tool must finish snapping: $case", page.left, target.left)
                    assertEquals("The target tool must fill the viewport: $case", page.right, target.right)
                    assertEquals("An edge swipe must not select, rename, or close a tab: $case", 0, tabActions)
                }
            }
        }
    }

    @Test fun inactiveSplitTabsStayGroupedAndRestoreFocusInBothToolbarSizes() {
        val sessions = listOf("A", "B", "C").map { SessionUi(it, "NATIVE", it, "RUNNING") }
        val savedPair = TerminalPaneState("A", "B")
        val panes = mutableStateOf(savedPair.select("C"))
        val mode = mutableStateOf(ToolbarSheetMode.Default)
        val selections = mutableListOf<String>()
        var otherTabActions = 0
        var showFirstTabs: (() -> Unit)? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    val scroll = rememberScrollState()
                    val scope = rememberCoroutineScope()
                    SideEffect { showFirstTabs = { scope.launch { scroll.scrollTo(0) }; Unit } }
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.weight(1f))
                        TerminalToolbar(ToolbarConfig(tools = listOf(SESSIONS_TOOL)), mode.value,
                            { mode.value = it }, 400.dp, TerminalChromeColors(Color.Black)) { _, active ->
                            ToolbarSessionsTool(sessions, panes.value.focusedSessionId, active, emptySet(), emptyList(),
                                {}, {}, false, { selections += it; panes.value = panes.value.select(it) },
                                { otherTabActions++ }, { otherTabActions++ }, {}, {}, {},
                                headerTabScroll = scroll, paneState = panes.value)
                        }
                    }
                } }
            } }
            fun tab(title: String, iconOnly: Boolean = false): AccessibilityNodeInfo {
                var target: AccessibilityNodeInfo? = if (iconOnly) description(title) else tabText(title)
                while (target != null && target.stateDescription == null) target = target.parent
                return requireNotNull(target)
            }
            val menuA = context.getString(R.string.workspace_session_menu, "A")
            val menuB = context.getString(R.string.workspace_session_menu, "B")
            val animationScale = android.provider.Settings.Global.getFloat(context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            data class MenuFrame(val width: Float?, val menuPresent: Boolean)
            fun composeRoot(view: View): ViewRootForTest? {
                if (view is ViewRootForTest) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) {
                    composeRoot(view.getChildAt(index))?.let { return it }
                }
                return null
            }
            fun menuFrame(root: ViewRootForTest): MenuFrame {
                var width: Float? = null
                var menuPresent = false
                fun visit(node: SemanticsNode) {
                    if (node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(menuB) == true) menuPresent = true
                    if (node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "B" } == true) {
                        var member: SemanticsNode? = node
                        while (member != null && member.config.getOrNull(SemanticsProperties.StateDescription) == null) member = member.parent
                        width = member?.boundsInRoot?.width
                    }
                    node.children.forEach(::visit)
                }
                visit(root.semanticsOwner.rootSemanticsNode)
                return MenuFrame(width, menuPresent)
            }
            fun captureMenuMotion(action: () -> Unit): List<MenuFrame> {
                if (animationScale == 0f) { action(); return emptyList() }
                var root: ViewRootForTest? = null
                scenario.onActivity { root = composeRoot(it.window.decorView) }
                val compose = requireNotNull(root)
                val frames = mutableListOf<MenuFrame>()
                val listener = ViewTreeObserver.OnPreDrawListener { frames += menuFrame(compose); true }
                scenario.onActivity { compose.view.viewTreeObserver.addOnPreDrawListener(listener) }
                try {
                    action()
                    var result = emptyList<MenuFrame>()
                    scenario.onActivity { result = frames.toList() }
                    return result
                } finally {
                    scenario.onActivity { compose.view.viewTreeObserver.removeOnPreDrawListener(listener) }
                }
            }
            val inspectDelay = InstrumentationRegistry.getArguments().getString("split.inspectInactiveMs")
                ?.toLongOrNull()?.coerceIn(0, 30_000) ?: 0
            val inspectActiveDelay = InstrumentationRegistry.getArguments().getString("split.inspectActiveMs")
                ?.toLongOrNull()?.coerceIn(0, 30_000) ?: 0
            for (size in listOf(ToolbarSheetMode.Default, ToolbarSheetMode.Expanded)) {
                scenario.onActivity { mode.value = size; panes.value = savedPair.select("C") }
                settle()
                val compact = size == ToolbarSheetMode.Default
                if (compact) {
                    // Expose the saved group independently of prior manual tab scrolling.
                    scenario.onActivity { requireNotNull(showFirstTabs)() }
                    settle()
                }
                assertEquals("The ordinary tab stays selected in $size", "C", panes.value.focusedSessionId)
                assertTrue("C keeps its selected tab style in $size", tab("C").isSelected)
                assertEquals(listOf("A", "B"), panes.value.splitSessionIds)
                assertFalse("Showing C does not display the saved pair", panes.value.split)
                for (title in listOf("A", "B")) {
                    val member = tab(title, compact)
                    assertFalse("The saved $title tab remains unselected in $size", member.isSelected)
                    if (compact) {
                        assertNull("Compact inactive members show icons without title text", find { it.text?.toString() == title })
                        assertEquals("Compact inactive members keep their 48dp width",
                            48f * context.resources.displayMetrics.density, bounds(member).width().toFloat(), 1f)
                    }
                }
                val inactiveA = if (compact) null else bounds(tab("A"))
                val inactiveB = if (compact) null else bounds(tab("B"))
                if (!compact) {
                    assertNull("An inactive group has no common A menu", find { it.contentDescription?.toString() == menuA })
                    assertNull("An inactive group has no common B menu", find { it.contentDescription?.toString() == menuB })
                    val fullRow = bounds(tab("C"))
                    assertTrue("The inactive pair uses the whole row without a menu slot",
                        kotlin.math.abs(inactiveA!!.left - fullRow.left) <= 2 &&
                            kotlin.math.abs(inactiveB!!.right - fullRow.right) <= 2)
                }
                if (inspectDelay > 0) {
                    android.util.Log.i("InactiveSplitInspection", "Inactive split tabs ready: $size")
                    SystemClock.sleep(inspectDelay)
                }
                val showingFrames = if (compact) {
                    click(description("B")); emptyList()
                } else captureMenuMotion { click(tabText("B")) }
                assertEquals("Selecting a member restores both panes in $size", listOf("A", "B"), panes.value.sessionIds)
                assertEquals("The clicked member receives focus in $size", "B", panes.value.focusedSessionId)
                assertTrue(panes.value.split)
                assertEquals(1, panes.value.focus)
                assertTrue("The restored member uses the selected tab style in $size", tab("B").isSelected)
                assertFalse("The partner retains unselected semantics in $size", tab("A", compact).isSelected)
                val activeB = if (compact) null else bounds(tab("B"))
                if (!compact) {
                    description(menuB)
                    val activeWidth = requireNotNull(activeB).width()
                    val inactiveWidth = requireNotNull(inactiveB).width()
                    assertTrue("Showing the menu gives it room beside the two tabs", activeWidth < inactiveWidth)
                    if (animationScale > 0f) assertTrue("The common menu appears through intermediate tab widths: ${showingFrames.take(20)}",
                        showingFrames.any { it.width != null && it.width > activeWidth + 2 && it.width < inactiveWidth - 2 })
                }
                if (inspectActiveDelay > 0) {
                    android.util.Log.i("ActiveSplitInspection", "Active split tabs ready: $size")
                    SystemClock.sleep(inspectActiveDelay)
                }
                if (!compact) {
                    val hidingFrames = captureMenuMotion { click(tabText("C")) }
                    assertEquals("C opens alone again", listOf("C"), panes.value.sessionIds)
                    assertTrue("C regains selected semantics", tab("C").isSelected)
                    assertFalse(tab("A").isSelected)
                    assertFalse(tab("B").isSelected)
                    assertNull("The hidden common menu has no accessibility node", find { it.contentDescription?.toString() == menuB })
                    assertNull(find { it.contentDescription?.toString() == menuA })
                    assertEquals("A reclaims its original width", inactiveA!!.width().toFloat(), bounds(tab("A")).width().toFloat(), 2f)
                    assertEquals("B reclaims its original width", inactiveB!!.width().toFloat(), bounds(tab("B")).width().toFloat(), 2f)
                    if (animationScale > 0f) assertTrue("The menu loses semantics while the tabs expand: ${hidingFrames.take(20)}",
                        hidingFrames.any { !it.menuPresent && it.width != null &&
                            it.width > activeB!!.width() + 2 && it.width < inactiveB.width() - 2 })
                }
            }
            assertEquals(listOf("B", "B", "C"), selections)
            assertEquals("Restoring a member does not invoke another tab action", 0, otherTabActions)
        }
    }

    @Test fun defaultSessionToolShowsHorizontalTabsAndExpandsToVerticalTabs() {
        val mode = mutableStateOf(ToolbarSheetMode.Default)
        val selected = mutableStateOf("second")
        var showLastTab: (() -> Unit)? = null
        val sessions = mutableStateOf(listOf(
            SessionUi("first", "NATIVE", "First shell", "RUNNING"),
            SessionUi("second", "NATIVE", "Pinned work", "RUNNING",
                hasExplicitTabTitle = true, subtitle = "Original shell"),
        ))
        var newClicks = 0
        var settingsClicks = 0
        var renamed: String? = null
        var terminated: String? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    val scroll = rememberScrollState()
                    val scope = rememberCoroutineScope()
                    SideEffect { showLastTab = { scope.launch { scroll.scrollTo(scroll.maxValue) }; Unit } }
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.weight(1f))
                        TerminalToolbar(ToolbarConfig(tools = listOf(SESSIONS_TOOL)), mode.value,
                            { mode.value = it }, 400.dp, TerminalChromeColors(Color.Black)) { _, active ->
                            ToolbarSessionsTool(sessions.value, selected.value, active, emptySet(), emptyList(),
                                {}, {}, false, { selected.value = it }, { renamed = it.id },
                                { terminated = it.id }, { newClicks++ }, {},
                                { settingsClicks++ }, headerTabScroll = scroll)
                        }
                    }
                } }
            } }
            settle()
            // Selection keeps the viewport in place; position the tab explicitly for this UI test.
            scenario.onActivity { showLastTab!!() }
            settle()
            val defaultHandle = bounds(handle())
            val title = bounds(tabText("Pinned work"))
            val subtitle = bounds(tabText("Original shell"))
            val newButton = bounds(description(label(R.string.new_session)))
            val settingsButton = bounds(description(label(R.string.settings)))
            val density = context.resources.displayMetrics.density
            assertTrue("Horizontal tabs use the header's left side", maxOf(title.right, subtitle.right) <= newButton.left)
            assertEquals(newButton.centerY(), settingsButton.centerY())
            assertEquals("The header fills the existing default viewport", 46f * density,
                (newButton.exactCenterY() - defaultHandle.bottom), 1f)
            assertNull(find { it.contentDescription?.toString() ==
                context.getString(R.string.workspace_session_menu, "Pinned work") })
            drag(title.exactCenterX(), title.exactCenterY(), 0f, 0f, longPress = true)
            assertNull("Long press opens a menu before performing an action", renamed)
            assertEquals("Long press must not switch tabs", "second", selected.value)
            click(text(label(R.string.rename_session_action)))
            assertEquals("second", renamed)
            click(description(context.getString(R.string.terminate_session_description, "Pinned work")))
            assertEquals("second", terminated)
            click(description(label(R.string.new_session)))
            click(description(label(R.string.settings)))
            assertEquals(1, newClicks)
            assertEquals(1, settingsClicks)
            scenario.onActivity { sessions.value = sessions.value.map {
                if (it.id == "second") it.copy(subtitle = "Updated automatic title") else it
            } }
            tabText("Updated automatic title")
            assertNull(find { it.text?.toString() == "Original shell" })
            val tab = bounds(tabText("Pinned work"))
            drag(tab.exactCenterX(), tab.exactCenterY(), 180 * density, 0f)
            val firstTab = bounds(tabText("First shell"))
            assertEquals("Default tabs share the action row", newButton.centerY(), firstTab.centerY())
            assertNull(find { it.contentDescription?.toString() ==
                context.getString(R.string.terminate_session_description, "First shell") })
            drag(firstTab.exactCenterX(), firstTab.exactCenterY(), 0f, 0f, longPress = true)
            click(text(label(R.string.rename_session_action)))
            assertEquals("The menu can rename an inactive tab", "first", renamed)
            assertEquals("Editing an inactive tab must not select it", "second", selected.value)
            click(tabText("First shell"))
            assertEquals("Default tabs can switch sessions", "first", selected.value)
            action(label(R.string.toolbar_expand))
            assertNull(find { it.text?.toString() == context.getString(R.string.toolbar_session_count, 2) })
            assertNull(find { it.text?.toString() ==
                context.getString(R.string.toolbar_session_state_count, label(R.string.session_state_running), 2) })
            tabText("First shell")
            tabText("Pinned work")
            click(tabText("Pinned work"))
            assertEquals("second", selected.value)
            action(label(R.string.toolbar_default))
            assertEquals(defaultHandle, bounds(handle()))
            scenario.onActivity { showLastTab!!() }
            settle()
            tabText("Pinned work")
            tabText("Updated automatic title")
            assertNull(find { it.text?.toString() == context.getString(R.string.toolbar_session_count, 2) })
            scenario.onActivity { sessions.value = sessions.value.map {
                if (it.id == "second") it.copy(title = "") else it
            } }
            tabText(label(R.string.shell_default_title))
        }
    }

    @Test fun defaultSessionTabsSwitchOncePerTouchWithoutAutoScrolling() {
        val selected = mutableStateOf("first")
        val selections = mutableListOf<String>()
        var positionTabs: ((Boolean) -> Unit)? = null
        var headerScroll: ScrollState? = null
        val sessions = listOf(
            SessionUi("first", "NATIVE", "A", "RUNNING"),
            SessionUi("second", "NATIVE", "B", "RUNNING"),
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    val scroll = rememberScrollState()
                    val scope = rememberCoroutineScope()
                    SideEffect {
                        headerScroll = scroll
                        positionTabs = { end -> scope.launch { scroll.scrollTo(if (end) scroll.maxValue else 0) }; Unit }
                    }
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.weight(1f))
                        TerminalToolbar(ToolbarConfig(tools = listOf(SESSIONS_TOOL, HOTKEY_TOOL)),
                            ToolbarSheetMode.Default, {}, 400.dp, TerminalChromeColors(Color.Black)) { tool, active ->
                            if (tool == SESSIONS_TOOL) ToolbarSessionsTool(sessions, selected.value, active,
                                emptySet(), emptyList(), {}, {}, false,
                                { selected.value = it; selections += it }, {}, {}, {}, {}, {}, headerTabScroll = scroll)
                        }
                    }
                } }
            } }
            settle()
            val clippedIcon = waitForNode("inactive tab icon at the viewport edge") { node ->
                node.contentDescription?.toString() == label(R.string.runtime_native_title) &&
                    generateSequence(node.parent) { it.parent }.none { it.isSelected }
            }
            var scrollNode: AccessibilityNodeInfo? = clippedIcon
            while (scrollNode != null && !scrollNode.isScrollable) scrollNode = scrollNode.parent
            val viewport = bounds(requireNotNull(scrollNode))
            val beforeSelection = requireNotNull(headerScroll).value
            click(clippedIcon, pressMillis = 120, settleAfter = false)
            assertEquals("One touch on a partially visible tab selects it", "second", selected.value)
            assertEquals(listOf("second"), selections)
            // Let the selected tab recompose before the next touch, without waiting out a scroll animation.
            instrumentation.waitForIdleSync()
            SystemClock.sleep(32)
            tap(viewport.left + 8 * context.resources.displayMetrics.density, viewport.exactCenterY(), pressMillis = 120)
            assertEquals("The next touch immediately selects the other tab", "first", selected.value)
            assertEquals(listOf("second", "first"), selections)
            assertEquals("Tab selection keeps the viewport in place", beforeSelection, requireNotNull(headerScroll).value)
            repeat(8) { index ->
                val target = sessions[(index + 1) % sessions.size]
                scenario.onActivity { positionTabs!!(target.id == "second") }
                settle()
                // Keep the finger down through recomposition and the pressed-shape animation.
                click(tabText(target.title), pressMillis = 120)
                assertEquals("One touch selects ${target.title} on attempt $index", target.id, selected.value)
                assertEquals("A touch dispatches exactly one selection", index + 3, selections.size)
            }
            val manualPosition = requireNotNull(headerScroll).value
            scenario.onActivity { selected.value = "second" }
            settle()
            assertEquals("External selection keeps the manually positioned viewport", manualPosition,
                requireNotNull(headerScroll).value)
        }
    }

    @Test fun defaultSessionTabsSeparateOnlyAdjacentInactiveTabs() {
        val selected = mutableStateOf("third")
        val sessions = listOf(
            SessionUi("first", "NATIVE", "A", "RUNNING"),
            SessionUi("second", "NATIVE", "B", "RUNNING"),
            SessionUi("third", "NATIVE", "C", "RUNNING"),
        )
        val colors = TerminalChromeColors(Color.Black)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    Box(Modifier.fillMaxWidth().height(92.dp)) {
                        ToolbarSessionsTool(sessions, selected.value, true, emptySet(), emptyList(),
                            {}, {}, false, { selected.value = it }, {}, {}, {}, {}, {}, colors,
                            expansionProgress = 0f)
                    }
                } }
            } }
            settle()
            fun hasDivider(): Boolean {
                val button = bounds(description(label(R.string.new_session)))
                val density = context.resources.displayMetrics.density
                val halfHeight = (10 * density).toInt()
                val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                return try {
                    (0 until button.left).any { x ->
                        (button.centerY() - halfHeight..button.centerY() + halfHeight).all { y ->
                            screenshot.getPixel(x, y) == colors.divider.toArgb()
                        }
                    }
                } finally { screenshot.recycle() }
            }
            assertTrue("A and B are adjacent inactive tabs", hasDivider())
            assertNull(find { it.contentDescription?.toString() == context.getString(R.string.terminate_session_description, "A") })
            assertNull(find { it.contentDescription?.toString() == context.getString(R.string.terminate_session_description, "B") })
            description(context.getString(R.string.terminate_session_description, "C"))
            click(tabText("B"))
            assertEquals("second", selected.value)
            assertFalse("No divider belongs on either side of the selected middle tab", hasDivider())
            assertNull(find { it.contentDescription?.toString() == context.getString(R.string.terminate_session_description, "C") })
            description(context.getString(R.string.terminate_session_description, "B"))
            scenario.onActivity { selected.value = "first" }
            settle()
            assertTrue("B and C now share an inactive boundary", hasDivider())
        }
    }

    @Test fun expandedSessionHeaderOmitsCountsAndKeepsSessionSelection() {
        val selected = mutableStateOf("first")
        val sessions = mutableStateOf(listOf(
            SessionUi("first", "NATIVE", "First shell", "RUNNING"),
            SessionUi("second", "ROOTLESS", "Second shell", "RUNNING"),
            SessionUi("third", "NATIVE", "Exited shell", "EXITED"),
        ))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    Box(Modifier.fillMaxWidth().height(400.dp)) {
                        ToolbarSessionsTool(sessions.value, selected.value, true, emptySet(), emptyList(),
                            {}, {}, false, { selected.value = it }, {}, {}, {}, {}, {})
                    }
                } }
            } }
            settle()
            assertNull(find { it.text?.toString() == context.getString(R.string.toolbar_session_count, 3) })
            assertNull(find { it.text?.toString() ==
                context.getString(R.string.toolbar_session_state_count, label(R.string.session_state_running), 2) })
            assertNull(find { it.text?.toString() ==
                context.getString(R.string.toolbar_session_state_count, label(R.string.session_state_exited), 1) })
            click(tabText("Exited shell"))
            assertEquals("third", selected.value)
            for ((state, resource) in listOf(
                "PREPARING" to R.string.avf_session_preparing_state,
                "CREATED" to R.string.session_state_created,
                "EXITED" to R.string.session_state_exited,
                "LAUNCH_FAILED" to R.string.avf_session_failed_state,
                "FUTURE_STATE" to R.string.session_state_unknown,
            )) {
                scenario.onActivity {
                    sessions.value = listOf(
                        SessionUi("first", "NATIVE", "First shell", state),
                        SessionUi("second", "NATIVE", "Second shell", if (state == "FUTURE_STATE") "OTHER_STATE" else state),
                    )
                }
                settle()
                tabText("First shell")
                tabText("Second shell")
                assertNull(find { it.text?.toString() == context.getString(R.string.toolbar_session_count, 2) })
                assertNull(find { it.text?.toString() ==
                    context.getString(R.string.toolbar_session_state_count, label(resource), 2) })
            }
            scenario.onActivity { sessions.value = emptyList() }
            settle()
            assertNull(find { it.text?.toString() == context.getString(R.string.toolbar_session_count, 0) })
            assertNull(find { it.text?.toString() == "First shell" || it.text?.toString() == "Second shell" })
        }
    }

    @Test fun sessionHeaderAndTabsMoveDuringSheetMotionInsteadOfAfterItSettles() {
        val mode = mutableStateOf(ToolbarSheetMode.Default)
        val samples = mutableListOf<Float>()
        var progress = 0f
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.weight(1f))
                        TerminalToolbar(ToolbarConfig(tools = listOf(SESSIONS_TOOL)), mode.value,
                            { mode.value = it }, 400.dp, TerminalChromeColors(Color.Black)) { _, active ->
                            val expansion = LocalToolbarExpansionProgress.current.value
                            SideEffect { progress = expansion; samples += expansion }
                            ToolbarSessionsTool(listOf(SessionUi("first", "NATIVE", "Motion shell", "RUNNING")),
                                "first", active, emptySet(), emptyList(), {}, {}, false, {}, {}, {}, {}, {}, {})
                        }
                    }
                } }
            } }
            settle()
            val density = context.resources.displayMetrics.density
            val initialHandle = bounds(handle())
            val initialButton = bounds(description(label(R.string.new_session)))
            assertEquals(46f * density, initialButton.exactCenterY() - initialHandle.bottom, 1f)
            fun terminateEnabled(): Boolean {
                var node: AccessibilityNodeInfo? = description(
                    context.getString(R.string.terminate_session_description, "Motion shell"))
                // Icon text nodes retain enabled=true; disabled semantics belong to their button.
                while (node != null) {
                    if (!node.isEnabled) return false
                    node = node.parent
                }
                return true
            }
            var middleProgress = 0f
            var middleMode = ToolbarSheetMode.Hidden
            var middleDistance = 0f
            var middleActionEnabled = true
            // Hold between anchors: the settled mode remains Default while the header moves
            // and the list is already being revealed underneath it.
            drag(initialHandle.exactCenterX(), initialHandle.exactCenterY(), 0f, -280f,
                duringDrag = {
                    scenario.onActivity {
                        middleMode = mode.value
                        middleProgress = progress
                    }
                    middleDistance = bounds(description(label(R.string.new_session))).exactCenterY() - bounds(handle()).bottom
                    middleActionEnabled = terminateEnabled()
                })
            assertEquals(ToolbarSheetMode.Default, middleMode)
            assertTrue("The content must follow the finger before settling", middleProgress > 0f && middleProgress < 1f)
            assertTrue("Header must occupy an intermediate position", middleDistance > 24 * density && middleDistance < 46 * density)
            assertFalse("Partially revealed tabs must not accept actions", middleActionEnabled)
            action(label(R.string.toolbar_expand))
            val expandedHandle = bounds(handle())
            val expandedButton = bounds(description(label(R.string.new_session)))
            assertEquals(24f * density, expandedButton.exactCenterY() - expandedHandle.bottom, 1f)
            assertTrue(terminateEnabled())
            scenario.onActivity { samples.clear() }
            action(label(R.string.toolbar_default))
            scenario.onActivity {
                assertTrue("Collapse must also present intermediate frames", samples.any { it > 0f && it < 1f })
                assertEquals(0f, progress, 0f)
            }
            assertEquals(initialHandle, bounds(handle()))
            assertEquals(initialButton, bounds(description(label(R.string.new_session))))
            tabText("Motion shell")
        }
    }

    @Test fun animatedHeightDoesNotRecomposeToolsThatDoNotReadExpansion() {
        val mode = mutableStateOf(ToolbarSheetMode.Default)
        var compositions = 0
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.weight(1f))
                        TerminalToolbar(ToolbarConfig(tools = listOf(HOTKEY_TOOL)), mode.value,
                            { mode.value = it }, 400.dp, TerminalChromeColors(Color.Black)) { _, _ ->
                            SideEffect { compositions++ }
                            Text("Stable tool content")
                        }
                    }
                } }
            } }
            settle()
            val initialHandle = bounds(handle())
            var before = 0
            scenario.onActivity { before = compositions; mode.value = ToolbarSheetMode.Expanded }
            settle()
            assertTrue("The sheet must expand", bounds(handle()).top < initialHandle.top)
            scenario.onActivity {
                assertTrue("Height frames must not repeatedly recompose unrelated content: ${compositions - before}",
                    compositions - before <= 2)
                before = compositions
                mode.value = ToolbarSheetMode.Default
            }
            settle()
            assertEquals(initialHandle, bounds(handle()))
            scenario.onActivity {
                assertTrue("Collapse must also avoid repeated content composition: ${compositions - before}",
                    compositions - before <= 2)
            }
        }
    }

    @Test fun defaultHeightStaysFixedWhenPagingBetweenSessionsAndOtherTools() {
        val tools = listOf(SESSIONS_TOOL, HOTKEY_TOOL, HISTORY_TOOL)
        val mode = mutableStateOf(ToolbarSheetMode.Default)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.weight(1f))
                        TerminalToolbar(ToolbarConfig(tools = tools), mode.value, { mode.value = it },
                            400.dp, TerminalChromeColors(Color.Black)) { id, _ ->
                            Text("$id viewport", Modifier.fillMaxSize())
                        }
                    }
                } }
            } }
            settle()
            val initialHandle = bounds(handle())
            val initialViewport = bounds(text("$SESSIONS_TOOL viewport"))
            val density = context.resources.displayMetrics.density
            assertEquals(24f * density, initialHandle.height().toFloat(), 1f)
            assertEquals(92f * density, initialViewport.height().toFloat(), 1f)
            assertEquals(116f * density, (initialViewport.bottom - initialHandle.top).toFloat(), 1f)
            val route = tools.drop(1) + tools.dropLast(1).reversed()
            for ((index, destination) in route.withIndex()) {
                val direction = if (index < tools.size - 1) -1 else 1
                val startX = initialViewport.exactCenterX() - direction * initialViewport.width() * 0.3f
                drag(startX, initialViewport.exactCenterY(), direction * initialViewport.width() * 0.65f, 0f,
                    duringDrag = {
                        assertEquals("The handle must stay in place during paging", initialHandle, bounds(handle()))
                    })
                assertEquals(ToolbarSheetMode.Default, mode.value)
                assertEquals("The handle must stay in place on $destination", initialHandle, bounds(handle()))
                assertEquals("Every tool must use the same viewport", initialViewport, bounds(text("$destination viewport")))
            }
        }
    }

    @Test fun emptyWorkspaceForcesSessionsToolAndRestoresPagingWhenOccupied() {
        val sessionsEmpty = mutableStateOf(false)
        val mode = mutableStateOf(ToolbarSheetMode.Default)
        val savedConfig = ToolbarConfig(tools = listOf(SESSIONS_TOOL, HISTORY_TOOL))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.weight(1f))
                        TerminalToolbar(savedConfig.visibleConfig(sessionsOnly = sessionsEmpty.value),
                            mode.value, { mode.value = it }, 400.dp, TerminalChromeColors(Color.Black),
                            pageNavigationEnabled = !sessionsEmpty.value) { id, _ ->
                            Text("$id page", Modifier.fillMaxSize())
                        }
                    }
                } }
            } }
            settle()
            val page = bounds(text("$SESSIONS_TOOL page"))
            fun nextTool() = swipe(page.left + page.width() * 0.8f,
                page.exactCenterY(), -page.width() * 0.65f)
            nextTool()
            text("$HISTORY_TOOL page")
            scenario.onActivity { sessionsEmpty.value = true }
            settle()
            text("$SESSIONS_TOOL page")
            for (size in listOf(R.string.toolbar_default, R.string.toolbar_expand)) {
                action(label(size))
                nextTool()
                assertNull("An empty Workspace must not expose other tools",
                    find { it.text?.toString() == "$HISTORY_TOOL page" })
                text("$SESSIONS_TOOL page")
            }
            assertEquals("The restriction preserves the saved tool list",
                listOf(SESSIONS_TOOL, HISTORY_TOOL), savedConfig.tools)
            action(label(R.string.toolbar_default))
            scenario.onActivity { sessionsEmpty.value = false }
            settle()
            nextTool()
            text("$HISTORY_TOOL page")
        }
    }

    @Test fun sheetDragPagingHiddenHandleAndDisabledState() {
        val mode = mutableStateOf(ToolbarSheetMode.Default)
        val config = mutableStateOf(ToolbarConfig(tools = listOf("first", "second")))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) { Text("Terminal canvas") }
                        TerminalToolbar(config.value, mode.value, { mode.value = it }, 400.dp, TerminalChromeColors(Color.Black)) { id, _ ->
                            Text("$id page", Modifier.fillMaxSize())
                        }
                    }
                } }
            } }
            settle()
            assertEquals(ToolbarSheetMode.Default, mode.value)
            assertNoHandleHover(handle())
            val page = bounds(text("first page"))
            val indicatorLabels = (1..2).map { context.getString(R.string.toolbar_page_position, it, 2) }
            assertNull(find { it.contentDescription?.toString() in indicatorLabels })
            drag(page.exactCenterX() + page.width() * 0.3f, page.exactCenterY(), -page.width() * 0.65f, 0f) {
                waitForNode("page dots during swipe") { it.contentDescription?.toString() in indicatorLabels }
            }
            assertEquals(page.height(), bounds(text("second page")).height())
            assertNull(find { it.contentDescription?.toString() in indicatorLabels })
            val initial = bounds(handle())
            drag(initial.exactCenterX(), initial.exactCenterY(), 0f, -800f)
            assertEquals(ToolbarSheetMode.Expanded, mode.value)
            val expandedHandle = bounds(handle())
            assertNoHandleHover(handle())
            drag(expandedHandle.exactCenterX(), expandedHandle.exactCenterY(), 0f, 950f)
            assertEquals(ToolbarSheetMode.Hidden, mode.value)
            assertNull(find { it.text?.toString() == "second page" })
            assertNoHandleHover(handle())
            click(handle())
            assertEquals(ToolbarSheetMode.Default, mode.value)
            text("second page")
            action(label(R.string.toolbar_expand))
            assertEquals(ToolbarSheetMode.Expanded, mode.value)
            action(label(R.string.toolbar_default))
            assertEquals(ToolbarSheetMode.Default, mode.value)
            // Stable tool IDs preserve selection across reordering.
            scenario.onActivity { config.value = config.value.copy(tools = listOf("second", "first")) }
            settle()
            text("second page")
            scenario.onActivity { config.value = config.value.copy(tools = emptyList()) }
            settle()
            text(label(R.string.toolbar_empty))
            scenario.onActivity { config.value = config.value.copy(enabled = false) }
            settle()
            assertNull(find { it.contentDescription?.toString() == label(R.string.toolbar_handle) })
        }
    }

    @Test fun newSessionHandleHasNoHoverIndicationAndStillDismissesByDragging() {
        val visible = mutableStateOf(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    Text("Session sheet fixture")
                    if (visible.value) NewSessionSheet(SessionProfiles(context).builtIns(), {}, {}, { visible.value = false })
                } }
            } }
            settle()
            val handle = waitForNode("modal sheet handle") { node ->
                node.isClickable && node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id }
            }
            assertNoHandleHover(handle)
            val rect = bounds(handle)
            val screen = Rect().also { instrumentation.uiAutomation.rootInActiveWindow.getBoundsInScreen(it) }
            val endY = screen.bottom - 32 * context.resources.displayMetrics.density
            drag(rect.exactCenterX(), rect.exactCenterY(), 0f, endY - rect.exactCenterY())
            assertFalse("Hover must preserve drag dismissal: handle=$rect screen=$screen", visible.value)
            assertNull(find { node -> node.isClickable && node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id } })
            scenario.onActivity { visible.value = true }
            settle()
            click(description(label(R.string.session_sheet_handle)))
            assertFalse("The replacement handle must still dismiss on tap", visible.value)
            scenario.onActivity { visible.value = true }
            settle()
            val accessibleHandle = waitForNode("accessible modal sheet handle") { node ->
                node.isClickable && node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id }
            }
            assertTrue("Dismiss action failed: ${accessibleHandle.actionList}; " + dump(), accessibleHandle
                .performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id))
            settle()
            assertFalse("The replacement handle must retain accessible dismissal", visible.value)
        }
    }

    @Test fun standaloneShiftUsesAToggleAndCanBeCancelled() {
        val shift = mutableStateOf(false)
        val layout = HotkeyLayout(keys = listOf(Hotkey("Shift", "SHIFT")))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    Box(Modifier.height(100.dp)) {
                        ToolbarHotkeys(layout, false, false, shift.value, TerminalChromeColors(Color.Black), true) { key ->
                            assertEquals(TerminalToolbarKey.Shift, key)
                            shift.value = !shift.value
                        }
                    }
                } }
            } }
            settle()
            fun shiftButton(): AccessibilityNodeInfo {
                var node = description("Shift: SHIFT")
                while (!node.isClickable && node.parent != null) node = requireNotNull(node.parent)
                assertTrue("SHIFT must expose an interactive button", node.isClickable)
                return node
            }
            assertFalse(shiftButton().let { it.isSelected || it.isChecked })
            click(description("Shift: SHIFT"))
            assertTrue(shift.value)
            assertTrue(shiftButton().let { it.isSelected || it.isChecked })
            click(description("Shift: SHIFT"))
            assertFalse(shift.value)
            assertFalse(shiftButton().let { it.isSelected || it.isChecked })
        }
    }

    @Test fun hotkeysUseToolbarHeightAndScrollOverflow() {
        val layout = HotkeyLayout(columns = 2, keys = (1..12).map { Hotkey("Key $it", "F$it") })
        val mode = mutableStateOf(ToolbarSheetMode.Default)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.weight(1f))
                        TerminalToolbar(ToolbarConfig(), mode.value, { mode.value = it },
                            240.dp, TerminalChromeColors(Color.Black)) { _, active ->
                            ToolbarHotkeys(layout, false, false, false, TerminalChromeColors(Color.Black), active, {})
                        }
                    }
                } }
            } }
            settle()
            val first = bounds(description("Key 1: F1"))
            val density = context.resources.displayMetrics.density
            assertEquals(40f * density, first.height().toFloat(), 1f)
            assertEquals(92f * density, bounds(waitForNode("default hotkey viewport") { it.isScrollable }).height().toFloat(), 1f)
            description("Key 4: F4")
            assertNull(find { it.contentDescription?.toString() == "Key 5: F5" && !bounds(it).isEmpty })
            val lastVisible = bounds(description("Key 4: F4"))
            drag(first.exactCenterX(), lastVisible.exactCenterY(), 0f, -64f * density)
            waitForNode("third row after scrolling") { it.contentDescription?.toString() == "Key 5: F5" && !bounds(it).isEmpty }
            assertEquals(40f * density, bounds(description("Key 6: F6")).height().toFloat(), 1f)

            action(label(R.string.toolbar_expand))
            assertEquals(ToolbarSheetMode.Expanded, mode.value)
            val expanded = bounds(waitForNode("expanded hotkey viewport") { it.isScrollable })
            assertEquals(216f * density, expanded.height().toFloat(), 1f)
            val fourthRow = bounds(description("Key 8: F8"))
            assertEquals(40f * density, fourthRow.height().toFloat(), 1f)
            assertTrue("Expanded hotkeys use the space below two rows", fourthRow.bottom > expanded.top + 92 * density)
            drag(expanded.exactCenterX(), expanded.bottom - 8 * density, 0f, -64f * density)
            assertEquals(40f * density, bounds(description("Key 12: F12")).height().toFloat(), 1f)

            action(label(R.string.toolbar_default))
            assertEquals(ToolbarSheetMode.Default, mode.value)
            assertEquals(92f * density, bounds(waitForNode("collapsed hotkey viewport") { it.isScrollable }).height().toFloat(), 1f)
            assertNull(find { it.contentDescription?.toString() == "Key 12: F12" && !bounds(it).isEmpty })
        }
    }

    @Test fun removedCommandSetsAreAbsentFromToolbarSettingsAndSearch() {
        val prefs = context.getSharedPreferences("removed-command-tool-test", 0)
        val legacy = ToolbarConfig(false, listOf("memory", "commands", HISTORY_TOOL, SESSIONS_TOOL, HOTKEY_TOOL))
        val expected = legacy.copy(tools = listOf(HISTORY_TOOL, SESSIONS_TOOL, HOTKEY_TOOL))
        prefs.edit().clear().putString(KEY_TOOLBAR, serializeToolbarConfig(legacy))
            .putString("toolbar-commands-v1", "legacy command data").commit()
        try {
            assertEquals(expected, readToolbarConfig(prefs))
            assertFalse(settingsSearchEntries.any { it.target.screen == "toolbar-commands" })
            assertThrows(android.content.pm.PackageManager.NameNotFoundException::class.java) {
                context.packageManager.getActivityInfo(ComponentName(context, "dev.terminal.app.ToolbarCommandEditActivity"), 0)
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    DroidTheme { TerminalHapticFeedbackProvider { ToolbarSettings(prefs, {}) {} } }
                } }
                settle()
                text(label(R.string.toolbar_history))
                text(label(R.string.toolbar_sessions))
                text(label(R.string.settings_hotkeys_title))
                click(description(label(R.string.toolbar_edit)))
                description(context.getString(R.string.toolbar_remove, label(R.string.toolbar_history)))
                assertNull(find {
                    val value = it.text?.toString().orEmpty() + it.contentDescription?.toString().orEmpty()
                    value.contains("명령어셋") || value.contains("Command sets")
                })
                assertEquals(expected, readToolbarConfig(prefs))
                assertEquals("legacy command data", prefs.getString("toolbar-commands-v1", null))
            }
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun retiredCommandSettingsDestinationOpensToolbarSettings() {
        val intent = Intent(context, SettingsDetailActivity::class.java)
            .putExtra(SettingsDetailActivity.EXTRA_CATEGORY, "toolbar-commands")
        ActivityScenario.launch<SettingsDetailActivity>(intent).use {
            text(label(R.string.toolbar_settings))
            description(label(R.string.toolbar_edit))
        }
    }

    @Test fun settingsDragMorphsCornersAtBothEndsWithoutGroupClipping() {
        val prefs = context.getSharedPreferences("toolbar-corners-test", 0)
        val original = listOf(HOTKEY_TOOL, "second", "third")
        val registry = toolbarTools + listOf(
            ToolbarTool("second", R.string.diagnostics_title, "fixture"),
            ToolbarTool("third", R.string.toolbar_empty, "fixture"))
        prefs.edit().clear().putString(KEY_TOOLBAR, serializeToolbarConfig(ToolbarConfig(tools = original))).commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    DroidTheme { TerminalHapticFeedbackProvider { ToolbarSettings(prefs, {}, registry) {} } }
                } }
                settle()
                click(description(label(R.string.toolbar_edit)))
                val moveLabel = context.getString(R.string.toolbar_move, label(R.string.diagnostics_title))
                fun rowBounds(): Rect {
                    var node = description(moveLabel)
                    while (!node.isClickable && node.parent != null) node = node.parent
                    return bounds(node)
                }
                val first = bounds(description(context.getString(R.string.toolbar_move, label(R.string.settings_hotkeys_title))))
                val handle = bounds(description(moveLabel))
                val row = rowBounds()
                val step = handle.exactCenterY() - first.exactCenterY()
                val gridTop = row.top - step
                val density = context.resources.displayMetrics.density
                val start = SystemClock.uptimeMillis()
                var pointerY = handle.exactCenterY()
                fun touch(action: Int) {
                    val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, handle.exactCenterX(), pointerY, 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                }
                touch(MotionEvent.ACTION_DOWN)
                try {
                    pointerY += 36f * density
                    touch(MotionEvent.ACTION_MOVE)
                    SystemClock.sleep(350)
                    fun cornersAt(slot: Float): Pair<Int, Int> {
                        pointerY += gridTop + step * slot - rowBounds().top
                        touch(MotionEvent.ACTION_MOVE)
                        SystemClock.sleep(350)
                        val lifted = rowBounds()
                        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                        try {
                            val inset = (2f * density).toInt()
                            val background = screenshot.getPixel(lifted.centerX(), lifted.top + inset)
                            assertEquals("Lifted surface extends beyond the former wrapper",
                                background, screenshot.getPixel(row.right + 2, lifted.centerY()))
                            fun cutout(y: Int): Int = (0..(40f * density).toInt()).first { x ->
                                screenshot.getPixel(lifted.left + x, y) == background
                            }
                            return cutout(lifted.top + inset) to cutout(lifted.bottom - inset)
                        } finally { screenshot.recycle() }
                    }
                    val middle = cornersAt(1f)
                    val nearTop = cornersAt(0.5f)
                    val top = cornersAt(0f)
                    assertTrue("Top corners grow progressively", middle.first < nearTop.first && nearTop.first < top.first)
                    assertEquals("Bottom corners stay small at the top", middle.second.toFloat(), top.second.toFloat(), 2f * density)
                    val nearBottom = cornersAt(1.5f)
                    val bottom = cornersAt(2f)
                    assertTrue("Bottom corners grow progressively", middle.second < nearBottom.second && nearBottom.second < bottom.second)
                    assertEquals("Top corners stay small at the bottom", middle.first.toFloat(), bottom.first.toFloat(), 2f * density)
                    assertEquals(original, parseToolbarConfig(prefs.getString(KEY_TOOLBAR, null), available = registry.map { it.id }.toSet()).tools)
                } finally { touch(MotionEvent.ACTION_CANCEL) }
                settle()
                assertEquals(original, parseToolbarConfig(prefs.getString(KEY_TOOLBAR, null), available = registry.map { it.id }.toSet()).tools)
                assertEquals(row.top, rowBounds().top)
            }
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun settingsEditAddRemoveReorderAndNavigate() {
        val prefs = context.getSharedPreferences("toolbar-ui-test", 0)
        prefs.edit().clear().putString(KEY_TOOLBAR, serializeToolbarConfig(ToolbarConfig(tools = listOf(HOTKEY_TOOL, "clipboard")))).commit()
        var opened: String? = null
        val registry = toolbarTools + ToolbarTool("second", R.string.diagnostics_title, "fixture")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    DroidTheme { TerminalHapticFeedbackProvider {
                        ToolbarSettings(prefs, {}, registry) { opened = it }
                    } }
                } }
                settle()
                assertNull(find { it.text?.toString() == label(R.string.diagnostics_title) })
                fun toolRowHeight(): Int {
                    var node = text(label(R.string.settings_hotkeys_title))
                    while (!node.isClickable && node.parent != null) node = node.parent
                    return bounds(node).height()
                }
                val normalHeight = toolRowHeight()
                assertEquals(56f * context.resources.displayMetrics.density, normalHeight.toFloat(), 1f)
                click(description(label(R.string.toolbar_edit)))
                assertEquals(normalHeight, toolRowHeight())
                text(label(R.string.diagnostics_title))
                assertNull(find { it.contentDescription?.toString() == context.getString(R.string.toolbar_move, label(R.string.diagnostics_title)) })
                click(description(context.getString(R.string.toolbar_add, label(R.string.diagnostics_title))))
                val first = bounds(description(context.getString(R.string.toolbar_move, label(R.string.settings_hotkeys_title))))
                val second = bounds(description(context.getString(R.string.toolbar_move, label(R.string.diagnostics_title))))
                val original = listOf(HOTKEY_TOOL, "second")
                val delta = (second.exactCenterY() - first.exactCenterY()) * 1.4f
                fun assertDraggedCorners() {
                    var node = description(context.getString(R.string.toolbar_move, label(R.string.settings_hotkeys_title)))
                    while (!node.isClickable && node.parent != null) node = node.parent
                    val row = bounds(node)
                    val inset = (2f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                    val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try {
                        val background = screenshot.getPixel(row.right - inset, row.centerY())
                        for (x in listOf(row.left + inset, row.right - inset)) {
                            for (y in listOf(row.top + inset, row.bottom - inset)) {
                                assertNotEquals("Dragged row must retain rounded corners at ($x, $y)",
                                    background, screenshot.getPixel(x, y))
                            }
                        }
                    } finally { screenshot.recycle() }
                }
                drag(first.exactCenterX(), first.exactCenterY(), 0f, delta, cancel = true) {
                    assertDraggedCorners()
                    val movingNeighbor = bounds(description(context.getString(R.string.toolbar_move, label(R.string.diagnostics_title))))
                    assertTrue("Neighbor moves before drop", movingNeighbor.centerY() < second.centerY())
                    assertEquals(original, parseToolbarConfig(prefs.getString(KEY_TOOLBAR, null), available = registry.map { it.id }.toSet()).tools)
                }
                assertEquals(original, parseToolbarConfig(prefs.getString(KEY_TOOLBAR, null), available = registry.map { it.id }.toSet()).tools)
                assertEquals(second.centerY(), bounds(description(context.getString(R.string.toolbar_move, label(R.string.diagnostics_title)))).centerY())
                drag(first.exactCenterX(), first.exactCenterY(), 0f, delta) { assertDraggedCorners() }
                assertEquals(listOf("second", HOTKEY_TOOL), parseToolbarConfig(prefs.getString(KEY_TOOLBAR, null), available = registry.map { it.id }.toSet()).tools)
                click(description(context.getString(R.string.toolbar_remove, label(R.string.settings_hotkeys_title))))
                assertNull(find { it.contentDescription?.toString() == context.getString(R.string.toolbar_move, label(R.string.settings_hotkeys_title)) })
                click(description(label(R.string.toolbar_done)))
                assertNull(find { it.text?.toString() == label(R.string.settings_hotkeys_title) })
                click(text(label(R.string.diagnostics_title)))
                assertEquals("fixture", opened)
            }
        } finally { prefs.edit().clear().commit() }
    }
}
