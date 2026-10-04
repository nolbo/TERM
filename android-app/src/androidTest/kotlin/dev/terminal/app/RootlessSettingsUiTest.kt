package dev.terminal.app

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Global engine settings must not offer destructive operations on distribution instances. */
@RunWith(AndroidJUnit4::class)
class RootlessSettingsUiTest {
    @Test fun rootlessSettingsShowEngineSupportAndLimitationsWithoutEnvironmentActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun labels(): Set<String> {
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.clearCache()
            val result = mutableSetOf<String>()
            fun visit(node: AccessibilityNodeInfo?) {
                if (node == null) return
                node.text?.toString()?.let(result::add)
                node.contentDescription?.toString()?.let(result::add)
                for (index in 0 until node.childCount) visit(node.getChild(index))
            }
            visit(instrumentation.uiAutomation.rootInActiveWindow)
            return result
        }
        ActivityScenario.launch<SettingsDetailActivity>(Intent(context, SettingsDetailActivity::class.java)
            .putExtra(SettingsDetailActivity.EXTRA_CATEGORY, SettingsDetailActivity.ROOTLESS)).use {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (context.getString(R.string.userspace_proot) !in labels()) {
                check(SystemClock.elapsedRealtime() < deadline) { "Rootless engine settings did not appear" }
                SystemClock.sleep(100)
            }
            val text = labels()
            assertTrue(context.getString(R.string.userspace_dns) in text)
            assertTrue(context.getString(R.string.userspace_architecture) in text)
            assertTrue(context.getString(R.string.userspace_acceleration) in text)
            assertTrue(context.getString(R.string.userspace_runtime_info) in text)
            assertTrue("Userspace Linux" in text)
            assertFalse(context.getString(R.string.rootless_limitations) in text)
            assertTrue(context.getString(R.string.userspace_limitations_note) in text)
            assertFalse(context.getString(R.string.storage_title) in text)
            assertFalse(context.getString(R.string.rootless_installed_distributions) in text)
            assertFalse(context.getString(R.string.rootless_uninstalled_distributions) in text)
            assertFalse(context.getString(R.string.rootless_remove) in text)
            assertFalse(context.getString(R.string.rootless_complete_environment) in text)
            assertFalse(context.getString(R.string.diagnostics_title) in text)
            assertFalse(context.getString(R.string.forwarded_ports_title) in text)
        }
    }
}
