package dev.terminal.app

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.DroidTheme
import dev.terminal.image.AvfDistribution
import dev.terminal.image.AvfImageUpdateKind
import dev.terminal.image.AvfImageUpdateResult
import dev.terminal.image.BundledAvfImageCatalog
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.session.SessionStorage
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    private fun absent(resource: Int) {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.clearCache()
        assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, context.getString(resource)))
    }

    @Test fun checkingShowsOnlyTheAvailableUpdateKindAndNoActionForOtherResults() {
        assertTrue(BuildConfig.DEBUG)
        for (result in listOf(AvfImageUpdateResult.Available(AvfImageUpdateKind.COMPONENTS),
            AvfImageUpdateResult.Available(AvfImageUpdateKind.IMAGE), AvfImageUpdateResult.UpToDate,
            AvfImageUpdateResult.Unsupported, null)) {
            val action = AtomicReference("")
            val calls = AtomicInteger()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    DroidTheme {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            AvfImageUpdateSetting(context.cacheDir.toPath(),
                                BundledAvfImageCatalog.find(AvfDistribution.DEBIAN), revision = 0,
                                working = false, canManage = true, onUpdate = action::set,
                                checkUpdate = { _, _, _, _ ->
                                    calls.incrementAndGet()
                                    result ?: throw java.io.IOException("offline fixture")
                                })
                        }
                    }
                } }
                visible(context.getString(R.string.avf_check_updates))
                absent(R.string.avf_update_components)
                absent(R.string.avf_update_image)
                assertEquals(0, calls.get())
                click(context.getString(R.string.avf_check_updates))
                visible(context.getString(when (result) {
                    is AvfImageUpdateResult.Available -> R.string.avf_update_available
                    AvfImageUpdateResult.UpToDate -> R.string.avf_update_current
                    AvfImageUpdateResult.Unsupported -> R.string.avf_update_unsupported
                    null -> R.string.avf_update_check_failed
                }))
                assertEquals(1, calls.get())
                assertEquals("", action.get())
                if (result is AvfImageUpdateResult.Available) {
                    val components = result.kind == AvfImageUpdateKind.COMPONENTS
                    val title = if (components) R.string.avf_update_components else R.string.avf_update_image
                    absent(if (components) R.string.avf_update_image else R.string.avf_update_components)
                    click(context.getString(title))
                    assertEquals(if (components) "UPDATE_COMPONENTS" else "UPDATE_IMAGE", action.get())
                } else {
                    absent(R.string.avf_update_components)
                    absent(R.string.avf_update_image)
                }
            }
        }
    }

    @Test fun checkingAndActiveWorkHideActionsAndKeepManagementLocks() {
        val releaseCheck = CountDownLatch(1)
        val working = mutableStateOf(false)
        val canManage = mutableStateOf(false)
        val root = context.cacheDir.toPath()
        val candidate = BundledAvfImageCatalog.find(AvfDistribution.DEBIAN)
        fun disabled(resource: Int) {
            repeat(30) {
                var node: AccessibilityNodeInfo? = visible(context.getString(resource))
                while (node != null) {
                    if (!node.isEnabled) return
                    node = node.parent
                }
                // Compose publishes the new enabled semantics on the next frame.
                SystemClock.sleep(50)
            }
            fail("Action is enabled: ${context.getString(resource)}")
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    DroidTheme {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            AvfImageUpdateSetting(root, candidate, 0, working.value, canManage.value, onUpdate = {},
                                checkUpdate = { _, _, _, _ ->
                                    check(releaseCheck.await(10, TimeUnit.SECONDS))
                                    AvfImageUpdateResult.Available(AvfImageUpdateKind.IMAGE)
                                })
                        }
                    }
                } }
                click(context.getString(R.string.avf_check_updates))
                visible(context.getString(R.string.avf_update_checking))
                disabled(R.string.avf_check_updates)
                absent(R.string.avf_update_components)
                absent(R.string.avf_update_image)
                releaseCheck.countDown()
                disabled(R.string.avf_update_image)
                scenario.onActivity { working.value = true }
                disabled(R.string.avf_check_updates)
                absent(R.string.avf_update_components)
                absent(R.string.avf_update_image)
            }
        } finally { releaseCheck.countDown() }
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
