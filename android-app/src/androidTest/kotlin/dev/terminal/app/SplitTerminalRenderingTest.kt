package dev.terminal.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.DroidTheme
import dev.terminal.app.ui.SplitDirection
import dev.terminal.app.ui.TerminalPaneState
import dev.terminal.app.ui.splitPaneExtent
import dev.terminal.emulator.TerminalSession
import dev.terminal.view.TerminalView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.WeakHashMap
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class SplitTerminalRenderingTest {
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue(message, condition())
    }

    @Test fun dividerDragResizesOnlyAffectedPtysAndKeepsFontsScrollPaletteAndImagesIndependent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resizeEvents = Array(2) { mutableListOf<Pair<Int, Int>>() }
        val backends = Array(2) { index -> RemoteTerminalBackend(write = {},
            resize = { columns, rows -> resizeEvents[index] += columns to rows }) }
        lateinit var sessions: Array<TerminalSession>
        instrumentation.runOnMainSync {
            sessions = Array(2) { index -> TerminalSession("", "/", emptyArray(), emptyArray(), 1000,
                TerminalCallbacks {}, backends[index]) }
        }
        val positions = WeakHashMap<TerminalSession, TerminalView.ScrollPosition>()
        val state = mutableStateOf(TerminalPaneState("0", direction = SplitDirection.SideBySide))
        val positiveSize = mutableStateOf(true)
        var collapseThresholds = 0
        var unsplitRequests = 0
        val collapseRequests = mutableListOf<Int>()
        lateinit var layout: TerminalPaneLayout
        var mounted = false
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    DroidTheme {
                        val parent = rememberCompositionContext()
                        val width = with(LocalDensity.current) { (if (positiveSize.value) 1000 else 0).toDp() }
                        val height = with(LocalDensity.current) { (if (positiveSize.value) 600 else 0).toDp() }
                        AndroidView(factory = { paneContext ->
                            TerminalPaneLayout(paneContext,
                                createTerminal = { index -> TerminalView(paneContext, null).apply {
                                    setTerminalViewClient(TerminalInputCallbacks(isSelected = { state.value.focus == index }))
                                    setTextSize(if (index == 0) 20 else 30)
                                    setSessionScrollPositions(positions)
                                } },
                                bindTerminal = { _, view, id ->
                                    val session = sessions[id.toInt()]
                                    if (view.currentSession !== session) view.attachSession(session)
                                },
                                releaseTerminal = { it.saveSessionScrollPosition(); it.attachSession(null) },
                                focusPane = { state.value = state.value.copy(focus = it) },
                                changeFraction = { state.value = state.value.copy(fraction = it) },
                                onUnsplit = { unsplitRequests++ },
                                onCollapse = { collapseRequests += it },
                                onCollapseThreshold = { collapseThresholds++ },
                                parentCompositionContext = parent).also { layout = it; mounted = true }
                        }, update = { it.update(state.value, 0, 0, 0xff888888.toInt()) },
                            onRelease = { it.release() }, modifier = Modifier.requiredSize(width, height))
                    }
                } }
                fun awaitGeometry() = await("The window-attached scaffold reaches its requested pane geometry") {
                    var ready = false
                    scenario.onActivity {
                        if (mounted) {
                            val gutter = (12 * context.resources.displayMetrics.density).roundToInt()
                            val horizontal = state.value.direction == SplitDirection.SideBySide
                            val total = if (horizontal) 1000 else 600
                            val expected = splitPaneExtent(total, gutter, state.value.fraction,
                                ((if (horizontal) 96 else 72) * context.resources.displayMetrics.density).roundToInt())
                            val first = layout.terminal(0); val second = layout.terminal(1)
                            ready = layout.width == 1000 && layout.height == 600 && first != null && second != null &&
                                (if (horizontal) first.width == expected && second.width == 1000 - gutter - expected &&
                                    first.height == 600 && second.height == 600
                                else first.height == expected && second.height == 600 - gutter - expected &&
                                    first.width == 1000 && second.width == 1000) &&
                                first.currentSession === sessions[0] && second.currentSession === sessions[1]
                        }
                    }
                    ready
                }
                fun append(index: Int, output: String) {
                    val bytes = output.toByteArray(); sessions[index].emulator.append(bytes, bytes.size)
                    layout.terminal(index)!!.onScreenUpdated()
                }
                fun awaitSingle(id: Int) = await("Session $id binds to the unchanged full-size slot") {
                    var ready = false
                    scenario.onActivity {
                        ready = mounted && layout.terminal(0)?.let {
                            it.width == 1000 && it.height == 600 && it.currentSession === sessions[id]
                        } == true
                    }
                    ready
                }
                awaitSingle(0)
                lateinit var singleSlot: TerminalView
                scenario.onActivity {
                    singleSlot = layout.terminal(0)!!
                    state.value = state.value.copy(first = "1")
                }
                awaitSingle(1)
                scenario.onActivity {
                    assertSame("Changing only a session id retains its TerminalView", singleSlot, layout.terminal(0))
                    state.value = TerminalPaneState("0", "1", direction = SplitDirection.SideBySide)
                }
                awaitGeometry()
                scenario.onActivity { assertSame("Splitting retains the first renderer", singleSlot, layout.terminal(0)) }
                var handle: AccessibilityNodeInfo? = null
                val handleLabel = context.getString(R.string.terminal_split_resize)
                val sideBySideLabel = context.getString(R.string.terminal_split_side_by_side)
                fun paneMenuVisible(): Boolean {
                    instrumentation.uiAutomation.clearCache()
                    fun find(node: AccessibilityNodeInfo?): Boolean {
                        if (node == null) return false
                        if (node.isVisibleToUser && node.contentDescription?.toString() == sideBySideLabel) return true
                        return (0 until node.childCount).any { find(node.getChild(it)) }
                    }
                    return find(instrumentation.uiAutomation.rootInActiveWindow)
                }
                fun findHandle(): AccessibilityNodeInfo? {
                    instrumentation.uiAutomation.clearCache()
                    fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                        if (node == null) return null
                        if (node.isVisibleToUser && node.contentDescription?.toString() == handleLabel &&
                            node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id }) return node
                        for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
                        return null
                    }
                    return find(instrumentation.uiAutomation.rootInActiveWindow)
                }
                await("The Material pane drag handle exposes setProgress") {
                    handle = findHandle()
                    handle != null
                }
                assertTrue(handle!!.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                    Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 0.45f) }))
                await("The Material handle updates the pane ratio") {
                    var changed = false; scenario.onActivity { changed = state.value.fraction == 0.45f }; changed
                }
                awaitGeometry()
                lateinit var first: TerminalView; lateinit var second: TerminalView
                var oldWidth = 0
                scenario.onActivity {
                    first = layout.terminal(0)!!; second = layout.terminal(1)!!
                    assertTrue(first.mEmulator.mColumns > second.mEmulator.mColumns)
                    append(0, (1..100).joinToString("\r\n") { "first-$it" })
                    append(1, (1..100).joinToString("\r\n") { "second-$it" })
                    first.topRow = -15; second.topRow = -8
                    oldWidth = first.width
                    val center = oldWidth + 6 * context.resources.displayMetrics.density
                    val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, center, 200f, 0)
                    assertTrue(layout.onInterceptTouchEvent(down)); down.recycle()
                    val move = MotionEvent.obtain(0, 20, MotionEvent.ACTION_MOVE, center - 150f, 200f, 0)
                    assertTrue(layout.onTouchEvent(move)); move.recycle()
                    val up = MotionEvent.obtain(0, 40, MotionEvent.ACTION_UP, center - 150f, 200f, 0)
                    assertTrue(layout.onTouchEvent(up)); up.recycle()
                }
                awaitGeometry()
                assertFalse("Dragging the divider never opens its menu", paneMenuVisible())
                var restingDimensions = emptyList<Pair<Int, Int>>()
                var restingResizes = emptyList<Int>()
                var restingFraction = 0f
                scenario.onActivity {
                    first.updateSize(); second.updateSize()
                    restingDimensions = listOf(first.width to first.height, second.width to second.height)
                    restingResizes = resizeEvents.map { it.size }
                    restingFraction = state.value.fraction
                }
                fun assertHandleOnlyChangesItsPresentation() = scenario.onActivity {
                    assertEquals("Holding or releasing the handle keeps both pane sizes", restingDimensions,
                        listOf(first.width to first.height, second.width to second.height))
                    assertEquals("An unmoved handle keeps the split ratio", restingFraction, state.value.fraction, 0f)
                    assertEquals("Handle interaction never resizes either PTY", restingResizes, resizeEvents.map { it.size })
                }
                val inspectHandle = InstrumentationRegistry.getArguments().getString("split.inspectHandleMs")
                    ?.toLongOrNull()?.coerceIn(0, 8_000) ?: 0
                for (ending in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) {
                    val downTime = SystemClock.uptimeMillis()
                    scenario.onActivity {
                        val center = first.width + 6 * context.resources.displayMetrics.density
                        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, center, layout.height / 2f, 0)
                        try { assertTrue(layout.onInterceptTouchEvent(down)) } finally { down.recycle() }
                    }
                    // Leave the UI thread free for several frames of the Material interaction motion.
                    SystemClock.sleep(300)
                    assertHandleOnlyChangesItsPresentation()
                    if (inspectHandle > 0 && ending == MotionEvent.ACTION_CANCEL) {
                        android.util.Log.i("SplitHandleInspection", "Held")
                        SystemClock.sleep(inspectHandle)
                    }
                    scenario.onActivity {
                        val center = first.width + 6 * context.resources.displayMetrics.density
                        val endX = if (ending == MotionEvent.ACTION_UP) center + 2 * context.resources.displayMetrics.density else center
                        if (ending == MotionEvent.ACTION_UP) {
                            val move = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, endX, layout.height / 2f, 0)
                            try { assertTrue(layout.onTouchEvent(move)) } finally { move.recycle() }
                        }
                        val end = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), ending, endX, layout.height / 2f, 0)
                        try { assertTrue(layout.onTouchEvent(end)) } finally { end.recycle() }
                    }
                    SystemClock.sleep(300)
                    assertHandleOnlyChangesItsPresentation()
                    if (ending == MotionEvent.ACTION_UP) {
                        await("A sub-slop handle tap opens its menu") { paneMenuVisible() }
                        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                        await("Back dismisses the handle menu") { !paneMenuVisible() }
                    } else assertFalse("Cancel never opens the handle menu", paneMenuVisible())
                    if (inspectHandle > 0 && ending == MotionEvent.ACTION_CANCEL) {
                        android.util.Log.i("SplitHandleInspection", "Released")
                        SystemClock.sleep(inspectHandle)
                    }
                }
                val restingState = state.value
                fun awaitPreview(target: Int?) = await("Only collapse candidate $target has a foreground preview") {
                    var ready = false
                    scenario.onActivity {
                        val alphas = listOf(first, second).map { (it.parent as android.widget.FrameLayout).foreground?.alpha ?: 0 }
                        ready = alphas.indices.all { if (it == target) alphas[it] > 0 else alphas[it] == 0 }
                    }
                    ready
                }
                for (direction in SplitDirection.entries) {
                    scenario.onActivity { state.value = restingState.copy(direction = direction, fraction = 0.5f) }
                    awaitGeometry()
                    for (edge in 0..1) {
                        val horizontal = direction == SplitDirection.SideBySide
                        val density = context.resources.displayMetrics.density
                        val length = if (horizontal) 1000 else 600
                        val cross = (if (horizontal) 600 else 1000) / 2f
                        var downTime = 0L
                        fun event(action: Int, coordinate: Float) = scenario.onActivity {
                            val value = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                                if (horizontal) coordinate else cross, if (horizontal) cross else coordinate, 0)
                            try {
                                assertTrue("$direction edge=$edge handles action $action", if (action == MotionEvent.ACTION_DOWN)
                                    layout.onInterceptTouchEvent(value) else layout.onTouchEvent(value))
                            } finally { value.recycle() }
                        }
                        fun awaitBoundary(coordinate: Float) = await("$direction edge=$edge boundary follows coordinate $coordinate") {
                            val bounds = android.graphics.Rect()
                            val node = findHandle()
                            node?.getBoundsInScreen(bounds)
                            var origin = 0
                            scenario.onActivity {
                                val location = IntArray(2)
                                layout.getLocationOnScreen(location)
                                origin = location[if (horizontal) 0 else 1]
                            }
                            node != null && kotlin.math.abs((if (horizontal) bounds.exactCenterX() else bounds.exactCenterY()) -
                                origin - coordinate) <= 2f
                        }
                        fun coordinate(distanceDp: Float) = if (edge == 0) distanceDp * density else length - distanceDp * density
                        fun begin() {
                            downTime = SystemClock.uptimeMillis()
                            var center = 0f
                            scenario.onActivity {
                                val container = first.parent as View
                                center = (if (horizontal) container.width else container.height) + 6 * density
                            }
                            // The previous gesture may still be springing back to the safe render bounds.
                            awaitBoundary(center)
                            event(MotionEvent.ACTION_DOWN, center)
                        }
                        fun at(action: Int, distanceDp: Float) = event(action, coordinate(distanceDp))
                        val thresholds = collapseThresholds
                        val requests = collapseRequests.size
                        begin()
                        at(MotionEvent.ACTION_MOVE, 49f)
                        assertEquals("$direction edge=$edge stays outside at 49dp", thresholds, collapseThresholds)
                        awaitPreview(null)
                        at(MotionEvent.ACTION_MOVE, 47f)
                        assertEquals(thresholds + 1, collapseThresholds)
                        awaitPreview(edge)
                        awaitGeometry()
                        awaitBoundary(coordinate(47f))
                        var safeDimensions = emptyList<Pair<Int, Int>>()
                        var safeResizes = emptyList<Int>()
                        scenario.onActivity {
                            first.updateSize(); second.updateSize()
                            safeDimensions = listOf(first.width to first.height, second.width to second.height)
                            safeResizes = resizeEvents.map { it.size }
                        }
                        at(MotionEvent.ACTION_MOVE, 24f)
                        awaitBoundary(coordinate(24f))
                        scenario.onActivity {
                            assertSame(first, layout.terminal(0)); assertSame(second, layout.terminal(1))
                            assertEquals("Elastic boundary motion preserves safe native renderer sizes", safeDimensions,
                                listOf(first.width to first.height, second.width to second.height))
                            first.updateSize(); second.updateSize()
                            assertEquals("Elastic boundary motion never adds PTY resizes", safeResizes, resizeEvents.map { it.size })
                        }
                        at(MotionEvent.ACTION_MOVE, 55f)
                        awaitPreview(null)
                        at(MotionEvent.ACTION_MOVE, 47f)
                        assertEquals("$direction edge=$edge small boundary jitter does not rearm haptics", thresholds + 1, collapseThresholds)
                        at(MotionEvent.ACTION_MOVE, 65f)
                        awaitPreview(null)
                        at(MotionEvent.ACTION_MOVE, 47f)
                        assertEquals("$direction edge=$edge returning past 64dp rearms haptics", thresholds + 2, collapseThresholds)
                        awaitPreview(edge)
                        scenario.onActivity { assertTrue("A fixed-dp edge keeps a legal render ratio",
                            state.value.fraction in TerminalPaneState.MIN_FRACTION..TerminalPaneState.MAX_FRACTION) }
                        at(MotionEvent.ACTION_UP, 47f)
                        assertEquals("$direction edge=$edge keeps the opposite pane immediately", listOf(1 - edge), collapseRequests.drop(requests))
                        awaitPreview(null)
                        begin()
                        at(MotionEvent.ACTION_MOVE, 47f)
                        at(MotionEvent.ACTION_UP, 55f)
                        assertEquals("$direction edge=$edge backing out on UP keeps both panes", requests + 1, collapseRequests.size)
                        awaitPreview(null)
                        begin()
                        at(MotionEvent.ACTION_MOVE, 24f)
                        awaitPreview(edge)
                        awaitBoundary(coordinate(24f))
                        at(MotionEvent.ACTION_CANCEL, 24f)
                        assertEquals("$direction edge=$edge cancelling keeps both panes", requests + 1, collapseRequests.size)
                        assertEquals(thresholds + 4, collapseThresholds)
                        awaitPreview(null)
                        var center = 0f
                        scenario.onActivity {
                            val container = first.parent as View
                            center = (if (horizontal) container.width else container.height) + 6 * density
                        }
                        awaitBoundary(center)
                        scenario.onActivity {
                            assertEquals("Cancelling restores native pane geometry", safeDimensions,
                                listOf(first.width to first.height, second.width to second.height))
                            first.updateSize(); second.updateSize()
                            assertEquals("Spring return does not resize either PTY", safeResizes, resizeEvents.map { it.size })
                            assertSame(first, layout.terminal(0)); assertSame(second, layout.terminal(1))
                        }
                        assertEquals("Edge gestures never invoke the focused-session menu action", 0, unsplitRequests)
                    }
                }
                scenario.onActivity { state.value = restingState }
                awaitGeometry()
                var sizes = emptyList<Int>()
                scenario.onActivity {
                    assertSame(first, layout.terminal(0)); assertSame(second, layout.terminal(1))
                    assertTrue(first.width < oldWidth)
                    first.updateSize(); second.updateSize()
                    val geometry = resizeEvents.map { it.size }
                    repeat(10) {
                        layout.update(state.value, 0, 0, 0xff888888.toInt())
                        layout.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
                        layout.layout(0, 0, 1000, 600)
                        first.updateSize(); second.updateSize()
                    }
                    assertEquals(geometry, resizeEvents.map { it.size })
                    val secondGeometry = resizeEvents[1].size
                    first.setTextSize(24)
                    assertEquals(secondGeometry, resizeEvents[1].size)
                    assertTrue(first.topRow < 0); assertTrue(second.topRow < 0)
                    append(0, "\u001b[?1049h\u001b[41m\u001b[2J\u001b[H\u001b_Ga=T,i=1,q=2,f=24,s=1,v=1,C=1;/wAA\u001b\\")
                    append(1, "\u001b[?1049h\u001b[44m\u001b[2J\u001b[H\u001b_Ga=T,i=2,q=2,f=24,s=1,v=1,C=1;AAD/\u001b\\")
                    assertEquals(0xffff0000.toInt(), first.mEmulator.graphics.placements.single().image.pixels[0])
                    assertEquals(0xff0000ff.toInt(), second.mEmulator.graphics.placements.single().image.pixels[0])
                    fun centerPixel(view: TerminalView): Int {
                        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                        view.draw(Canvas(bitmap))
                        return try { bitmap.getPixel(view.width / 2, view.height / 2) } finally { bitmap.recycle() }
                    }
                    assertNotEquals(centerPixel(first), centerPixel(second))
                    sizes = resizeEvents.map { it.size }
                    positiveSize.value = false
                }
                await("The attached scaffold accepts an empty viewport") {
                    var empty = false; scenario.onActivity { empty = layout.width == 0 && layout.height == 0 }; empty
                }
                scenario.onActivity {
                    first.updateSize(); second.updateSize()
                    assertEquals("An empty viewport never resizes either PTY", sizes, resizeEvents.map { it.size })
                }
            }
        } finally { backends.forEach { it.close() } }
    }
}
