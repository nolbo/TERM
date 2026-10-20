package dev.terminal.app

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.DroidTheme
import dev.terminal.image.AvfDistribution
import dev.terminal.image.AvfImageVerifier
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.session.SessionStorage
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** UI-only private fixture. Never boots a VM or changes saved Environments. */
@RunWith(AndroidJUnit4::class)
class AvfEnvironmentUpdateUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && node.text?.toString() == text) return node
        for (i in 0 until node.childCount) find(node.getChild(i), text)?.let { return it }
        return null
    }
    private fun scroll(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
        for (i in 0 until node.childCount) if (scroll(node.getChild(i))) return true
        return false
    }
    private fun visible(text: String): AccessibilityNodeInfo {
        repeat(30) {
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.clearCache()
            find(instrumentation.uiAutomation.rootInActiveWindow, text)?.let { return it }
            scroll(instrumentation.uiAutomation.rootInActiveWindow)
            SystemClock.sleep(100)
        }
        error("Missing UI label: $text")
    }
    private fun click(text: String) {
        visible(text)
        repeat(30) {
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.clearCache()
            var node = find(instrumentation.uiAutomation.rootInActiveWindow, text)
            while (node != null && !node.isClickable) node = node.parent
            if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                instrumentation.waitForIdleSync()
                return
            }
            // A closing dialog briefly retains the same title as its action row.
            SystemClock.sleep(50)
        }
        fail("Missing clickable action: $text")
    }

    @Test fun debugApprovesBothUpdateActionsAndKeepsConfirmation() {
        assertTrue(BuildConfig.DEBUG)
        val storage = UUID.randomUUID().toString()
        val root = SessionStorage.root(context.filesDir, SessionRuntime.AVF, storage)
        root.mkdirs()
        val artifacts = mapOf("kernel" to "vmlinuz", "initrd" to "initrd.img", "system" to "system.img",
            "home" to "home.img", "config" to "config.img")
        artifacts.values.forEach { root.resolve(it).writeText("UI fixture") }
        root.resolve("manifest.properties").writeText("formatVersion=1\ndistribution=debian\narchitecture=arm64\n" +
            artifacts.entries.joinToString("\n") { (key, file) ->
                "$key.sha256=${AvfImageVerifier.sha256(root.resolve(file).toPath())}"
            })
        val action = AtomicReference("")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    DroidTheme {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            AvfEnvironmentImageSetting(storage, AvfDistribution.DEBIAN, null, 0,
                                savedStorage = true, requesting = false, onInstall = {},
                                canManage = true, onManage = action::set)
                        }
                    }
                } }
                for ((title, operation) in listOf(R.string.avf_update_components to "UPDATE_COMPONENTS",
                    R.string.avf_update_image to "UPDATE_IMAGE")) {
                    click(context.getString(title))
                    assertEquals("", action.get())
                    click(context.getString(android.R.string.cancel))
                    assertEquals("", action.get())
                    click(context.getString(title))
                    click(context.getString(android.R.string.ok))
                    assertEquals(operation, action.get())
                    action.set("")
                }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun historyActionsRequireConfirmationAndDoNotDeleteOnCancel() {
        val storage = UUID.randomUUID().toString()
        val root = SessionStorage.root(context.filesDir, SessionRuntime.AVF, storage)
        root.mkdirs()
        val previous = UUID.randomUUID().toString()
        root.resolve(".avf-versions/$previous").mkdirs()
        root.resolve(".avf-previous").writeText(previous)
        val action = AtomicReference("")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    DroidTheme {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            AvfEnvironmentImageSetting(storage, AvfDistribution.DEBIAN, null, 0,
                                savedStorage = true, requesting = false, onInstall = {}, hasData = true,
                                canManage = true, onManage = action::set)
                        }
                    }
                } }
                click(context.getString(R.string.avf_rollback_image))
                visible(context.getString(R.string.avf_rollback_image_help))
                assertEquals("", action.get())
                click(context.getString(android.R.string.cancel))
                assertEquals("", action.get())
                click(context.getString(R.string.avf_rollback_image))
                click(context.getString(android.R.string.ok))
                assertEquals("ROLLBACK_IMAGE", action.get())
                action.set("")
                click(context.getString(R.string.avf_discard_history))
                visible(context.getString(R.string.avf_discard_history_help))
                click(context.getString(android.R.string.cancel))
                assertEquals("", action.get())
                assertTrue(root.resolve(".avf-versions/$previous").isDirectory)
            }
        } finally { root.deleteRecursively() }
    }
}
