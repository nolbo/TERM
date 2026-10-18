package dev.terminal.app

import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppInfoUiTest {
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

    private fun waitFor(scroll: Boolean = false, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            find(predicate)?.let { return it }
            if (scroll) find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(150)
        }
        throw AssertionError("Expected app information or license UI element not found")
    }

    private fun click(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = node
        while (current != null && !current.isClickable) current = current.parent
        assertTrue(requireNotNull(current).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test fun settingsSearchOpensApkInformationAndOfflineLicenseReader() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            val input = waitFor { it.isEditable }
            assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, context.getString(R.string.app_info))
            }))
            click(waitFor { !it.isEditable && it.text?.toString() == context.getString(R.string.app_info) })
            for ((title, value) in listOf(
                R.string.app_version to BuildConfig.VERSION_NAME,
                R.string.app_package_name to context.packageName,
                R.string.app_build_type to BuildConfig.BUILD_TYPE,
                R.string.app_build_number to BuildConfig.VERSION_CODE.toString(),
                R.string.update_channel_label to BuildConfig.UPDATE_CHANNEL,
                R.string.app_build_revision to BuildConfig.UPDATE_REVISION,
            )) {
                waitFor(scroll = true) { it.text?.toString() == context.getString(title) }
                waitFor { it.text?.toString() == value }
            }
            click(waitFor(scroll = true) { it.text?.toString() == context.getString(R.string.open_source_licenses) })
            click(waitFor(scroll = true) { it.text?.toString() == "Pretendard" })
            waitFor { it.text?.toString()?.contains("SIL OPEN FONT LICENSE") == true }
            click(waitFor { it.contentDescription?.toString() == context.getString(R.string.back) })
            waitFor { it.text?.toString() == "Pretendard" }
            click(waitFor(scroll = true) { it.text?.toString() == context.getString(R.string.license_iphone_runtime) })
            waitFor { it.text?.toString()?.contains("COPYING") == true }
        }
    }

    @Test fun apkIncludesNonemptyLicensesForUiFontsAndNestedRuntimeComponents() {
        val paths = mutableListOf<String>()
        fun visit(path: String) {
            val children = context.assets.list(path).orEmpty()
            if (children.isEmpty()) {
                assertTrue("License is empty: $path", context.assets.open(path).bufferedReader().use { it.readText() }.isNotBlank())
                paths += path
            } else children.forEach { visit("$path/$it") }
        }
        visit("licenses")
        for (name in listOf("TERM-GPL-3.0.txt", "AndroidX-Kotlin-Apache-2.0.txt", "Markwon-Apache-2.0.txt",
            "CommonMark-BSD-2-Clause.txt", "Commons-Compress-Apache-2.0.txt", "XZ-for-Java-0BSD.txt",
            "GoogleSans-OFL.txt", "GoogleSansCode-OFL.txt", "Pretendard-OFL.txt",
            "material-symbols-Apache-2.0.txt", "proot-GPL-2.0-or-later.txt")) {
            assertTrue("Missing bundled license: $name", "licenses/$name" in paths)
        }
        for (directory in listOf("iphone", "iphone-tools", "usb-serial")) {
            assertTrue("Missing nested runtime licenses: $directory", paths.any { it.startsWith("licenses/$directory/") })
        }
    }
}
