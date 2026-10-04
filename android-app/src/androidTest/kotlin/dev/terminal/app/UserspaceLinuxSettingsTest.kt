package dev.terminal.app

import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class UserspaceLinuxSettingsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        instrumentation.waitForIdleSync()
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
            find(predicate)?.let { return it }
            if (scroll) find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(50)
        }
        throw AssertionError("Userspace Linux UI element did not appear")
    }

    private fun click(label: Int) {
        var node: AccessibilityNodeInfo? = waitFor(scroll = true) {
            if (it.text?.toString() != context.getString(label)) false else {
                var button = it
                while (!button.isClickable && button.parent != null) button = button.parent
                button.isClickable && button.isEnabled
            }
        }
        while (node?.isClickable == false) node = node.parent
        assertTrue(checkNotNull(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun launch(screen: String) = ActivityScenario.launch<UserspaceLinuxSettingsActivity>(
        Intent(context, UserspaceLinuxSettingsActivity::class.java).putExtra(SettingsDetailActivity.EXTRA_CATEGORY, screen))

    private fun withRuntime(block: (IRuntimeService) -> Unit) {
        val queue = LinkedBlockingQueue<IRuntimeService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { queue.offer(IRuntimeService.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        try { block(checkNotNull(queue.poll(10, TimeUnit.SECONDS))) } finally { context.unbindService(connection) }
    }

    @Test fun dnsInputIsConditionalValidatedAndSavedOnlyOnSave() = withRuntime { service ->
        val original = JSONObject(service.userspaceLinuxSettingsJson)
        try {
            assertEquals("", service.setUserspaceLinuxDns("android", ""))
            launch(UserspaceLinuxSettingsActivity.DNS).use { scenario ->
                waitFor { it.className == "android.widget.RadioButton" }
                assertNull(find { it.isEditable })
                click(R.string.userspace_dns_custom)
                val field = waitFor { it.isEditable }
                assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "invalid.example")
                }))
                val save = waitFor { it.text?.toString() == context.getString(R.string.save) }
                var button = save
                while (!button.isClickable && button.parent != null) button = button.parent
                assertFalse(button.isEnabled)
                assertEquals("android", JSONObject(service.userspaceLinuxSettingsJson).getString("dnsMode"))
                field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "192.0.2.53\n2001:db8::53")
                })
                scenario.recreate()
                waitFor { it.isEditable && it.text.toString().contains("192.0.2.53") }
                click(R.string.cancel)
                assertEquals("android", JSONObject(service.userspaceLinuxSettingsJson).getString("dnsMode"))
            }
            launch(UserspaceLinuxSettingsActivity.DNS).use {
                waitFor { it.className == "android.widget.RadioButton" }
                click(R.string.userspace_dns_custom)
                waitFor { it.isEditable }.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "192.0.2.53")
                })
                click(R.string.save)
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (JSONObject(service.userspaceLinuxSettingsJson).getString("dnsMode") != "custom") {
                    check(SystemClock.elapsedRealtime() < deadline); SystemClock.sleep(50)
                }
                assertEquals("192.0.2.53", JSONObject(service.userspaceLinuxSettingsJson).getString("dnsServers"))
            }
            assertFalse(service.setUserspaceLinuxDns("custom", "hostname.invalid").isEmpty())
            assertEquals("192.0.2.53", JSONObject(service.userspaceLinuxSettingsJson).getString("dnsServers"))
        } finally {
            assertEquals("", service.setUserspaceLinuxDns(original.getString("dnsMode"), original.getString("dnsServers")))
        }
    }

    @Test fun runtimeInformationIsReadOnly() {
        launch(UserspaceLinuxSettingsActivity.INFO).use {
            waitFor { it.text?.toString() == context.getString(R.string.userspace_loader) }
            assertNotNull(find { it.text?.toString() == context.getString(R.string.userspace_faststat) })
            assertNull(find { it.className == "android.widget.Switch" || it.isEditable })
        }
    }
}
