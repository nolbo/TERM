package dev.terminal.app

import android.content.Intent
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FloatingToolbarTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    @Test fun profileActionsShowPlainTooltipsOnLongPressAndMouseHoverWithoutExecuting() {
        val store = SessionProfiles(context)
        val profile = store.duplicate(store.builtIns().first()).copy(name = "Tooltip profile fixture")
        store.save(profile)
        try {
            ActivityScenario.launch<ProfileEditActivity>(Intent(context, ProfileEditActivity::class.java)
                .putExtra("profileId", profile.id)).use {
                listOf(R.string.profile_save, R.string.profile_duplicate, R.string.profile_delete).forEach { label ->
                    assertTooltipGestures(label)
                    assertEquals(profile, store.find(profile.id))
                    assertNotNull(find { it.text?.toString() == context.getString(R.string.profile_name) })
                    assertNull(find { it.text?.toString() == context.getString(R.string.profile_delete_message, profile.name) })
                }
            }
        } finally { store.delete(profile.id) }
    }

    @Test fun storageActionsIncludingDisabledDeletionShowPlainTooltipsWithoutExecuting() {
        val store = SessionProfiles(context)
        val profile = store.duplicate(store.builtIns().first()).copy(name = "Tooltip storage fixture")
        store.save(profile)
        try {
            ActivityScenario.launch<StorageDetailActivity>(StorageDetailActivity.intent(context,
                profile.runtime.wireName, profile.storageId)).use {
                listOf(R.string.storage_rename, R.string.storage_delete).forEach { label ->
                    assertTooltipGestures(label)
                    assertNull(find { it.isEditable })
                    assertNull(find { it.text?.toString() == context.getString(R.string.storage_delete_confirm, profile.storageId) })
                }
            }
        } finally { store.delete(profile.id) }
    }

    private fun assertTooltipGestures(labelId: Int) {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val label = context.getString(labelId)
        await { find { it.contentDescription?.toString() == label && it.isVisibleToUser } != null }
        assertNull("Labels belong in tooltips, not on the toolbar", find { it.text?.toString() == label })
        var button = requireNotNull(find { it.contentDescription?.toString() == label })
        while (!button.isClickable) {
            button = requireNotNull(button.parent)
        }
        val bounds = Rect().also(button::getBoundsInScreen)
        val minTouchSize = 48 * context.resources.displayMetrics.density
        assertTrue("48dp touch target width", bounds.width() >= minTouchSize - 1)
        assertTrue("48dp touch target height", bounds.height() >= minTouchSize - 1)
        val downTime = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, bounds, downTime, mouse = false)
        try {
            await { find { it.text?.toString() == label && it.isVisibleToUser } != null }
        } finally { inject(MotionEvent.ACTION_UP, bounds, downTime, mouse = false) }
        await { find { it.text?.toString() == label && it.isVisibleToUser } == null }
        inject(MotionEvent.ACTION_HOVER_ENTER, bounds, SystemClock.uptimeMillis(), mouse = true)
        try {
            await { find { it.text?.toString() == label && it.isVisibleToUser } != null }
        } finally { inject(MotionEvent.ACTION_HOVER_EXIT, bounds, SystemClock.uptimeMillis(), mouse = true) }
        await { find { it.text?.toString() == label && it.isVisibleToUser } == null }
    }

    private fun inject(action: Int, bounds: Rect, downTime: Long, mouse: Boolean) {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = if (mouse) MotionEvent.TOOL_TYPE_MOUSE else MotionEvent.TOOL_TYPE_FINGER
        }
        val coordinates = MotionEvent.PointerCoords().apply {
            x = bounds.exactCenterX()
            y = bounds.exactCenterY()
            pressure = 1f
            size = 1f
        }
        MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, arrayOf(properties),
            arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0,
            if (mouse) InputDevice.SOURCE_MOUSE else InputDevice.SOURCE_TOUCHSCREEN, 0).also {
            try { assertTrue(automation.injectInputEvent(it, true)) } finally { it.recycle() }
        }
    }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        instrumentation.waitForIdleSync()
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (index in 0 until node.childCount) walk(node.getChild(index))?.let { return it }
            return null
        }
        walk(automation.rootInActiveWindow)?.let { return it }
        for (window in automation.windows) walk(window.root)?.let { return it }
        return null
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for tooltip interaction" }
            SystemClock.sleep(50)
        }
    }
}
