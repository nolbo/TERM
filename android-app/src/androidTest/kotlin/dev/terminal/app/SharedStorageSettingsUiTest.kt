package dev.terminal.app

import android.app.AppOpsManager
import android.content.Intent
import android.os.Environment
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Checks common permission state and AVF timing without changing any storage grant or booting a VM. */
@RunWith(AndroidJUnit4::class)
class SharedStorageSettingsUiTest {
    @Test fun commonStoragePermissionExplainsAvfBootAndRevocation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("android:manage_external_storage", AppOpsManager.permissionToOp(android.Manifest.permission.MANAGE_EXTERNAL_STORAGE))
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
        fun await(text: String) {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < deadline) {
                instrumentation.waitForIdleSync()
                if (find(text) != null) return
                fun scroll(node: AccessibilityNodeInfo?): Boolean {
                    if (node == null) return false
                    if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                    for (index in 0 until node.childCount) if (scroll(node.getChild(index))) return true
                    return false
                }
                scroll(instrumentation.uiAutomation.rootInActiveWindow)
                SystemClock.sleep(100)
            }
            throw AssertionError("Shared storage setting text missing: $text")
        }
        val intent = Intent(context, SettingsDetailActivity::class.java)
            .putExtra(SettingsDetailActivity.EXTRA_CATEGORY, SettingsDetailActivity.TERMINAL)
        ActivityScenario.launch<SettingsDetailActivity>(intent).use {
            await(context.getString(if (Environment.isExternalStorageManager()) R.string.shared_storage_enabled else R.string.shared_storage_disabled))
            await(context.getString(R.string.shared_storage_usage_support))
        }
    }
}
