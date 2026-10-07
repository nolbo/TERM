package dev.terminal.app

import android.os.SystemClock
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.AvfImageInstallCard
import dev.terminal.app.ui.AvfImageInstallationUi
import dev.terminal.app.ui.DroidTheme
import dev.terminal.runtime.image.AvfImageInstallPhase
import dev.terminal.runtime.image.AvfImageInstallSnapshot
import dev.terminal.runtime.image.AvfImageInstallState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Renders recorded state fixtures only. Never downloads an image, requests AVF, or changes storage. */
@RunWith(AndroidJUnit4::class)
class AvfImageInstallUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun label(id: Int) = context.getString(id)
    private fun find(node: AccessibilityNodeInfo?, text: String, contains: Boolean = false): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && (if (contains) node.text?.toString()?.contains(text) == true else node.text?.toString() == text)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), text, contains)?.let { return it }
        return null
    }
    private fun waitFor(text: String, contains: Boolean = false) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.clearCache()
            if (find(instrumentation.uiAutomation.rootInActiveWindow, text, contains) != null) return
            SystemClock.sleep(50)
        }
        assertNotNull("Missing visible text: $text", find(instrumentation.uiAutomation.rootInActiveWindow, text, contains))
    }
    private fun waitForGone(text: String) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.clearCache()
            if (find(instrumentation.uiAutomation.rootInActiveWindow, text) == null) return
            SystemClock.sleep(50)
        }
        assertNull("Unexpected visible text: $text", find(instrumentation.uiAutomation.rootInActiveWindow, text))
    }
    private fun snapshot(state: AvfImageInstallState = AvfImageInstallState.RUNNING,
        phase: AvfImageInstallPhase = AvfImageInstallPhase.DOWNLOAD, total: Long? = 100L * 1024 * 1024,
        error: String? = null): AvfImageInstallSnapshot {
        val now = SystemClock.elapsedRealtime()
        return AvfImageInstallSnapshot("fixture-job", "legacy", state, phase, 3, 40L * 1024 * 1024, total, now - 5000, now, error)
    }

    @Test fun dialogShowsMeasuredDownloadAndStaysDismissedWithoutProgressButton() {
        val recording = snapshot()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun render() = scenario.onActivity { activity -> activity.setContent {
                DroidTheme { AvfImageInstallationUi(listOf(recording)) }
            } }
            render()
            waitFor(label(R.string.avf_install_title))
            waitFor(context.getString(R.string.rootless_stage_percent, 40), contains = true)
            waitFor(context.getString(R.string.avf_install_artifact, label(R.string.avf_install_system), 3))
            scenario.recreate()
            render()
            waitFor(context.getString(R.string.rootless_stage_percent, 40), contains = true)
            // Accessibility can update before the recreated dialog's first rendered frame.
            SystemClock.sleep(1000)
            instrumentation.waitForIdleSync()
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            java.io.File(context.externalCacheDir, "avf-image-install-fixture.png").outputStream().use {
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            screenshot.recycle()
            if (InstrumentationRegistry.getArguments().getString("preview") == "true") SystemClock.sleep(20_000)
            var close = find(instrumentation.uiAutomation.rootInActiveWindow, label(R.string.close))
            while (close != null && !close.isClickable) close = close.parent
            assertTrue(close!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitForGone(label(R.string.avf_install_title))
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, label(R.string.avf_install_open_progress)))
            assertEquals(AvfImageInstallState.RUNNING, recording.state)
            scenario.recreate()
            render()
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.clearCache()
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, label(R.string.avf_install_title)))
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, label(R.string.avf_install_open_progress)))
            assertEquals(AvfImageInstallState.RUNNING, recording.state)
        }
    }

    @Test fun unknownDownloadAndFailureDoNotShowInventedPercentages() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun show(state: AvfImageInstallSnapshot) = scenario.onActivity { activity -> activity.setContent {
                DroidTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AvfImageInstallCard(state) } }
            } }
            show(snapshot(total = null))
            waitFor(label(R.string.avf_install_download_note))
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, context.getString(R.string.rootless_stage_percent, 40), contains = true))
            show(snapshot(AvfImageInstallState.FAILED, AvfImageInstallPhase.VERIFY, error = "Fixture checksum mismatch"))
            waitFor(label(R.string.avf_install_failed))
            waitFor("Fixture checksum mismatch")
            show(snapshot(AvfImageInstallState.SUCCEEDED, AvfImageInstallPhase.COMPLETE))
            waitFor(label(R.string.avf_install_complete_note))
        }
    }
}
