package dev.terminal.app

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.DroidTheme
import dev.terminal.app.ui.DiagnosticsScreen
import dev.terminal.app.ui.SettingsPageScaffold
import dev.terminal.app.ui.TerminalHapticFeedbackProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsAppBarUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val title = "Settings app bar fixture"
    private val viewport = "Settings scroll viewport"
    private val rowColor = Color(0xFF193B68)

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.clearCache()
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && predicate(node)) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        return walk(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Settings app bar did not reach the expected state" }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(50)
        }
    }
    private fun back(): AccessibilityNodeInfo {
        var node = checkNotNull(find { it.contentDescription?.toString() == instrumentation.targetContext.getString(R.string.back) })
        while (!node.isClickable) node = checkNotNull(node.parent)
        return node
    }

    private fun touch(x: Float, fromY: Float, toY: Float) {
        val start = SystemClock.uptimeMillis()
        for (i in 0..20) {
            val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; 20 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, fromY + (toY - fromY) * i / 20, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            SystemClock.sleep(20)
        }
        SystemClock.sleep(700)
        instrumentation.waitForIdleSync()
    }

    /** Stop before lifting so each checkpoint observes scroll distance without a fling. */
    private fun scrollDistance(screen: Rect, distance: Float, whileHeld: (() -> Unit)? = null) {
        val slop = ViewConfiguration.get(instrumentation.targetContext).scaledTouchSlop
        val fromY = screen.height() * 0.65f
        val delta = -distance - if (distance > 0f) slop else -slop
        val start = SystemClock.uptimeMillis()
        fun event(action: Int, y: Float) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, screen.exactCenterX(), y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        event(MotionEvent.ACTION_DOWN, fromY)
        for (i in 1..20) {
            event(MotionEvent.ACTION_MOVE, fromY + delta * i / 20)
            SystemClock.sleep(20)
        }
        SystemClock.sleep(350)
        instrumentation.waitForIdleSync()
        whileHeld?.invoke()
        event(MotionEvent.ACTION_UP, fromY + delta)
        SystemClock.sleep(350)
        instrumentation.waitForIdleSync()
    }

    private fun titleBrightness(screenshot: Bitmap, titleBounds: Rect): Int {
        var brightness = 0
        for (y in titleBounds.top.coerceAtLeast(0) until titleBounds.bottom.coerceAtMost(screenshot.height)) {
            for (x in titleBounds.left.coerceAtLeast(0) until titleBounds.right.coerceAtMost(screenshot.width)) {
                brightness = maxOf(brightness, android.graphics.Color.red(screenshot.getPixel(x, y)))
            }
        }
        return brightness
    }

    @Test fun lazySettingsCollapseToFloatingBackAndEdgeToEdgeContent() = checkScrolling(lazy = true)
    @Test fun columnSettingsCollapseToFloatingBackAndEdgeToEdgeContent() = checkScrolling(lazy = false)

    @Test fun diagnosticsKeepsRefreshInContentAndCollapsesItsHeader() {
        val context = instrumentation.targetContext
        var refreshes = 0
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    DiagnosticsScreen({}, "Selectable diagnostic output\n".repeat(100)) { refreshes++ }
                } }
            } }
            await { find { it.text?.toString() == context.getString(R.string.refresh) } != null }
            var refresh = checkNotNull(find { it.text?.toString() == context.getString(R.string.refresh) })
            while (!refresh.isClickable) refresh = checkNotNull(refresh.parent)
            assertTrue(refresh.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            assertEquals(1, refreshes)
            val initialBack = bounds(back())
            val screen = bounds(instrumentation.uiAutomation.rootInActiveWindow)
            touch(screen.exactCenterX(), screen.height() * 0.8f, screen.height() * 0.25f)
            assertNull(find { it.text?.toString() == context.getString(R.string.diagnostics_title) })
            assertEquals(initialBack, bounds(back()))
            assertNull("Refresh scrolls with the content", find { it.text?.toString() == context.getString(R.string.refresh) })
        }
    }

    private fun checkScrolling(lazy: Boolean) {
        var backClicks = 0
        var rowClicks = 0
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { TerminalHapticFeedbackProvider {
                    // A parent surface must not tint an opaque scaffold background behind the header.
                    Surface(tonalElevation = 3.dp) {
                        SettingsPageScaffold(title, { backClicks++ }) { padding ->
                            val modifier = Modifier.fillMaxSize().padding(padding)
                                .semantics { contentDescription = viewport }
                            val row = Modifier.fillMaxWidth().height(80.dp).background(rowColor).clickable { rowClicks++ }
                            if (lazy) LazyColumn(modifier) {
                                items(60) { Text("Setting $it", row.padding(16.dp)) }
                            } else Column(modifier.verticalScroll(rememberScrollState())) {
                                repeat(60) { Text("Setting $it", row.padding(16.dp)) }
                            }
                        }
                    }
                } }
            } }
            await { find { it.contentDescription?.toString() == instrumentation.targetContext.getString(R.string.back) } != null }
            await { find { it.contentDescription?.toString() == viewport } != null }
            SystemClock.sleep(1000)
            instrumentation.waitForIdleSync()
            val initialBack = bounds(back())
            val initialViewport = bounds(checkNotNull(find { it.contentDescription?.toString() == viewport }))
            assertNotNull(find { it.text?.toString() == title })
            assertTrue("Expanded header keeps the first row below the back button", initialViewport.top > initialBack.bottom)
            val screen = bounds(instrumentation.uiAutomation.rootInActiveWindow)
            val density = instrumentation.targetContext.resources.displayMetrics.density
            val expandedTitle = bounds(checkNotNull(find { it.text?.toString() == title }))
            assertTrue("Expanded title stays below the circular back button with a gap",
                expandedTitle.top - initialBack.bottom >= (16f * density).toInt())
            fun assertSmallTitle() {
                val smallTitle = bounds(checkNotNull(find { it.text?.toString() == title }))
                assertTrue("Small title uses a smaller text size", smallTitle.height() < expandedTitle.height())
                assertEquals("Small title is beside the back button", initialBack.centerY().toFloat(),
                    smallTitle.exactCenterY(), 2f * density)
                assertTrue("Small title keeps 16dp clear of the circular back button",
                    smallTitle.left - initialBack.right >= (16f * density).toInt())
            }
            val smallTop = initialBack.top + (56f * density).toInt()
            val inset = (2f * density).toInt()
            val initialScreenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            val initialBackColor: Int
            val pageBackground: Int
            val initialTitleBrightness: Int
            try {
                initialTitleBrightness = titleBrightness(initialScreenshot, expandedTitle)
                pageBackground = initialScreenshot.getPixel(screen.width() * 3 / 4, smallTop - (4f * density).toInt())
                initialBackColor = initialScreenshot.getPixel(initialBack.centerX(), initialBack.bottom - inset * 3)
                assertNotEquals("Back button is filled from the expanded stage", pageBackground, initialBackColor)
                assertEquals("Expanded back button has circular corners", pageBackground,
                    initialScreenshot.getPixel(initialBack.left + inset, initialBack.top + inset))
                assertEquals("Expanded back button has no floating shadow", pageBackground,
                    initialScreenshot.getPixel(initialBack.centerX(), initialBack.bottom + inset))
                assertEquals("Transparent app bar exposes the page background", Color(0xFF0C0E11).toArgb(), pageBackground)
            } finally { initialScreenshot.recycle() }
            scrollDistance(screen, (initialViewport.top - smallTop) * 0.25f) {
                val partialTitle = bounds(checkNotNull(find { it.text?.toString() == title }))
                val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    assertTrue("Large title fades during collapse before reaching the small stage",
                        titleBrightness(screenshot, partialTitle) < initialTitleBrightness - 20)
                } finally { screenshot.recycle() }
                assertEquals("Back button stays fixed during title fading", initialBack, bounds(back()))
            }
            await { bounds(checkNotNull(find { it.contentDescription?.toString() == viewport })) == initialViewport }
            scrollDistance(screen, (initialViewport.top - smallTop).toFloat())
            assertSmallTitle()
            val smallViewport = bounds(checkNotNull(find { it.contentDescription?.toString() == viewport }))
            assertEquals("First stage retains a complete small app bar", smallTop.toFloat(), smallViewport.top.toFloat(), 2f * density)
            assertEquals(initialBack, bounds(back()))
            fun distanceFromContent(color: Int): Int =
                kotlin.math.abs(android.graphics.Color.red(color) - android.graphics.Color.red(rowColor.toArgb())) +
                    kotlin.math.abs(android.graphics.Color.green(color) - android.graphics.Color.green(rowColor.toArgb())) +
                    kotlin.math.abs(android.graphics.Color.blue(color) - android.graphics.Color.blue(rowColor.toArgb()))
            SystemClock.sleep(300)
            assertSmallTitle()
            repeat(2) {
                scrollDistance(screen, 2f * density)
                assertSmallTitle()
            }
            scrollDistance(screen, 2f * density)
            assertNull("Scrolling beyond 4dp fades out the small title", find { it.text?.toString() == title })
            val transparentScreenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                assertTrue("Content is visible through the small app bar before all top space is removed",
                    distanceFromContent(transparentScreenshot.getPixel(screen.width() * 3 / 4,
                        smallTop - (4f * density).toInt())) < distanceFromContent(pageBackground) / 10)
            } finally { transparentScreenshot.recycle() }
            assertEquals("Back button stays fixed", initialBack, bounds(back()))
            val fadedViewport = bounds(checkNotNull(find { it.contentDescription?.toString() == viewport }))
            assertEquals("Content moves only by the actual extra scroll distance",
                smallViewport.top - 6f * density, fadedViewport.top.toFloat(), 1f)
            val fadedRow = bounds(checkNotNull(find { it.text?.toString() == "Setting 0" }))
            SystemClock.sleep(300)
            assertEquals("Content does not continue moving while the finger is stopped", fadedViewport,
                bounds(checkNotNull(find { it.contentDescription?.toString() == viewport })))
            assertEquals("Rows do not scroll automatically after collapsing", fadedRow,
                bounds(checkNotNull(find { it.text?.toString() == "Setting 0" })))
            scrollDistance(screen, (fadedViewport.top - screen.top).toFloat())
            val collapsedViewport = bounds(checkNotNull(find { it.contentDescription?.toString() == viewport }))
            assertEquals("Content reaches the top window edge", screen.top, collapsedViewport.top)
            val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                val middleFade = distanceFromContent(screenshot.getPixel(screen.width() * 3 / 4, initialBack.centerY()))
                val lowerFade = distanceFromContent(screenshot.getPixel(screen.width() * 3 / 4, smallTop - inset))
                assertTrue("Former app-bar area has a transparent fading edge rather than a solid background",
                    middleFade > lowerFade && middleFade < distanceFromContent(pageBackground) * 0.6f)
                assertEquals("Content below the gradient is unobscured", rowColor.toArgb(),
                    screenshot.getPixel(screen.width() * 3 / 4, smallTop + inset))
                val corner = screenshot.getPixel(initialBack.left + inset, initialBack.top + inset)
                val cornerBackground = screenshot.getPixel(screen.width() * 3 / 4, initialBack.top + inset)
                assertTrue("Back button keeps circular corners above the gradient",
                    kotlin.math.abs(distanceFromContent(corner) - distanceFromContent(cornerBackground)) < 8)
                assertEquals("Back button keeps the same filled background throughout scrolling", initialBackColor,
                    screenshot.getPixel(initialBack.centerX(), initialBack.bottom - inset * 3))
                val shadow = screenshot.getPixel(initialBack.centerX(), initialBack.bottom + inset)
                val shadowBackground = screenshot.getPixel(screen.width() * 3 / 4, initialBack.bottom + inset)
                assertTrue("Floating back button has a subtle shadow outside its circular surface",
                    distanceFromContent(shadow) > distanceFromContent(shadowBackground) &&
                        distanceFromContent(shadow) < distanceFromContent(pageBackground) / 2)
                val statusInset = initialBack.top - (8f * density).toInt()
                if (statusInset > 0) {
                    val statusFade = distanceFromContent(screenshot.getPixel(screen.width() * 3 / 4, statusInset / 2))
                    assertTrue("System edge has a stronger gradient while retaining visible content",
                        statusFade > distanceFromContent(pageBackground) * 0.8f &&
                            statusFade < distanceFromContent(pageBackground))
                }
                val belowStatusFade = distanceFromContent(screenshot.getPixel(screen.width() * 3 / 4,
                    statusInset + (12f * density).toInt()))
                assertTrue("Gradient extends smoothly below the status bar without hiding content",
                    belowStatusFade > distanceFromContent(pageBackground) * 0.45f &&
                        belowStatusFade < distanceFromContent(pageBackground) * 0.8f)
            } finally { screenshot.recycle() }
            touch(screen.width() * 0.75f, initialBack.exactCenterY(), initialBack.exactCenterY())
            assertEquals("Rows remain clickable in the former app-bar area", 1, rowClicks)
            assertTrue(back().performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            assertEquals(1, backClicks)
            touch(screen.exactCenterX(), screen.height() * 0.8f, screen.height() * 0.25f)
            touch(screen.exactCenterX(), screen.height() * 0.3f, screen.height() * 0.4f)
            assertNull("Header stays collapsed while content is still scrolled", find { it.text?.toString() == title })
            repeat(4) { touch(screen.exactCenterX(), screen.height() * 0.25f, screen.height() * 0.8f) }
            assertNotNull("Header returns at the start of the content", find { it.text?.toString() == title })
            assertEquals(initialViewport, bounds(checkNotNull(find { it.contentDescription?.toString() == viewport })))
            assertEquals(initialBack, bounds(back()))
        }
    }
}
