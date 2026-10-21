package dev.terminal.app

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Checks that terminal settings omit storage access while keeping the following sections reachable. */
@RunWith(AndroidJUnit4::class)
class SharedStorageSettingsUiTest {
    @Test fun terminalSettingsOmitStorageAccessAndKeepBackgroundAndAppPermissions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            instrumentation.uiAutomation.clearCache()
            fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.isVisibleToUser && predicate(node)) return node
                for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
                return null
            }
            return visit(instrumentation.uiAutomation.rootInActiveWindow)
        }
        fun await(text: String) {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < deadline) {
                instrumentation.waitForIdleSync()
                if (find { it.text?.toString() == text } != null) return
                fun scroll(node: AccessibilityNodeInfo?): Boolean {
                    if (node == null) return false
                    if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
                    for (index in 0 until node.childCount) if (scroll(node.getChild(index))) return true
                    return false
                }
                scroll(instrumentation.uiAutomation.rootInActiveWindow)
                SystemClock.sleep(100)
            }
            throw AssertionError("Terminal setting text missing: $text")
        }
        val intent = Intent(context, SettingsDetailActivity::class.java)
            .putExtra(SettingsDetailActivity.EXTRA_CATEGORY, SettingsDetailActivity.TERMINAL)
            .putExtra(SettingsDetailActivity.EXTRA_SECTION_INDEX, 5)
        ActivityScenario.launch<SettingsDetailActivity>(intent).use {
            await(context.getString(R.string.haptic_scroll))
            val removedLabels = setOf("공유 내부저장소", "내부저장소 접근 설정", "접근 범위와 적용 시점",
                "Shared internal storage", "Configure internal storage access", "Access scope and timing")
            var foundBackground = false
            var foundPermissions = false
            var reachedEnd = false
            for (page in 0 until 16) {
                instrumentation.waitForIdleSync()
                assertNull(find { it.text?.toString() in removedLabels })
                foundBackground = foundBackground || find { it.text?.toString() == context.getString(R.string.wake_policy) } != null
                foundPermissions = foundPermissions || find { it.text?.toString() == context.getString(R.string.android_permissions) } != null
                if (find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) != true) {
                    reachedEnd = true
                    break
                }
                instrumentation.uiAutomation.waitForIdle(100, 3_000)
            }
            assertTrue("Terminal settings did not reach the end", reachedEnd)
            assertTrue("Background execution settings missing", foundBackground)
            assertTrue("Android permission settings missing", foundPermissions)
        }
    }
}
