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
        if (node.isVisibleToUser && node.text?.toString()?.contains(text) == true) return node
        for (index in 0 until node.childCount) find(node.getChild(index), text)?.let { return it }
        return null
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.clearCache()
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

    @Test fun cpuChoicesMatchSupportedPoliciesAndCancellationPreservesLegacySelection() {
        org.junit.Assume.assumeTrue("AVF engine settings require a privileged deployment", SessionProfiles(context).hasAvfPrivileges())
        val preferences = context.getSharedPreferences("runtime-settings", Context.MODE_PRIVATE)
        val before = preferences.all.toMap()
        preferences.edit().putInt("avf-cpu-count", 8).putInt("avf-memory-mib", 0).commit()
        try {
            val intent = Intent(context, SettingsDetailActivity::class.java)
                .putExtra(SettingsDetailActivity.EXTRA_CATEGORY, "avf")
            ActivityScenario.launch<SettingsDetailActivity>(intent).use {
                waitUntil { find(instrumentation.uiAutomation.rootInActiveWindow, label(R.string.avf_memory_auto)) != null }
                fun scrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    if (node == null) return null
                    if (node.isScrollable) return node
                    for (index in 0 until node.childCount) scrollable(node.getChild(index))?.let { return it }
                    return null
                }
                waitUntil {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    if (find(root, label(R.string.avf_cpu_host)) != null) true
                    else { scrollable(root)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); false }
                }
                click(label(R.string.virtual_cpu))
                click(label(R.string.avf_cpu_single))
                click(label(R.string.cancel))
                assertEquals(8, preferences.getInt("avf-cpu-count", -1))
                click(label(R.string.virtual_cpu))
                click(label(R.string.avf_cpu_single))
                click(label(R.string.save))
                waitUntil { preferences.getInt("avf-cpu-count", -1) == 1 }
            }
        } finally {
            val edit = preferences.edit().clear()
            before.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value)
                is Int -> edit.putInt(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
            } }
            edit.commit()
        }
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
