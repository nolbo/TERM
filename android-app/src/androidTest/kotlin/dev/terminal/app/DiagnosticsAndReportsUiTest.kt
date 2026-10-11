package dev.terminal.app

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DiagnosticsAndReportsUiTest {
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
            SystemClock.sleep(100)
        }
        throw AssertionError("Expected diagnostic or report UI was not found")
    }

    private fun click(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = node
        while (current != null && !current.isClickable) current = current.parent
        assertTrue(requireNotNull(current).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test fun settingsSearchOpensMenuAndEachDestinationUsesAnIndependentActivity() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            val input = waitFor { it.isEditable }
            assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    context.getString(R.string.settings_diagnostics_title))
            }))
            click(waitFor { !it.isEditable && it.text?.toString() == context.getString(R.string.settings_diagnostics_title) })
            val categories = listOf(
                R.string.diagnostics_category_runtime to listOf(
                    NativeDiagnosticsActivity::class.java to R.string.runtime_native_title,
                    UserspaceLinuxDiagnosticsActivity::class.java to R.string.settings_userspace_title,
                    AvfLinuxDiagnosticsActivity::class.java to R.string.settings_avf_title,
                ),
                R.string.diagnostics_category_system to listOf(MemoryDiagnosticsActivity::class.java to R.string.memory_diagnostics_title),
                R.string.diagnostics_category_reports to listOf(CrashReportsActivity::class.java to R.string.crash_reports_title),
            )
            categories.forEach { (category, entries) ->
                val heading = waitFor(scroll = true) { it.text?.toString() == context.getString(category) }
                assertFalse("Category heading is not a destination", heading.isClickable)
                entries.forEach { (activityClass, title) ->
                    val monitor = instrumentation.addMonitor(activityClass.name, null, false)
                    try {
                        click(waitFor(scroll = true) { it.text?.toString() == context.getString(title) })
                        val child = monitor.waitForActivityWithTimeout(5000)
                        assertNotNull("Independent activity for ${activityClass.simpleName}", child)
                        assertEquals(activityClass, child.javaClass)
                        instrumentation.runOnMainSync { (child as androidx.activity.ComponentActivity).onBackPressedDispatcher.onBackPressed() }
                    } finally { instrumentation.removeMonitor(monitor) }
                    waitFor { it.text?.toString() == context.getString(R.string.settings_diagnostics_title) }
                }
            }
            assertNull(find { it.text?.toString() == context.getString(R.string.forwarded_ports_title) })
        }
    }

    @Test fun nativeAndUserspaceDiagnosticsReflectDeviceAndPackagedEngine() {
        ActivityScenario.launch(NativeDiagnosticsActivity::class.java).use { scenario ->
            val deadline = SystemClock.elapsedRealtime() + 5000
            var info: dev.terminal.runtime.native.NativeCompatibility? = null
            while (info == null && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { info = it.information }; SystemClock.sleep(50)
            }
            val observed = requireNotNull(info)
            assertEquals(java.io.File(dev.terminal.runtime.native.DEFAULT_NATIVE_SHELL).isFile,
                observed.shellPath != null)
            assertTrue(observed.backendAvailable == true)
            assertTrue(observed.shellExecutable == true)
            val row = waitFor { it.text?.toString() == context.getString(R.string.native_default_shell) }
            assertFalse(row.isClickable)
        }
        val expected = dev.terminal.runtime.rootless.RootlessEngineInfo.inspect(context)
        ActivityScenario.launch(UserspaceLinuxDiagnosticsActivity::class.java).use { scenario ->
            val deadline = SystemClock.elapsedRealtime() + 5000
            var info: dev.terminal.runtime.rootless.RootlessEngineInfo? = null
            while (info == null && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { info = it.information }; SystemClock.sleep(50)
            }
            assertEquals(expected, info)
            waitFor { it.text?.toString() == context.getString(R.string.userspace_proot) }
            assertNull(find { it.isEditable || it.className == "android.widget.Switch" })
        }
    }

    @Test fun unsupportedAvfIsSafeAndDoesNotCreateRuntimeOrVmFiles() {
        val processManager = context.getSystemService(android.app.ActivityManager::class.java)
        fun vmhostRunning() = processManager.runningAppProcesses.orEmpty().any { it.processName == context.packageName + ":vmhost" }
        fun artifacts() = listOf("vm", "shared-vms").associateWith { name ->
            File(context.filesDir, name).listFiles()?.map { it.name }?.toSet().orEmpty()
        }
        val processBefore = vmhostRunning()
        val filesBefore = artifacts()
        ActivityScenario.launch(AvfLinuxDiagnosticsActivity::class.java).use { scenario ->
            var info: dev.terminal.runtime.avf.system.AvfDiagnostics? = null
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (info == null && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { info = it.information }; SystemClock.sleep(50)
            }
            val result = requireNotNull(info)
            assertEquals(7, result.checks.size)
            val feature = context.packageManager.hasSystemFeature("android.software.virtualization_framework")
            val check = result.checks.single { it.condition == dev.terminal.runtime.avf.system.AvfCondition.SYSTEM_FEATURE }
            assertEquals(if (feature) dev.terminal.runtime.avf.system.AvfCheckStatus.SATISFIED
                else dev.terminal.runtime.avf.system.AvfCheckStatus.UNSATISFIED, check.status)
            if (!feature || !SessionProfiles(context).hasAvfPrivileges()) {
                assertEquals(dev.terminal.runtime.avf.system.AvfCheckStatus.UNSATISFIED, result.status)
                assertEquals(dev.terminal.runtime.avf.system.AvfCheckStatus.UNKNOWN,
                    result.checks.single { it.condition == dev.terminal.runtime.avf.system.AvfCondition.CUSTOM_IMAGE_API }.status)
            }
            scenario.recreate()
            waitFor { it.text?.toString() == context.getString(R.string.avf_overall) }
        }
        assertEquals(processBefore, vmhostRunning())
        assertEquals(filesBefore, artifacts())
    }

    @Test fun reportsPreserveCopyAndBackWhileDeletionRefreshesTheList() {
        // Fixtures do not prune any reports already present on the target device.
        val directory = File(context.filesDir, "crash-reports")
        val store = CrashReportStore(directory, retainedReports = Int.MAX_VALUE)
        store.importPending(context)
        val marker = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis() + 60_000
        val first = store.record("first fixture stack $marker\n" + " at fixture.frame\n".repeat(200),
            "First fixture $marker", timestamp + 1)
        val second = store.record("second fixture stack $marker", "Second fixture $marker", timestamp)
        try {
            ActivityScenario.launch(CrashReportsActivity::class.java).use { scenario ->
                waitFor(scroll = true) { it.text?.toString() == first.summary }
                scenario.recreate()
                click(waitFor(scroll = true) { it.text?.toString() == first.summary })
                waitFor { it.text?.toString() == first.text }
                assertNull(find { it.contentDescription?.toString() == context.getString(R.string.close) })
                click(waitFor { it.isEnabled && it.contentDescription?.toString() == context.getString(R.string.copy_error) })
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                assertEquals(first.text, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
                click(waitFor { it.contentDescription?.toString() == context.getString(R.string.back) })
                click(waitFor(scroll = true) { it.text?.toString() == second.summary })
                waitFor { it.text?.toString() == second.text }
                assertNull(find { it.text?.toString() == first.text })
                click(waitFor { it.contentDescription?.toString() == context.getString(R.string.crash_report_delete) })
                waitFor { it.text?.toString() == context.getString(R.string.crash_report_delete_confirmation) }
                click(waitFor { it.text?.toString() == context.getString(android.R.string.cancel) })
                assertEquals(second, store.read(second.id))
                click(waitFor { it.contentDescription?.toString() == context.getString(R.string.crash_report_delete) })
                click(waitFor { it.text?.toString() == context.getString(R.string.delete) })
                waitFor(scroll = true) { it.text?.toString() == first.summary }
                assertNull(find { it.text?.toString() == second.summary })
                assertEquals(first, CrashReportStore.from(context).read(first.id))
                assertNull(CrashReportStore.from(context).read(second.id))
            }
        } finally {
            File(directory, "${first.id}.json").delete()
            File(directory, "${second.id}.json").delete()
        }
    }

    @Test fun reportDeletionConfirmationSurvivesRecreationAndCancellingPreservesTheReport() {
        val directory = File(context.filesDir, "crash-reports")
        val store = CrashReportStore(directory, retainedReports = Int.MAX_VALUE)
        val report = store.record("recreation fixture ${UUID.randomUUID()}", "Deletion recreation fixture")
        try {
            ActivityScenario.launch<CrashReportActivity>(CrashReportActivity.intent(context, report.id)).use { scenario ->
                waitFor { it.text?.toString() == report.text }
                click(waitFor { it.contentDescription?.toString() == context.getString(R.string.crash_report_delete) })
                waitFor { it.text?.toString() == context.getString(R.string.crash_report_delete_confirmation) }
                scenario.recreate()
                waitFor { it.text?.toString() == context.getString(R.string.crash_report_delete_confirmation) }
                click(waitFor { it.text?.toString() == context.getString(android.R.string.cancel) })
                waitFor { it.text?.toString() == report.text }
                assertEquals(report, store.read(report.id))
                click(waitFor { it.isEnabled && it.contentDescription?.toString() == context.getString(R.string.copy_error) })
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                assertEquals(report.text, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            }
        } finally { File(directory, "${report.id}.json").delete() }
    }

    @Test fun failedReportDeletionKeepsTheDetailAndCopyAvailable() {
        val directory = File(context.filesDir, "crash-reports")
        val store = CrashReportStore(directory, retainedReports = Int.MAX_VALUE)
        val report = store.record("failed deletion fixture ${UUID.randomUUID()}", "Failed deletion fixture")
        val file = File(directory, "${report.id}.json")
        val child = File(file, "blocked")
        try {
            ActivityScenario.launch<CrashReportActivity>(CrashReportActivity.intent(context, report.id)).use { scenario ->
                waitFor { it.text?.toString() == report.text }
                assertTrue(file.delete())
                assertTrue(file.mkdir())
                child.writeText("cannot remove directory")
                click(waitFor { it.contentDescription?.toString() == context.getString(R.string.crash_report_delete) })
                click(waitFor { it.text?.toString() == context.getString(R.string.delete) })
                waitFor { it.text?.toString() == context.getString(R.string.crash_report_delete_failed) }
                assertEquals(Lifecycle.State.RESUMED, scenario.state)
                assertTrue(child.isFile)
                click(waitFor { it.isEnabled && it.contentDescription?.toString() == context.getString(R.string.copy_error) })
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                assertEquals(report.text, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            }
        } finally { child.delete(); file.delete() }
    }

    @Test fun forwardedPortsRedirectWithoutRequiringAvfPrivileges() {
        assumeFalse(SessionProfiles(context).hasAvfPrivileges())
        val monitor = instrumentation.addMonitor(LocalServicesActivity::class.java.name, null, false)
        try {
            ActivityScenario.launch(ForwardedPortsActivity::class.java).use { scenario ->
                val dashboard = monitor.waitForActivityWithTimeout(5000)
                assertNotNull(dashboard)
                assertEquals("AVF", dashboard.intent.getStringExtra(LocalServicesActivity.EXTRA_FILTER))
                assertEquals(Lifecycle.State.DESTROYED, scenario.state)
                instrumentation.runOnMainSync { dashboard.finish() }
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }
}
