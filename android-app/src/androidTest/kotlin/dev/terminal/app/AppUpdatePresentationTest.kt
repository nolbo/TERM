package dev.terminal.app

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.update.AppUpdateManifest
import dev.terminal.app.update.AppUpdateState
import dev.terminal.app.update.UpdateChannel
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Presentation fixtures do not perform network requests, downloads or APK installation. */
class AppUpdatePresentationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val controller get() = (context.applicationContext as TerminalApplication).appUpdates
    private fun label(id: Int) = context.getString(id)
    private fun setState(state: AppUpdateState) {
        controller.javaClass.getDeclaredMethod("setState", AppUpdateState::class.java)
            .apply { isAccessible = true }.invoke(controller, state)
    }

    private fun find(text: String): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && node.text?.toString() == text) return node
            for (index in 0 until node.childCount) search(node.getChild(index))?.let { return it }
            return null
        }
        return search(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun waitFor(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            find(text)?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("Missing update UI text: $text")
    }

    private fun click(text: String) {
        var node: AccessibilityNodeInfo? = waitFor(text)
        while (node != null) {
            if (node.isClickable) {
                assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                return
            }
            node = node.parent
        }
        throw AssertionError("Update action is not clickable: $text")
    }

    @Test fun latestStatusRequiresAConfirmedCurrentState() {
        val original = controller.state
        ActivityScenario.launch(AppUpdatesActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { setState(AppUpdateState.Idle) }
                val initial = label(if (controller.releaseUpdatesSupported) R.string.update_idle_title else R.string.update_debug_title)
                waitFor(initial)
                assertNull(find(label(R.string.update_current_title)))
                assertNull(find(label(R.string.update_current)))
                val channelBounds = Rect().also { waitFor(label(R.string.update_channel_label)).getBoundsInScreen(it) }
                val statusBounds = Rect().also { waitFor(initial).getBoundsInScreen(it) }
                assertTrue("Channel selection must precede the state card", channelBounds.bottom < statusBounds.top)
                scenario.onActivity { setState(AppUpdateState.Current) }
                waitFor(label(R.string.update_current_title))
                waitFor(label(R.string.update_current))
                scenario.onActivity { setState(AppUpdateState.Checking) }
                waitFor(label(R.string.update_checking_title))
                assertNull(find(label(R.string.update_current_title)))
                assertNull(find(label(R.string.update_check)))
                scenario.onActivity { setState(AppUpdateState.Idle) }
                waitFor(initial)
                assertNull(find(label(R.string.update_current_title)))
            } finally { scenario.onActivity { setState(original) } }
        }
    }

    @Test fun newVersionPersistsThroughProgressAndInstallationRequiresConfirmation() {
        val original = controller.state
        val manifest = AppUpdateManifest.parse("""{"schemaVersion":1,"applicationId":"dev.terminal.app","channel":"dev","commit":"${"1".repeat(40)}","versionCode":1000100,"versionName":"1.5.0+dev.1000100.${"1".repeat(12)}","size":10485760,"sha256":"${"0".repeat(64)}","minSdk":31}""", UpdateChannel.DEV)
        val version = context.getString(R.string.update_available, manifest.versionName)
        val installed = context.getString(R.string.update_installed_version, BuildConfig.VERSION_NAME)
        ActivityScenario.launch(AppUpdatesActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { setState(AppUpdateState.Available(manifest)) }
                waitFor(label(R.string.update_available_title))
                waitFor(version)
                assertNull(find(installed))
                waitFor(label(R.string.update_download))
                scenario.onActivity { setState(AppUpdateState.Downloading(manifest, manifest.size / 2)) }
                waitFor(label(R.string.update_downloading_title))
                waitFor(version)
                waitFor("50%")
                assertNull(find(installed))
                assertNull(find(label(R.string.update_download)))
                assertNull(find(label(R.string.update_check)))
                val ready = AppUpdateState.Ready(manifest, File(context.cacheDir, "unused-presentation.apk"))
                scenario.onActivity { setState(ready) }
                waitFor(label(R.string.update_ready_title))
                waitFor(version)
                assertNull(find(installed))
                click(label(R.string.install))
                waitFor(label(R.string.update_install_title))
                waitFor(label(R.string.update_install_warning))
                click(label(R.string.cancel))
                waitFor(label(R.string.update_ready_title))
                assertSame(ready, controller.state)
            } finally { scenario.onActivity { setState(original) } }
        }
    }
}
