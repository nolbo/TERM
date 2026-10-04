package dev.terminal.app

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Checks navigation and one-shot picker behavior without installing an image or starting AVF. */
@RunWith(AndroidJUnit4::class)
class AvfImageSettingsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun label(id: Int) = context.getString(id)

    private fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && node.text?.toString() == text) return node
        for (index in 0 until node.childCount) find(node.getChild(index), text)?.let { return it }
        return null
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue("Expected navigation state was not reached", condition())
    }

    private fun click(text: String) {
        waitUntil { find(instrumentation.uiAutomation.rootInActiveWindow, text) != null }
        var node = find(instrumentation.uiAutomation.rootInActiveWindow, text)
        while (node != null && !node.isClickable) node = node.parent
        assertNotNull("Missing clickable parent for $text", node)
        assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun mainActivity(): MainActivity? {
        var activity: MainActivity? = null
        instrumentation.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().singleOrNull()
        }
        return activity
    }

    @Test fun imageSettingsAndOfflinePickerDoNotReopenInitialSetup() {
        val setupBefore = context.getSharedPreferences("setup-state", Context.MODE_PRIVATE).all.toMap()
        val intent = Intent(context, SettingsDetailActivity::class.java)
            .putExtra(SettingsDetailActivity.EXTRA_CATEGORY, SettingsDetailActivity.AVF_IMAGE)
        ActivityScenario.launch<SettingsDetailActivity>(intent).use { scenario ->
            waitUntil { find(instrumentation.uiAutomation.rootInActiveWindow, label(R.string.avf_image_profile_support)) != null }
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, label(R.string.setup_headline)))
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, label(R.string.avf_image_installation_status)))
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, label(R.string.avf_image_current_build)))
            scenario.recreate()
            waitUntil { find(instrumentation.uiAutomation.rootInActiveWindow, label(R.string.avf_image_profile_support)) != null }
            click(label(R.string.select_avf_image))
            waitUntil { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.endsWith(".documentsui") == true }
            assertEquals(setupBefore, context.getSharedPreferences("setup-state", Context.MODE_PRIVATE).all)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitUntil { mainActivity() != null && instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == context.packageName }
            val original = mainActivity()!!
            instrumentation.runOnMainSync { original.recreate() }
            waitUntil { mainActivity()?.let { it !== original } == true &&
                instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == context.packageName }
        }
    }
}
