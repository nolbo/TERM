package dev.terminal.app

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Checks legacy AVF settings navigation without installing an image or starting AVF. */
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

    @Test fun imageSettingsOpenEnvironmentsWithoutChangingInitialSetup() {
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
            val monitor = instrumentation.addMonitor(EnvironmentActivity::class.java.name, null, false)
            try {
                click(label(R.string.avf_image_profile_support))
                val environments = monitor.waitForActivityWithTimeout(5000)
                assertNotNull("AVF image settings open execution environments", environments)
                assertEquals(setupBefore, context.getSharedPreferences("setup-state", Context.MODE_PRIVATE).all)
                instrumentation.runOnMainSync { requireNotNull(environments).finish() }
                waitUntil { find(instrumentation.uiAutomation.rootInActiveWindow, label(R.string.avf_image_profile_support)) != null }
                assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, label(R.string.setup_headline)))
            } finally {
                instrumentation.removeMonitor(monitor)
            }
        }
    }
}
