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
        var scrollAction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        while (SystemClock.elapsedRealtime() < deadline) {
            find(predicate)?.let { return it }
            if (scroll) find { it.isScrollable }?.let {
                if (!it.performAction(scrollAction)) scrollAction = if (scrollAction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            SystemClock.sleep(50)
        }
        throw AssertionError("Userspace Linux UI element did not appear")
    }

    private fun button(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable && current.parent != null) current = current.parent
        return current
    }

    private fun matches(node: AccessibilityNodeInfo, label: String) =
        node.text?.toString() == label || node.contentDescription?.toString() == label

    private fun control(label: String, enabled: Boolean): AccessibilityNodeInfo = button(waitFor(scroll = true) {
        matches(it, label) && button(it).isClickable && button(it).isEnabled == enabled
    })

    private fun waitUntilGone(predicate: (AccessibilityNodeInfo) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (find(predicate) != null) {
            check(SystemClock.elapsedRealtime() < deadline) { "Userspace Linux UI element did not disappear" }
            SystemClock.sleep(50)
        }
    }

    private fun click(label: Int) = click(context.getString(label))
    private fun click(label: String) {
        assertTrue(control(label, enabled = true).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun input(address: String) {
        assertTrue(waitFor { it.isEditable }.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, address)
        }))
    }

    private fun addServer(address: String) {
        click(R.string.userspace_dns_add_server)
        input(address)
        click(R.string.userspace_dns_add)
        waitFor(scroll = true) { !it.isEditable && it.text?.toString() == address }
    }

    private fun awaitPolicy(service: IRuntimeService, mode: String, servers: String) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (true) {
            val policy = JSONObject(service.userspaceLinuxSettingsJson)
            if (policy.getString("dnsMode") == mode && policy.getString("dnsServers") == servers) return
            check(SystemClock.elapsedRealtime() < deadline) { "DNS policy was not saved" }
            SystemClock.sleep(50)
        }
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
                assertNull(find { matches(it, context.getString(R.string.userspace_dns_add_server)) })
                click(R.string.userspace_dns_custom)
                assertNotNull(control(context.getString(R.string.userspace_dns_add_server), enabled = true))
                assertNull(find { it.isEditable })
                assertNotNull(control(context.getString(R.string.save), enabled = false))
                click(R.string.userspace_dns_add_server)
                assertNotNull(control(context.getString(R.string.userspace_dns_add), enabled = false))
                for (invalid in listOf("invalid.example", "192.0.2.53 2001:db8::53", "192.0.2.53,192.0.2.53")) {
                    input(invalid)
                    waitFor { it.text?.toString() == context.getString(R.string.userspace_dns_invalid) }
                    assertNotNull(control(context.getString(R.string.userspace_dns_add), enabled = false))
                }
                assertEquals("android", JSONObject(service.userspaceLinuxSettingsJson).getString("dnsMode"))
                input("2001:db8::53")
                scenario.recreate()
                waitFor { it.isEditable && it.text.toString().contains("2001:db8::53") }
                click(R.string.cancel)
                waitUntilGone { it.isEditable }
                assertNull(find { it.isEditable || it.text?.toString() == "2001:db8::53" })
                addServer("192.0.2.53")
                click(R.string.userspace_dns_add_server)
                input("192.0.2.53")
                waitFor { it.text?.toString() == context.getString(R.string.userspace_dns_duplicate) }
                assertNotNull(control(context.getString(R.string.userspace_dns_add), enabled = false))
                click(R.string.cancel)
                waitUntilGone { it.isEditable }
                scenario.recreate()
                waitFor(scroll = true) { it.text?.toString() == "192.0.2.53" }
                click(R.string.userspace_dns_android)
                waitUntilGone { matches(it, context.getString(R.string.userspace_dns_add_server)) }
                assertNull(find { it.text?.toString() == "192.0.2.53" || matches(it, context.getString(R.string.userspace_dns_add_server)) })
                click(R.string.userspace_dns_custom)
                waitFor(scroll = true) { it.text?.toString() == "192.0.2.53" }
                click(R.string.cancel)
                assertEquals("android", JSONObject(service.userspaceLinuxSettingsJson).getString("dnsMode"))
            }
            launch(UserspaceLinuxSettingsActivity.DNS).use {
                waitFor { it.className == "android.widget.RadioButton" }
                click(R.string.userspace_dns_custom)
                addServer("192.0.2.53")
                addServer("2001:db8::53")
                click(R.string.save)
                awaitPolicy(service, "custom", "192.0.2.53\n2001:db8::53")
            }
            assertFalse(service.setUserspaceLinuxDns("custom", "hostname.invalid").isEmpty())
            assertEquals("192.0.2.53\n2001:db8::53", JSONObject(service.userspaceLinuxSettingsJson).getString("dnsServers"))
        } finally {
            assertEquals("", service.setUserspaceLinuxDns(original.getString("dnsMode"), original.getString("dnsServers")))
        }
    }

    @Test fun dnsServerLimitAndDeletionApplyToTheDraftBeforeSaving() = withRuntime { service ->
        val original = JSONObject(service.userspaceLinuxSettingsJson)
        val ipv6 = "2001:0db8:1234:5678:90ab:cdef:1234:5678"
        try {
            assertEquals("", service.setUserspaceLinuxDns("android", ""))
            launch(UserspaceLinuxSettingsActivity.DNS).use {
                waitFor { it.className == "android.widget.RadioButton" }
                click(R.string.userspace_dns_custom)
                addServer("192.0.2.53")
                addServer(ipv6)
                addServer("198.51.100.53")
                assertNotNull(control(context.getString(R.string.userspace_dns_add_server), enabled = false))
                waitFor(scroll = true) { it.text?.toString() == context.getString(R.string.userspace_dns_limit) }
                assertNull(find { it.isEditable })
                assertEquals("android", JSONObject(service.userspaceLinuxSettingsJson).getString("dnsMode"))
                val remove = control(context.getString(R.string.userspace_dns_remove_server, ipv6), enabled = true)
                val bounds = android.graphics.Rect().also(remove::getBoundsInScreen)
                val minimumTouchSize = (48 * context.resources.displayMetrics.density).toInt()
                assertTrue(bounds.width() >= minimumTouchSize && bounds.height() >= minimumTouchSize)
                assertTrue(remove.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                waitUntilGone { it.text?.toString() == ipv6 }
                assertNull(find { it.text?.toString() == ipv6 })
                assertNotNull(control(context.getString(R.string.userspace_dns_add_server), enabled = true))
                addServer("203.0.113.53")
                assertNotNull(control(context.getString(R.string.userspace_dns_add_server), enabled = false))
                click(R.string.save)
                awaitPolicy(service, "custom", "192.0.2.53\n198.51.100.53\n203.0.113.53")
            }
            launch(UserspaceLinuxSettingsActivity.DNS).use {
                for (address in listOf("192.0.2.53", "198.51.100.53", "203.0.113.53")) {
                    click(context.getString(R.string.userspace_dns_remove_server, address))
                }
                assertNotNull(control(context.getString(R.string.userspace_dns_add_server), enabled = true))
                assertNotNull(control(context.getString(R.string.save), enabled = false))
                click(R.string.cancel)
                assertEquals("192.0.2.53\n198.51.100.53\n203.0.113.53", JSONObject(service.userspaceLinuxSettingsJson).getString("dnsServers"))
            }
        } finally {
            assertEquals("", service.setUserspaceLinuxDns(original.getString("dnsMode"), original.getString("dnsServers")))
        }
    }

    @Test fun runtimeInformationIsReadOnly() {
        ActivityScenario.launch(UserspaceLinuxDiagnosticsActivity::class.java).use {
            waitFor(scroll = true) { it.text?.toString() == context.getString(R.string.userspace_loader) }
            assertNotNull(find { it.text?.toString() == context.getString(R.string.userspace_faststat) })
            assertNull(find { it.className == "android.widget.Switch" || it.isEditable })
        }
    }
}
