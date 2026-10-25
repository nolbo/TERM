package dev.terminal.app

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.DroidTheme
import dev.terminal.app.ui.SharedFolderEditor
import dev.terminal.app.ui.SharedFoldersSnapshot
import dev.terminal.runtime.session.Environment
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.storage.SharedFolder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Checks retirement of AVF connections while retaining public-to-private restoration. */
@RunWith(AndroidJUnit4::class)
class AvfSharedFolderUiTest {
    @Test fun savingDropsRetiredAvfConnectionWithoutTouchingTheFolder() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val environment = Environment.create(SessionRuntime.AVF, "Private AVF")
        val folder = SharedFolder(UUID.randomUUID().toString(), "Code", setOf(environment.id))
        val snapshot = SharedFoldersSnapshot(listOf(folder), emptyMap(), listOf(environment))
        var saved: SharedFolder? = null
        ActivityScenario.launch(ProfilesActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { SharedFolderEditor(folder.json().toString(), snapshot, null, false,
                    onSave = { saved = it }, onBack = {}) }
            } }
            val text = instrumentation.targetContext.getString(R.string.save)
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (saved == null && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.waitForIdleSync()
                instrumentation.uiAutomation.clearCache()
                fun click(node: AccessibilityNodeInfo?): Boolean {
                    if (node == null) return false
                    if (node.isVisibleToUser && (node.text?.toString() == text || node.contentDescription?.toString() == text)) {
                        var button: AccessibilityNodeInfo? = node
                        while (button != null) {
                            if (button.isClickable) {
                                assertTrue(button.isEnabled)
                                return button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            }
                            button = button.parent
                        }
                    }
                    return (0 until node.childCount).any { click(node.getChild(it)) }
                }
                click(instrumentation.uiAutomation.rootInActiveWindow)
                SystemClock.sleep(50)
            }
            assertEquals(folder.copy(environments = emptySet()), saved)
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.clearCache()
            fun texts(node: AccessibilityNodeInfo?): List<String> = if (node == null) emptyList() else
                listOfNotNull(node.text?.toString()) + (0 until node.childCount).flatMap { texts(node.getChild(it)) }
            val visible = texts(instrumentation.uiAutomation.rootInActiveWindow)
            assertFalse(visible.any { it.contains(environment.name) || it.contains(folder.guestPath) })
            assertFalse(visible.contains(instrumentation.targetContext.getString(R.string.shared_to_vm)))
            assertFalse(visible.contains(instrumentation.targetContext.getString(R.string.shared_from_vm)))
        }
    }
    @Test fun avfOffersNoAndroidStoragePermissionControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var changed = false
        ActivityScenario.launch(ProfilesActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme {
                    androidx.compose.material3.Text("AVF permission preview")
                    EnvironmentStorageAccessSettings(dev.terminal.runtime.session.EnvironmentRuntime.AVF,
                        allowed = true, osGranted = true, editable = true,
                        onChange = { changed = true }, onOpenSettings = { changed = true })
                }
            } }
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.clearCache()
            fun texts(node: AccessibilityNodeInfo?): List<String> = if (node == null) emptyList() else
                listOfNotNull(node.text?.toString()) + (0 until node.childCount).flatMap { texts(node.getChild(it)) }
            var visible = texts(instrumentation.uiAutomation.rootInActiveWindow)
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (!visible.contains("AVF permission preview") && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(50)
                instrumentation.uiAutomation.clearCache()
                visible = texts(instrumentation.uiAutomation.rootInActiveWindow)
            }
            assertTrue("AVF permission preview did not render", visible.contains("AVF permission preview"))
            assertFalse(visible.contains(instrumentation.targetContext.getString(R.string.environment_storage_access)))
            assertFalse(visible.contains(instrumentation.targetContext.getString(R.string.environment_storage_android_permission)))
            assertFalse(changed)
        }
    }

    @Test fun publicRestoreRequiresPermissionAndConversionConsent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val environment = Environment.create(SessionRuntime.AVF, "AVF conversion preview")
        val folder = SharedFolder(UUID.randomUUID().toString(), "Project", setOf(environment.id), androidStorage = true)
        val snapshot = SharedFoldersSnapshot(listOf(folder), emptyMap(), listOf(environment))
        var saved = 0
        fun find(text: String): AccessibilityNodeInfo? {
            instrumentation.uiAutomation.clearCache()
            fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.isVisibleToUser && (node.text?.toString() == text || node.contentDescription?.toString() == text)) return node
                for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
                return null
            }
            return visit(instrumentation.uiAutomation.rootInActiveWindow)
        }
        fun await(text: String): AccessibilityNodeInfo {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < deadline) {
                instrumentation.waitForIdleSync()
                find(text)?.let { return it }
                SystemClock.sleep(50)
            }
            error("Conversion control missing: $text")
        }
        fun button(text: String): AccessibilityNodeInfo {
            var node: AccessibilityNodeInfo? = await(text)
            while (node != null) {
                if (node.isClickable) return node
                node = node.parent
            }
            error("Conversion control is not a button")
        }
        fun awaitDismissed(text: String) {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < deadline) {
                instrumentation.waitForIdleSync()
                if (find(text) == null) return
                SystemClock.sleep(50)
            }
            error("Conversion dialog did not close")
        }
        ActivityScenario.launch(ProfilesActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { SharedFolderEditor(folder.json().toString(), snapshot, null, false,
                    onSave = { saved++ }, onBack = {}) }
            } }
            val save = button(context.getString(R.string.save))
            assertEquals(android.os.Environment.isExternalStorageManager(), save.isEnabled)
            if (save.isEnabled) {
                assertTrue(save.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await(context.getString(R.string.cancel))
                assertEquals(0, saved)
                // The first Save only opens consent; Cancel leaves the draft untouched.
                assertTrue(button(context.getString(R.string.cancel)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                awaitDismissed(context.getString(R.string.cancel))
                assertEquals(0, saved)
                assertTrue(button(context.getString(R.string.save)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await(context.getString(R.string.cancel))
                assertTrue(button(context.getString(R.string.save)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                assertEquals(1, saved)
            }
        }
    }

}
