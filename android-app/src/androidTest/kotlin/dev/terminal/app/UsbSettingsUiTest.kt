package dev.terminal.app

import android.os.SystemClock
import android.graphics.Rect
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Display fixtures only; no physical USB permission, transfer or Apple operation is performed. */
@RunWith(AndroidJUnit4::class)
class UsbSettingsUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun label(id: Int) = instrumentation.targetContext.getString(id)
    private fun fixture(waiting: Boolean = false) = UsbSettingsSnapshot.parse(JSONObject("""{
        "supported":true,"devices":[{"id":"fixture-phone","vendor":1452,"product":4776,"iphone":true}],
        "requests":[{"request":"fixture-request","session":"fixture-session","runtime":"NATIVE",
          "device":"fixture-phone","interface":-1,"iphone":"afc-get","scope":"/approved-file","waitingForAndroid":$waiting}],
        "grants":[{"session":"fixture-session","runtime":"NATIVE","device":"fixture-phone","interface":-1,"iphone":"info","active":true}],
        "diagnostics":{"rawConnections":0,"token":"secret-sentinel","recentErrors":["secret-sentinel"]}
    }"""))

    private fun find(text: String): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && (node.text?.toString() == text || node.contentDescription?.toString() == text)) return node
            for (i in 0 until node.childCount) search(node.getChild(i))?.let { return it }
            return null
        }
        return search(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun waitFor(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            find(text)?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("Missing UI text: $text")
    }
    private fun click(text: String) {
        var node: AccessibilityNodeInfo? = waitFor(text)
        while (node != null) {
            if (node.isClickable) { assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); instrumentation.waitForIdleSync(); return }
            node = node.parent
        }
        throw AssertionError("Not clickable: $text")
    }
    private fun waitAbsent(text: String) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            if (find(text) == null) return
            SystemClock.sleep(50)
        }
        assertNull(find(text))
    }
    private fun assertExpansion(text: String, expanded: Boolean) {
        var node: AccessibilityNodeInfo? = waitFor(text)
        while (node != null && node.stateDescription == null) node = node.parent
        assertEquals(label(if (expanded) R.string.usb_expanded else R.string.usb_collapsed), node?.stateDescription?.toString())
    }
    private fun topOf(text: String): Int = Rect().also { waitFor(text).getBoundsInScreen(it) }.top
    private fun tap(text: String, last: Boolean = false) {
        waitFor(text)
        val matches = mutableListOf<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.text?.toString() == text) matches += node
            for (i in 0 until node.childCount) collect(node.getChild(i))
        }
        collect(instrumentation.uiAutomation.rootInActiveWindow)
        val bounds = Rect()
        (if (last) matches.last() else matches.first()).getBoundsInScreen(bounds)
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        instrumentation.waitForIdleSync()
    }
    private fun scrollTo(text: String) {
        fun scroll(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
            for (i in 0 until node.childCount) if (scroll(node.getChild(i))) return true
            return false
        }
        repeat(12) {
            instrumentation.waitForIdleSync()
            if (find(text) != null) return
            scroll(instrumentation.uiAutomation.rootInActiveWindow)
            // Accessibility scrolling uses Compose's animated scroll, which outlives main-loop idle.
            SystemClock.sleep(500)
        }
        waitFor(text)
    }

    @Test fun approvalCancellationDenialAndExpirationStayDistinct() {
        val snapshot = mutableStateOf(fixture())
        val commands = mutableListOf<UsbSettingsCommand>()
        ActivityScenario.launch(UsbDevicesActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { DroidTheme {
                UsbSettingsScreen(snapshot.value, true, false, "", {}, { commands += it }, {}, {})
            } } }
            click(label(R.string.usb_approve))
            waitFor("/approved-file")
            click(label(R.string.cancel))
            assertTrue(commands.isEmpty())
            click(label(R.string.usb_deny))
            assertEquals(listOf(UsbSettingsCommand(UsbSettingsAction.DENY, "fixture-request")), commands)
            commands.clear()
            click(label(R.string.usb_approve))
            waitFor(label(R.string.cancel))
            instrumentation.runOnMainSync { snapshot.value = snapshot.value.copy(requests = emptyList()) }
            waitFor(label(R.string.usb_no_requests_title))
            assertNull(find(label(R.string.cancel)))
            assertTrue(commands.isEmpty())
        }
    }

    @Test fun androidPermissionWaitingPreventsApprovalButAllowsDenial() {
        val commands = mutableListOf<UsbSettingsCommand>()
        ActivityScenario.launch(UsbDevicesActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { DroidTheme {
                UsbSettingsScreen(fixture(true), true, false, "", {}, { commands += it }, {}, {})
            } } }
            waitFor(label(R.string.usb_waiting_permission))
            // Compose exposes the child Text as enabled even when the containing button is disabled.
            // Verify the actual touch behavior rather than relying on that accessibility projection.
            tap(label(R.string.usb_approve))
            assertTrue(commands.isEmpty())
            assertNull(find(label(R.string.cancel)))
            click(label(R.string.usb_deny))
            assertEquals(UsbSettingsAction.DENY, commands.single().action)
        }
    }

    @Test fun confirmedApprovalSendsOnlyTheSelectedRequest() {
        val commands = mutableListOf<UsbSettingsCommand>()
        ActivityScenario.launch(UsbDevicesActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { DroidTheme {
                UsbSettingsScreen(fixture(), true, false, "", {}, { commands += it }, {}, {})
            } } }
            click(label(R.string.usb_approve))
            waitFor(label(R.string.cancel))
            assertTrue(commands.isEmpty())
            tap(label(R.string.usb_approve), last = true)
            assertEquals(listOf(UsbSettingsCommand(UsbSettingsAction.APPROVE, "fixture-request")), commands)
        }
    }

    @Test fun deviceDetailsAndFolderRemovalAreSeparateInteractions() {
        val commands = mutableListOf<UsbSettingsCommand>()
        val data = fixture().copy(requests = emptyList(), grants = emptyList(), storage = listOf(UsbStorageUi("fixture-root", "Test Drive", true, false)))
        ActivityScenario.launch(UsbDevicesActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { DroidTheme {
                UsbSettingsScreen(data, true, false, "", {}, { commands += it }, {}, {})
            } } }
            val deviceId = instrumentation.targetContext.getString(R.string.usb_device_id, "fixture-phone")
            assertNull(find(deviceId))
            click(label(R.string.usb_device_iphone))
            waitFor(deviceId)
            click(label(R.string.usb_device_iphone))
            waitAbsent(deviceId)
            scrollTo(label(R.string.usb_remove_storage))
            click(label(R.string.usb_remove_storage))
            waitFor(label(R.string.usb_remove_storage_confirmation))
            assertTrue(commands.isEmpty())
            click(label(R.string.usb_confirm_remove))
            assertEquals(UsbSettingsCommand(UsbSettingsAction.REMOVE_STORAGE, "fixture-root"), commands.single())
        }
    }

    @Test fun deviceExpansionReversesAndRestoresCollapsedRowSpacing() {
        val first = fixture().devices.single()
        val data = fixture().copy(requests = emptyList(), grants = emptyList(), devices = listOf(
            first, first.copy(id = "fixture-serial", iphone = false, cdc = true),
        ))
        ActivityScenario.launch(UsbDevicesActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { DroidTheme {
                UsbSettingsScreen(data, true, false, "", {}, {}, {}, {})
            } } }
            val header = label(R.string.usb_device_iphone)
            val nextHeader = label(R.string.usb_device_serial)
            val details = instrumentation.targetContext.getString(R.string.usb_device_id, first.id)
            val collapsedTop = topOf(nextHeader)
            assertExpansion(header, false)
            click(header)
            waitFor(details)
            assertExpansion(header, true)
            // Reverse the running collapse rather than waiting for its outgoing content to disappear.
            click(header)
            click(header)
            waitFor(details)
            assertExpansion(header, true)
            click(header)
            waitAbsent(details)
            assertExpansion(header, false)
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (SystemClock.elapsedRealtime() < deadline && topOf(nextHeader) != collapsedTop) SystemClock.sleep(50)
            assertEquals("Collapsing must restore the original row spacing", collapsedTop, topOf(nextHeader))
        }
    }

    @Test fun revocationRequiresConfirmationAndDiagnosticsDoNotRenderJson() {
        val commands = mutableListOf<UsbSettingsCommand>()
        val data = fixture().copy(requests = emptyList())
        ActivityScenario.launch(UsbDevicesActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { DroidTheme {
                UsbSettingsScreen(data, true, false, "", {}, { commands += it }, {}, {})
            } } }
            scrollTo(label(R.string.usb_revoke))
            click(label(R.string.usb_revoke))
            waitFor(label(R.string.usb_revoke_confirmation))
            click(label(R.string.cancel))
            assertTrue(commands.isEmpty())
            click(label(R.string.usb_revoke))
            click(label(R.string.usb_confirm_remove))
            assertEquals(UsbSettingsCommand(UsbSettingsAction.REVOKE, "fixture-session"), commands.single())
            waitAbsent(label(R.string.cancel))
            scrollTo(label(R.string.usb_diagnostics))
            click(label(R.string.usb_diagnostics))
            scrollTo(label(R.string.usb_diag_raw))
            assertNull(find("secret-sentinel"))
        }
    }

}
