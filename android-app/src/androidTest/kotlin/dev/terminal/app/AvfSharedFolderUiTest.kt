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

/** Exercises the conditional mount path and retained copy controls without claiming a live AVF VM. */
@RunWith(AndroidJUnit4::class)
class AvfSharedFolderUiTest {
    @Test fun android17SaveRequiresPermissionAndConversionConsent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val environment = Environment.create(SessionRuntime.AVF, "AVF conversion preview")
        val folder = SharedFolder(UUID.randomUUID().toString(), "Project", setOf(environment.id))
        val snapshot = SharedFoldersSnapshot(emptyList(), emptyMap(), listOf(environment), emptyList(), emptyList())
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
                    onSave = { saved++ }, onBack = {}, requiresPublicAvfStorage = true) }
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

    @Test fun livePathExplainsNextBootAndBothFileCopyActionsRemainAvailable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val environment = Environment.create(SessionRuntime.AVF, "AVF sharing preview")
        val folder = SharedFolder(UUID.randomUUID().toString(), "Project", setOf(environment.id))
        val snapshot = SharedFoldersSnapshot(listOf(folder), emptyMap(), listOf(environment), emptyList(), emptyList())
        var direction: String? = null
        fun find(text: String): AccessibilityNodeInfo? {
            instrumentation.uiAutomation.clearCache()
            fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.isVisibleToUser && node.text?.toString()?.contains(text) == true) return node
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
            throw AssertionError("Shared folder UI text missing: $text")
        }
        fun click(text: String) {
            var node: AccessibilityNodeInfo? = await(text)
            while (node != null) {
                if (node.isClickable) { assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); return }
                node = node.parent
            }
            error("File copy control is not clickable")
        }
        ActivityScenario.launch(ProfilesActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { SharedFolderEditor(folder.json().toString(), snapshot, null, false,
                    onSave = {}, onTransfer = { _, _, mode -> direction = mode }, onBack = {}) }
            } }
            await(folder.guestPath)
            await(context.getString(R.string.shared_avf_mount_summary))
            click(context.getString(R.string.shared_to_vm))
            instrumentation.waitForIdleSync()
            assertEquals("TO_VM", direction)
            click(context.getString(R.string.shared_from_vm))
            instrumentation.waitForIdleSync()
            assertEquals("FROM_VM", direction)
        }
    }
}
