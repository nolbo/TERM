package dev.terminal.app

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnvironmentBackupUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && predicate(node)) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun await(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            find(predicate)?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("Backup screen element missing")
    }
    private fun click(node: AccessibilityNodeInfo) {
        var item: AccessibilityNodeInfo? = node
        while (item != null) {
            if (item.isClickable) { assertTrue(item.performAction(AccessibilityNodeInfo.ACTION_CLICK)); return }
            item = item.parent
        }
        error("Backup action is not clickable")
    }

    @Test fun settingsEntryOpensBackupScreenAndStandaloneSafPickerAcrossRecreation() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            click(await { it.text?.toString() == context.getString(R.string.backup_title) })
            val source = await { it.text?.toString() == context.getString(R.string.backup_choose_source) }
            assertFalse("Opening the backup category must not start restoring", find {
                it.text?.toString() == context.getString(R.string.backup_phase_extracting)
            } != null)
            click(source)
            await { it.packageName?.toString() == "com.android.documentsui" }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await { it.text?.toString() == context.getString(R.string.backup_choose_source) }
        }
        ActivityScenario.launch<EnvironmentBackupActivity>(Intent(context, EnvironmentBackupActivity::class.java)).use { scenario ->
            await { it.text?.toString() == context.getString(R.string.backup_choose_source) }
            scenario.recreate()
            await { it.text?.toString() == context.getString(R.string.backup_choose_source) }
            assertFalse(find { it.text?.toString() == context.getString(R.string.backup_phase_extracting) } != null)
        }
    }
}
