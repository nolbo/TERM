package dev.terminal.app

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.DroidTheme
import dev.terminal.app.ui.NewSessionSheet
import dev.terminal.app.ui.TerminalHapticFeedbackProvider
import dev.terminal.runtime.session.SessionProfile
import dev.terminal.runtime.session.SessionRuntime
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileOrderUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun editFabSupportsMultilineDragCancelRepeatedReorderAndRecreation() = withProfiles(
        listOf("Multiline profile with a long name ".repeat(3), "Short profile"),
    ) { store, profiles ->
        val original = store.all().map { it.id }
        val first = profiles[0]
        val second = profiles[1]
        ActivityScenario.launch(ProfilesActivity::class.java).use { activity ->
            node(context.getString(R.string.toolbar_edit))
            assertNull(find { it.contentDescription?.toString() == moveLabel(first) })
            click(context.getString(R.string.toolbar_edit))
            settle()
            val firstHandle = bounds(node(moveLabel(first)))
            val secondHandle = bounds(node(moveLabel(second)))
            assertTrue("Long names retain their natural row height", rowBounds(first.name).height() > rowBounds(second.name).height())
            click(first.name)
            assertNull("Order editing does not open the profile editor", find { it.hasLabel(context.getString(R.string.profile_name)) })
            drag(firstHandle, secondHandle.exactCenterY(), cancel = true) {
                assertEquals("Preview must not persist before drop", original, store.all().map { it.id })
            }
            assertEquals(original, store.all().map { it.id })
            drag(bounds(node(moveLabel(first))), bounds(node(moveLabel(second))).exactCenterY())
            val swapped = original.toMutableList().apply { add(1, removeAt(0)) }
            await { store.all().map { it.id } == swapped }
            assertEquals(swapped, SessionProfiles(context).all().map { it.id })

            // A second gesture on the same handle must use its new slot, not a stale closure.
            drag(bounds(node(moveLabel(first))), bounds(node(moveLabel(second))).exactCenterY())
            await { store.all().map { it.id } == original }
            activity.recreate()
            node(context.getString(R.string.toolbar_done))
            assertNotNull(node(moveLabel(first)))
            assertEquals(original, SessionProfiles(context).all().map { it.id })

            val handle = node(moveLabel(first))
            val down = handle.actionList.single { it.label?.toString() == context.getString(R.string.toolbar_move_down) }
            assertTrue(handle.performAction(down.id))
            await { store.all().map { it.id } == swapped }
            click(context.getString(R.string.toolbar_done))
            await { find { it.contentDescription?.toString() == moveLabel(first) } == null }
            assertTrue(rowBounds(second.name).top < rowBounds(first.name).top)
        }

        ActivityScenario.launch(ProfilesActivity::class.java).use { activity ->
            activity.onActivity { it.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    NewSessionSheet(store.all(), onSelectProfile = {}, onAddProfile = {}, onDismiss = {})
                } }
            } }
            settle()
            node(first.name)
            assertTrue("The empty-screen picker uses the same saved order", rowBounds(second.name).top < rowBounds(first.name).top)
        }

        val added = first.copy(id = UUID.randomUUID().toString(), name = "Appended profile")
        try {
            store.save(added)
            assertEquals(added.id, store.all().last().id)
            store.delete(second.id)
            assertEquals(original.filter { it != second.id } + added.id, store.all().map { it.id })
        } finally { store.delete(added.id) }
    }

    @Test fun draggingAtTheViewportEdgeScrollsToOffscreenProfiles() = withProfiles(
        List(14) { "Scroll profile ${it + 1}" },
    ) { store, profiles ->
        ActivityScenario.launch(ProfilesActivity::class.java).use {
            click(context.getString(R.string.toolbar_edit))
            settle()
            val start = bounds(node(moveLabel(profiles.first())))
            val viewport = bounds(requireNotNull(find { it.isScrollable }))
            drag(start, viewport.bottom - 8f, holdAtEnd = 2000)
            await { store.all().indexOfFirst { it.id == profiles.first().id } >= 7 }
            assertEquals(profiles.map { it.id }.toSet(), store.all().take(profiles.size).map { it.id }.toSet())
        }
    }

    private fun withProfiles(names: List<String>, test: (SessionProfiles, List<SessionProfile>) -> Unit) {
        val preferences = context.getSharedPreferences(PROFILE_ORDER_PREFERENCES, Context.MODE_PRIVATE)
        val previousOrder = preferences.getString(KEY_PROFILE_ORDER, null)
        val store = SessionProfiles(context)
        val original = store.all()
        val template = original.firstOrNull { it.runtime == SessionRuntime.NATIVE }
            ?: store.builtIns().first { it.runtime == SessionRuntime.NATIVE }
        val profiles = names.map { template.copy(id = UUID.randomUUID().toString(), name = it) }
        try {
            profiles.forEach { store.save(it) }
            store.reorder(profiles.map { it.id } + original.map { it.id })
            test(store, profiles)
        } finally {
            profiles.forEach { store.delete(it.id) }
            preferences.edit().apply {
                if (previousOrder == null) remove(KEY_PROFILE_ORDER) else putString(KEY_PROFILE_ORDER, previousOrder)
            }.commit()
        }
    }

    private fun AccessibilityNodeInfo.hasLabel(label: String) =
        text?.toString()?.lineSequence()?.any { it == label } == true || contentDescription?.toString() == label
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || !node.isVisibleToUser) return null
            if (predicate(node)) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun node(label: String): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        await { result = find { it.hasLabel(label) }; result != null }
        return requireNotNull(result)
    }
    private fun click(label: String) {
        var target: AccessibilityNodeInfo? = node(label)
        while (target != null && !target.isClickable) target = target.parent
        assertTrue(requireNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        settle()
    }
    private fun rowBounds(label: String): Rect {
        var target = node(label)
        while (!target.isClickable && target.parent != null) target = target.parent
        return bounds(target)
    }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun moveLabel(profile: SessionProfile) = context.getString(R.string.toolbar_move, profile.name)
    private fun settle() { SystemClock.sleep(800); instrumentation.waitForIdleSync() }
    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 7000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (check()) return
            SystemClock.sleep(100)
        }
        error("Profile order UI did not reach the expected state")
    }
    private fun drag(start: Rect, endY: Float, cancel: Boolean = false, holdAtEnd: Long = 0, duringDrag: (() -> Unit)? = null) {
        val downTime = SystemClock.uptimeMillis()
        for (step in 0..20) {
            val action = when (step) {
                0 -> MotionEvent.ACTION_DOWN
                20 -> if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                start.exactCenterX(), start.exactCenterY() + (endY - start.exactCenterY()) * step / 20, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            if (step == 19) {
                duringDrag?.invoke()
                if (holdAtEnd > 0) SystemClock.sleep(holdAtEnd)
            }
            SystemClock.sleep(20)
        }
        settle()
    }
}
