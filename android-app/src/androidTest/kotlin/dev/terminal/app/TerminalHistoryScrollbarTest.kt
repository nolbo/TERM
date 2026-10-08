@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.terminal.app

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.os.Looper
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import androidx.compose.material3.MotionScheme
import androidx.compose.ui.graphics.toArgb
import dev.terminal.app.ui.TerminalChromeColors
import com.termux.terminal.CommandMarker
import com.termux.terminal.TerminalCheckpoint
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.HistoryScrollbarModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class TerminalHistoryScrollbarTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var view: TerminalView
    private lateinit var backend: RemoteTerminalBackend
    private val output = ByteArrayOutputStream()

    @Test fun leftSwipeRevealsCapsuleWithInternalSymbolsAndDragTraversesHistory() = withTerminal {
        assertFalse(view.isHistoryScrollbarVisible)
        scroll(-3f)
        assertFalse(view.isHistoryScrollbarVisible)
        reveal()
        main {
            render()
            val capsule = bounds("capsule")
            val thumb = bounds("thumb")
            val dp = view.resources.displayMetrics.density
            assertEquals(24 * dp, capsule.width(), 0.1f)
            assertEquals(48 * dp, bounds("railTouch").width(), 0.1f)
            assertTrue(capsule.height() > capsule.width() * 5)
            assertEquals(20 * dp, thumb.width(), 0.1f)
            assertTrue(thumb.height() >= 48 * dp)
            assertTrue(capsule.contains(thumb))
            assertTrue(bounds("railTouch").contains(bounds("previous")))
            assertTrue(bounds("railTouch").contains(bounds("next")))
            assertEquals(32 * dp, bounds("previous").height(), 0.1f)
            assertEquals(32 * dp, bounds("next").height(), 0.1f)
            for (name in listOf("failed", "chevronUp")) {
                val symbol = field(name) as Drawable
                assertFalse("$name is drawn", symbol.bounds.isEmpty)
                assertTrue("$name stays inside the capsule", capsule.contains(RectF(symbol.bounds)))
            }
            val buckets = buckets()
            assertTrue(buckets.any { it.count > 1 })
            assertTrue(buckets.size < view.mEmulator.history.markers.size)
            view.setHistoryScrollbarSnapEnabled(false)
            // The drawing shrinks by half while the outer, invisible hit target stays 48dp wide.
            val drag = Drag(bounds("railTouch").left + 2 * dp, thumb.centerY())
            drag.move(-1000f)
            assertTrue(view.isHistoryScrollbarDragging)
            assertEquals(-view.mEmulator.screen.activeTranscriptRows, view.topRow)
            drag.move(10000f)
            assertEquals(0, view.topRow)
            drag.finish()
            assertFalse(view.isHistoryScrollbarDragging)
            assertEquals(0, output.size())
        }
    }

    @Test fun mixedDensityDrawsOnlyStatusSymbolsInsideNarrowCapsule() = withTerminal {
        main { append("\u001b]133;A\u0007$ \u001b]133;B\u0007live --private=value\r\n\u001b]133;C\u0007") }
        reveal()
        main {
            render()
            assertTrue(buckets().any { it.running && it.failed })
            for (name in listOf("running", "failed")) {
                val instances = listOf(field(name), field("active" + name.replaceFirstChar(Char::uppercaseChar)))
                    .map { it as Drawable }.filter { !it.bounds.isEmpty }
                assertTrue("$name is drawn with normal or active contrast", instances.isNotEmpty())
                for (symbol in instances) {
                    assertTrue(bounds("capsule").contains(RectF(symbol.bounds)))
                    assertEquals(18 * density(), symbol.bounds.width().toFloat(), 1f)
                }
            }
            val mixed = buckets().first { it.running && it.failed }
            tap(bounds("capsule").centerX(), bucketY(mixed) - 10 * density())
            assertEquals(minOf(0, mixed.runningMarker.anchor.row!!), view.topRow)
            assertEquals(mixed.runningMarker.commandName, markerLabel(mixed.runningMarker))
            render()
            assertCircularThumbAt(bucketY(mixed) - 10 * density())
            tap(bounds("capsule").centerX(), bucketY(mixed) + 10 * density())
            assertEquals(minOf(0, mixed.failedMarker.anchor.row!!), view.topRow)
            assertEquals(mixed.failedMarker.commandName, markerLabel(mixed.failedMarker))
            render()
            assertCircularThumbAt(bucketY(mixed) + 10 * density())
        }
    }

    @Test fun thumbBecomesCommandBackgroundAndRestoresCapsuleWhenDraggedAway() = withTerminal {
        reveal()
        main {
            view.setHistoryScrollbarColors(Color.WHITE, Color.BLACK)
            view.setHistoryScrollbarSnapEnabled(false)
            render()
            val bucket = buckets()[buckets().size / 2]
            val center = bucketY(bucket)
            val drag = Drag(bounds("railTouch").left + 2 * density(), bounds("thumb").centerY())
            drag.move(center)
            val frame = snapshot()
            try {
                assertCircularThumbAt(center)
                val circle = bounds("visualThumb")
                val x = (circle.left + density()).toInt()
                assertEquals("A circle leaves its corner uncovered", Color.BLACK,
                    frame.getPixel(x, (circle.top + density()).toInt()))
                assertEquals("The handle is drawn beneath the command icon", Color.WHITE,
                    frame.getPixel(x, circle.centerY().toInt()))
            } finally { frame.recycle() }
            drag.move(center + 8 * density())
            render()
            assertTrue("The shape changes continuously near the icon", bounds("visualThumb").height() > 20 * density())
            assertTrue(bounds("visualThumb").height() < bounds("thumb").height())
            drag.move(center + 24 * density())
            render()
            assertEquals("Leaving the command region restores the original handle", bounds("thumb"), bounds("visualThumb"))
            drag.move(center)
            render()
            assertCircularThumbAt(center)
            drag.finish()
            assertFalse(view.isHistoryScrollbarDragging)
            assertEquals(0, output.size())
        }
    }

    @Test fun circularThumbAfterMarkerJumpKeepsOriginalDragCoordinates() = withTerminal {
        reveal()
        main {
            view.setHistoryScrollbarSnapEnabled(false)
            render()
            val bucket = buckets()[buckets().size / 2]
            tap(bounds("capsule").centerX(), bucketY(bucket))
            render()
            assertCircularThumbAt(bucketY(bucket))
            val logical = bounds("thumb")
            val circle = bounds("visualThumb")
            val delta = 20 * density()
            val expected = HistoryScrollbarModel.rowAt(
                (logical.top + delta - number("trackTop")) /
                    (number("trackBottom") - number("trackTop") - number("thumbHeight")),
                view.mEmulator.screen.activeTranscriptRows)
            val drag = Drag(circle.centerX(), circle.centerY())
            drag.move(circle.centerY() + delta)
            assertEquals("Changing the visible height does not change the drag origin", expected, view.topRow)
            drag.finish()
            assertEquals(expected, view.topRow)
        }
    }

    @Test fun commandBackgroundFollowsReflowAndReturnsToCapsuleAfterTrim() = withTerminal {
        reveal()
        main {
            val marker = view.mEmulator.history.markers[20]
            assertTrue(view.jumpToCommand(marker))
            render()
            assertEquals(bounds("visualThumb").width(), bounds("visualThumb").height(), 0.1f)
            view.mEmulator.resize(view.mEmulator.mColumns / 2, view.mEmulator.mRows, 1, 1)
            view.onScreenUpdated()
            assertTrue(view.jumpToCommand(marker))
            render()
            assertEquals(bounds("visualThumb").width(), bounds("visualThumb").height(), 0.1f)
            repeat(1100) { append("trim\r\n") }
            render()
            assertTrue(view.mEmulator.history.markers.isEmpty())
            assertEquals(bounds("thumb"), bounds("visualThumb"))
        }
    }

    @Test fun verticalRightwardShortAndMouseGesturesDoNotRevealCapsule() = withTerminal {
        main {
            val vertical = Drag(300f, 600f)
            vertical.move(900f)
            vertical.cancel()
            assertTrue(view.topRow < 0)
            assertFalse(view.isHistoryScrollbarVisible)
            val top = view.topRow
            Drag(300f, 600f).apply { moveTo(700f, 600f); finish() }
            Drag(700f, 600f).apply { moveTo(700f - 16 * density(), 600f); cancel() }
            Drag(700f, 600f, InputDevice.SOURCE_MOUSE).apply { moveTo(300f, 600f); finish() }
            assertEquals(top, view.topRow)
        }
        SystemClock.sleep(180)
        main { assertFalse(view.isHistoryScrollbarVisible) }
        reveal()
        assertTrue(view.isHistoryScrollbarVisible)
    }

    @Test fun emptyHistoryCannotBeRevealedBySwipe() = withTerminal(commands = 0) {
        leftSwipe()
        SystemClock.sleep(180)
        main { assertFalse(view.isHistoryScrollbarVisible) }
    }

    @Test fun shortHistoryUsesCenteredAggregateAndTapResolvesItsCommand() = withTerminal(commands = 0) {
        main {
            repeat(2) { append("\u001b]133;C\u0007command\u001b]133;D;0\u0007\r\n") }
            repeat(view.mEmulator.mRows) { append("padding\r\n") }
        }
        reveal()
        main {
            render()
            val bucket = buckets().single()
            val center = (number("trackTop") + number("trackBottom")) / 2
            tap(bounds("capsule").centerX(), center)
            assertEquals(bucket.marker.anchor.row, view.topRow)
        }
    }

    @Test fun pinchAndItsOneFingerTailDoNotRevealCapsule() = withTerminal {
        main {
            var scales = 0
            view.setTerminalViewClient(TerminalInputCallbacks(onPinchScale = { scales++; 1f }))
            val start = SystemClock.uptimeMillis()
            var tick = 0L
            fun dispatch(action: Int, count: Int, fraction: Float) {
                val properties = Array(count) { index -> MotionEvent.PointerProperties().apply {
                    id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
                } }
                val coordinates = Array(count) { index -> MotionEvent.PointerCoords().apply {
                    x = 600f + index * 200f + fraction * (if (index == 0) -450f else 150f)
                    y = 600f; pressure = 1f; size = 1f
                } }
                val event = MotionEvent.obtain(start, start + tick, action, count, properties, coordinates,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                try { view.onTouchEvent(event) } finally { event.recycle() }
                tick += 30
            }
            dispatch(MotionEvent.ACTION_DOWN, 1, 0f)
            dispatch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 0f)
            for (step in 1..10) dispatch(MotionEvent.ACTION_MOVE, 2, step / 10f)
            dispatch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 1f)
            dispatch(MotionEvent.ACTION_MOVE, 1, 1.2f)
            dispatch(MotionEvent.ACTION_UP, 1, 1.2f)
            assertTrue(scales > 0)
            assertEquals(0, view.topRow)
            assertEquals(0, output.size())
        }
        SystemClock.sleep(180)
        main { assertFalse(view.isHistoryScrollbarVisible) }
    }

    @Test fun capsuleStaysOpenUntilRightSwipeAndLeavesNoFloatingLatestButton() = withTerminal {
        ActivityScenario.launch(AppInfoActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContentView(view) }
            instrumentation.waitForIdleSync()
            scroll(-3f)
            reveal()
            SystemClock.sleep(2200) // Exceed the former idle timeout and fade duration.
            var top = 0
            main {
                assertTrue(view.isHistoryScrollbarVisible)
                assertEquals(1f, number("reveal"), 0f)
                top = view.topRow
            }
            rightSwipe()
            waitForHidden()
            main {
                assertFalse(view.isHistoryScrollbarVisible)
                assertEquals(top, view.topRow)
                assertTrue(view.topRow < 0)
                render()
                val x = bounds("capsule").left - 16 * density()
                val y = bounds("capsule").bottom - 24 * density()
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    val bar = overlay()
                    bar.javaClass.getDeclaredMethod("draw", Canvas::class.java).apply { isAccessible = true }.invoke(bar, Canvas(bitmap))
                    assertEquals("The former floating button is absent", Color.TRANSPARENT, bitmap.getPixel(x.toInt(), y.toInt()))
                } finally { bitmap.recycle() }
                Drag(x, y).apply {
                    assertFalse(field("captured") as Boolean)
                    cancel()
                }
                assertEquals(top, view.topRow)
            }
            SystemClock.sleep(180)
            main { assertFalse(view.isHistoryScrollbarVisible) }
        }
    }

    @Test fun rightSwipeStartingInsideRailDismissesWithoutDraggingOrJumping() = withTerminal {
        scroll(-3f)
        reveal()
        main {
            render()
            val top = view.topRow
            val x = bounds("railTouch").left + 10 * density()
            val y = bounds("thumb").centerY()
            val drag = Drag(x, y)
            assertTrue(field("captured") as Boolean)
            drag.moveTo(x + 30 * density(), y + 2 * density())
            assertFalse(view.isHistoryScrollbarDragging)
            drag.finish()
            assertEquals(top, view.topRow)
            assertEquals(0, output.size())
        }
        waitForHidden()
    }

    @Test fun capsuleSlidesAtFullOpacityAndTouchTargetFollowsIt() = withTerminal {
        main {
            val surface = Color.rgb(37, 53, 71)
            view.setHistoryScrollbarColors(Color.WHITE, surface)
            val baseline = snapshot()
            try {
                leftSwipe()
                val entering = field("animator") as ValueAnimator
                entering.pause()
                entering.setCurrentFraction(0f)
                snapshot().let { frame ->
                    try { assertTrue("Entry starts outside the screen", baseline.sameAs(frame)) }
                    finally { frame.recycle() }
                }
                seekPartialSlide(entering)
                val capsule = bounds("capsule")
                val y = capsule.top + 34 * density() // The gap below the compact arrow.
                val offset = (1 - number("reveal")) * 48 * density()
                assertTrue(offset > 0)
                snapshot().let { frame ->
                    try {
                        assertEquals("The moving capsule stays opaque", surface,
                            frame.getPixel((capsule.centerX() + offset).toInt(), y.toInt()))
                        val x = (capsule.left + 2 * density()).toInt()
                        assertEquals("The resting position remains uncovered", baseline.getPixel(x, y.toInt()), frame.getPixel(x, y.toInt()))
                    } finally { frame.recycle() }
                }
                Drag(bounds("railTouch").left + density(), y).apply {
                    assertFalse("The old hit target must move with the capsule", field("captured") as Boolean)
                    cancel()
                }
                Drag(bounds("railTouch").left + offset + density(), y).apply {
                    assertTrue("The translated rail accepts touches", field("captured") as Boolean)
                    cancel()
                }
                entering.setCurrentFraction(1f)
                snapshot().let { frame ->
                    try { assertEquals(surface, frame.getPixel(capsule.centerX().toInt(), y.toInt())) }
                    finally { frame.recycle() }
                }
                rightSwipe()
                val exiting = field("animator") as ValueAnimator
                exiting.pause()
                seekPartialSlide(exiting)
                val exitOffset = (1 - number("reveal")) * 48 * density()
                snapshot().let { frame ->
                    try {
                        assertEquals("Exit slides toward the right without fading", surface,
                            frame.getPixel((capsule.centerX() + exitOffset).toInt(), y.toInt()))
                    } finally { frame.recycle() }
                }
                exiting.setCurrentFraction(1f)
                snapshot().let { frame ->
                    try { assertTrue("Exit leaves no overlay behind", baseline.sameAs(frame)) }
                    finally { frame.recycle() }
                }
                assertFalse(view.isHistoryScrollbarVisible)
            } finally { baseline.recycle() }
        }
    }

    @Test fun reversingSwipeCancelsOppositeAnimationIncludingBeforeItsFirstFrame() = withTerminal {
        main {
            leftSwipe()
            val entering = field("animator") as ValueAnimator
            entering.pause()
            entering.setCurrentFraction(0f)
            rightSwipe()
            assertFalse(entering.isRunning)
            assertFalse(view.isHistoryScrollbarVisible)

            leftSwipe()
            (field("animator") as ValueAnimator).apply { pause(); setCurrentFraction(0.15f) }
            rightSwipe()
            (field("animator") as ValueAnimator).apply { pause(); setCurrentFraction(0.15f) }
            val position = number("reveal")
            leftSwipe()
            assertEquals("Reversing preserves the current position", position, number("reveal"), 0f)
            (field("animator") as ValueAnimator).apply { pause(); setCurrentFraction(1f) }

            rightSwipe()
            val exiting = field("animator") as ValueAnimator
            exiting.pause()
            exiting.setCurrentFraction(0f)
            leftSwipe()
            assertFalse(exiting.isRunning)
            assertEquals(1f, number("reveal"), 0f)
            assertTrue(view.isHistoryScrollbarVisible)
        }
    }

    @Test fun releaseSnapsOnlyWithinTwoRowsAndCanBeDisabled() = withTerminal {
        scroll(-3f)
        reveal()
        main {
            val target = view.mEmulator.history.markers[10]
            for (enabled in listOf(true, false)) {
                view.scrollToLatest()
                view.setHistoryScrollbarSnapEnabled(enabled)
                // The bar is still visible from the preceding swipe.
                render()
                val thumb = bounds("thumb")
                val destination = markerY(target) + (number("trackBottom") - number("trackTop") - number("thumbHeight")) /
                    view.mEmulator.screen.activeTranscriptRows
                val drag = Drag(bounds("capsule").centerX(), thumb.centerY())
                drag.move(destination)
                drag.move(destination) // Pause at the destination instead of flinging past it.
                drag.finish()
                assertEquals(target.anchor.row!! + if (enabled) 0 else 1, view.topRow)
            }
        }
    }

    @Test fun markerTapAndPreviousNextActionsJumpToAnchorsIncludingAfterReflow() = withTerminal {
        scroll(-3f)
        reveal()
        main {
            val markers = view.mEmulator.history.markers
            val middle = markers[markers.size / 2]
            assertTrue(view.jumpToCommand(middle))
            assertEquals(middle.anchor.row, view.topRow)
            assertEquals("tool-${markers.size / 2}", markerLabel(middle))
            assertTrue(view.scrollToPreviousCommand())
            assertEquals(markers[markers.size / 2 - 1].anchor.row, view.topRow)
            assertTrue(view.scrollToNextCommand())
            assertEquals(middle.anchor.row, view.topRow)
            view.mEmulator.resize(view.mEmulator.mColumns / 2, view.mEmulator.mRows, 1, 1)
            view.onScreenUpdated()
            assertTrue(view.jumpToCommand(middle))
            assertEquals(middle.anchor.row, view.topRow)
            assertEquals("tool-${markers.size / 2}", markerLabel(middle))
            render()
            for (bucket in buckets()) {
                val target = if (bucket.failed) bucket.failedMarker else if (bucket.running) bucket.runningMarker else bucket.marker
                tap(bounds("capsule").centerX(), bucketY(bucket))
                assertEquals("Displayed symbol jumps to its retained command", minOf(0, target.anchor.row!!), view.topRow)
                assertEquals(target.commandName, markerLabel(target))
            }
            assertTrue(view.jumpToCommand(middle))
            tap(bounds("previous").centerX(), bounds("previous").centerY())
            assertEquals(markers[markers.size / 2 - 1].anchor.row, view.topRow)
            tap(bounds("next").centerX(), bounds("next").centerY())
            assertEquals(middle.anchor.row, view.topRow)
            val info = AccessibilityNodeInfo.obtain()
            view.onInitializeAccessibilityNodeInfo(info)
            assertTrue(info.actionList.any { it.id == com.termux.view.R.id.history_previous_command })
            assertTrue(view.performAccessibilityAction(com.termux.view.R.id.history_latest_output, null))
            assertEquals(0, view.topRow)
        }
    }

    @Test fun compactArrowsSwitchSymbolsAndJumpToHistoryEdgesAtAndBeyondCommands() = withTerminal(commands = 0) {
        main {
            repeat(30) { append("leading output\r\n") }
            repeat(3) { command ->
                append("\u001b]133;C\u0007command-$command\r\n")
                repeat(4) { append("output\r\n") }
                append("\u001b]133;D;0\u0007")
            }
            repeat(view.mEmulator.mRows + 20) { append("trailing output\r\n") }
        }
        reveal()
        main {
            val markers = view.mEmulator.history.markers
            val first = markers.first()
            val last = markers.last()
            val oldest = -view.mEmulator.screen.activeTranscriptRows
            assertTrue(first.anchor.row!! > oldest)
            assertTrue(last.anchor.row!! < -1)
            assertTrue(view.jumpToCommand(markers[1]))
            val previousCommandSymbol = arrowSnapshot("previous")
            val nextCommandSymbol = arrowSnapshot("next")
            try {
                for (aboveFirst in listOf(false, true)) {
                    assertTrue(view.jumpToCommand(first))
                    if (aboveFirst) scroll(-1f)
                    arrowSnapshot("previous").let { frame ->
                        try { assertFalse("The top edge has a distinct icon", previousCommandSymbol.sameAs(frame)) }
                        finally { frame.recycle() }
                    }
                    tap(bounds("previous").centerX(), bounds("previous").centerY())
                    assertEquals(oldest, view.topRow)
                    tap(bounds("previous").centerX(), bounds("previous").centerY())
                    assertEquals(oldest, view.topRow)
                    tap(bounds("next").centerX(), bounds("next").centerY())
                    assertEquals(first.anchor.row, view.topRow)
                }
                for (belowLast in listOf(false, true)) {
                    assertTrue(view.jumpToCommand(last))
                    if (belowLast) scroll(1f)
                    arrowSnapshot("next").let { frame ->
                        try { assertFalse("The bottom edge has a distinct icon", nextCommandSymbol.sameAs(frame)) }
                        finally { frame.recycle() }
                    }
                    tap(bounds("next").centerX(), bounds("next").centerY())
                    assertEquals(0, view.topRow)
                    tap(bounds("next").centerX(), bounds("next").centerY())
                    assertEquals(0, view.topRow)
                    tap(bounds("previous").centerX(), bounds("previous").centerY())
                    assertEquals(last.anchor.row, view.topRow)
                }
                val info = AccessibilityNodeInfo.obtain()
                view.onInitializeAccessibilityNodeInfo(info)
                assertTrue(info.actionList.any { it.id == com.termux.view.R.id.history_first_output })
                assertTrue(view.performAccessibilityAction(com.termux.view.R.id.history_first_output, null))
                assertEquals(oldest, view.topRow)
                assertTrue(view.performAccessibilityAction(com.termux.view.R.id.history_latest_output, null))
                assertEquals(0, view.topRow)
            } finally {
                previousCommandSymbol.recycle()
                nextCommandSymbol.recycle()
            }
        }
    }

    @Test fun arrowsNavigateHistoryEdgesWithoutShellMarkers() = withTerminal(commands = 0) {
        main { repeat(150) { append("output-$it\r\n") } }
        reveal()
        main {
            render()
            assertTrue(view.mEmulator.history.markers.isEmpty())
            tap(bounds("previous").centerX(), bounds("previous").centerY())
            assertEquals(-view.mEmulator.screen.activeTranscriptRows, view.topRow)
            render()
            assertEquals(97, (field("edgeArrowUp") as Drawable).alpha)
            assertEquals(255, (field("edgeArrow") as Drawable).alpha)
            tap(bounds("next").centerX(), bounds("next").centerY())
            assertEquals(0, view.topRow)
            render()
            assertEquals(255, (field("edgeArrowUp") as Drawable).alpha)
            assertEquals(97, (field("edgeArrow") as Drawable).alpha)
        }
    }

    @Test fun freshOutputAccumulatesThroughTrimButResizeDoesNotCountAsNewOutput() = withTerminal {
        scroll(-4f)
        reveal()
        main {
            val top = view.topRow
            append("new-1\r\nnew-2\r\nnew-3\r\n")
            assertEquals(3L, view.pendingHistoryOutputRows)
            assertEquals(top - 3, view.topRow)
            view.mEmulator.resize(view.mEmulator.mColumns - 2, view.mEmulator.mRows + 1, 1, 1)
            view.onScreenUpdated()
            assertEquals(3L, view.pendingHistoryOutputRows)
            repeat(1100) { append("trim\r\n") }
            assertEquals(1103L, view.pendingHistoryOutputRows)
            render()
            val info = AccessibilityNodeInfo.obtain()
            view.onInitializeAccessibilityNodeInfo(info)
            assertTrue(info.stateDescription.toString().contains("1103"))
            val next = bounds("next")
            tap(next.centerX(), next.centerY())
            assertEquals(0, view.topRow)
            assertEquals(0L, view.pendingHistoryOutputRows)
        }
    }

    @Test fun alternateMouseAndApplicationOwnedViewportsHideOverlayAndPreserveWheelInput() = withTerminal {
        scroll(-3f)
        reveal()
        for (mode in listOf("\u001b[?1049h", "\u001b[?1049l\u001b[?1003h\u001b[?1006h",
            "\u001b[?1003l\u001b[2;5r", "\u001b[r\u001b[?1h")) {
            main { append(mode) }
            leftSwipe()
            SystemClock.sleep(180)
            main {
                assertFalse(view.isHistoryScrollbarVisible)
                assertFalse(view.scrollToPreviousCommand())
                if (view.mEmulator.isMouseTrackingActive) {
                    scroll(-1f)
                    assertTrue(output.toString("UTF-8").contains("\u001b[<64;"))
                    output.reset()
                }
            }
        }
    }

    @Test fun checkpointAndSessionSwitchPreserveMarkersAndCancelOldDrag() = withTerminal {
        scroll(-3f)
        reveal()
        main {
            val markers = view.mEmulator.history.markers
            val time = markers.first().startedAt
            val name = markers.first().commandName
            render()
            val state = ByteArrayOutputStream().also { TerminalCheckpoint.write(view.mEmulator, it) }
            view.mEmulator.restoreState(TerminalCheckpoint.read(ByteArrayInputStream(state.toByteArray())))
            view.onScreenUpdated()
            assertEquals(time, view.mEmulator.history.markers.first().startedAt)
            assertEquals(name, view.mEmulator.history.markers.first().commandName)
            render()
            val restored = buckets().first()
            assertTrue(view.mEmulator.history.markers.contains(restored.marker))
            val restoredTarget = if (restored.failed) restored.failedMarker else if (restored.running) restored.runningMarker else restored.marker
            tap(bounds("capsule").centerX(), bucketY(restored))
            assertEquals(restoredTarget.anchor.row, view.topRow)
            assertTrue(view.jumpToCommand(view.mEmulator.history.markers[4]))
            render()
            val thumb = bounds("thumb")
            val drag = Drag(bounds("capsule").centerX(), thumb.centerY())
            drag.move(thumb.centerY() - 100)
            assertTrue(view.isHistoryScrollbarDragging)
            val otherBackend = RemoteTerminalBackend(write = {}, resize = { _, _ -> })
            val other = TerminalSession("", "/", emptyArray(), emptyArray(), 1000, TerminalCallbacks {}, otherBackend)
            val original = view.currentSession
            try {
                view.attachSession(other)
                assertFalse(view.isHistoryScrollbarDragging)
                assertFalse(view.isHistoryScrollbarVisible)
                view.attachSession(original)
                assertEquals(markers.size, view.mEmulator.history.markers.size)
                assertTrue(view.jumpToCommand(view.mEmulator.history.markers[4]))
            } finally { otherBackend.close() }
        }
    }

    @Test fun appTakingViewportDuringDragCancelsWithoutLeakingMousePress() = withTerminal {
        scroll(-3f)
        reveal()
        main {
            render()
            val thumb = bounds("thumb")
            val drag = Drag(bounds("capsule").centerX(), thumb.centerY())
            drag.move(thumb.centerY() - 100)
            append("\u001b[?1003h\u001b[?1006h")
            drag.finish()
            assertFalse(view.isHistoryScrollbarDragging)
            assertEquals(0, output.size())
        }
    }

    @Test fun expressiveHandleUsesPaletteAccentAndContrastingCommandGlyph() = withTerminal {
        reveal()
        main {
            render()
            val marker = buckets().first { it.failed }.failedMarker
            for (background in listOf(Color.BLACK, Color.WHITE, Color.rgb(100, 112, 130))) {
                val colors = TerminalChromeColors(androidx.compose.ui.graphics.Color(background))
                view.setHistoryScrollbarColors(colors.onContainer.toArgb(), colors.container.toArgb(),
                    colors.checkedContainer.toArgb(), colors.onCheckedContainer.toArgb())
                assertTrue(view.jumpToCommand(marker))
                val frame = snapshot()
                try {
                    val circle = bounds("visualThumb")
                    assertCircularThumbAt(circle.centerY())
                    assertEquals("The selected command has the palette's active background", colors.checkedContainer.toArgb(),
                        frame.getPixel((circle.left + density()).toInt(), circle.centerY().toInt()))
                    var hasContrastingInk = false
                    for (y in (circle.centerY() - 5 * density()).toInt()..(circle.centerY() + 5 * density()).toInt()) {
                        for (x in (circle.centerX() - 5 * density()).toInt()..(circle.centerX() + 5 * density()).toInt()) {
                            if (frame.getPixel(x, y) == colors.onCheckedContainer.toArgb()) hasContrastingInk = true
                        }
                    }
                    assertTrue("The command glyph remains readable on the active handle", hasContrastingInk)
                } finally { frame.recycle() }
            }
        }
    }

    @Test fun navigationPressMorphRestoresOnCancelAndDisabledArrowKeepsItsSurface() = withTerminal(commands = 0) {
        main { repeat(150) { append("output\r\n") } }
        reveal()
        main {
            val before = arrowSnapshot("previous")
            val top = view.topRow
            val drag = Drag(bounds("previous").centerX(), bounds("previous").centerY())
            val press = field("pressAnimator") as ValueAnimator
            press.pause()
            press.setCurrentFraction(1f)
            arrowSnapshot("previous").let { frame ->
                try { assertFalse("Press changes shape and state layer", before.sameAs(frame)) }
                finally { frame.recycle() }
            }
            assertEquals("Press alone does not navigate", top, view.topRow)
            drag.cancel()
            (field("pressAnimator") as ValueAnimator).apply { pause(); setCurrentFraction(1f) }
            arrowSnapshot("previous").let { frame ->
                try { assertTrue("Cancellation restores the resting shape", before.sameAs(frame)) }
                finally { frame.recycle() }
            }
            before.recycle()
            assertEquals(top, view.topRow)
            val disabled = arrowSnapshot("next")
            Drag(bounds("next").centerX(), bounds("next").centerY()).apply {
                arrowSnapshot("next").let { frame ->
                    try { assertTrue("Disabled navigation retains its surface", disabled.sameAs(frame)) }
                    finally { frame.recycle() }
                }
                cancel()
            }
            disabled.recycle()
        }
    }

    @Test fun expressiveMotionRetainsVelocityAndApplicationTakeoverStopsEveryAnimation() = withTerminal {
        main {
            leftSwipe()
            (field("animator") as ValueAnimator).apply { pause(); setCurrentFraction(0.15f) }
            val position = number("reveal")
            val velocity = number("revealVelocity")
            assertTrue("Entry is a moving spring", velocity > 0)
            rightSwipe()
            (field("animator") as ValueAnimator).apply { pause(); setCurrentFraction(0f) }
            assertEquals("Reversal retains the visible position", position, number("reveal"), 0f)
            assertEquals("Reversal retains momentum", velocity, number("revealVelocity"), 0f)
            leftSwipe()
            (field("animator") as ValueAnimator).apply { pause(); setCurrentFraction(1f) }
            assertTrue(view.jumpToCommand(view.mEmulator.history.markers[20]))
            (field("tooltipAnimator") as ValueAnimator).apply { pause(); setCurrentFraction(0.25f) }
            assertTrue(number("tooltipOpacity") > 0 && number("tooltipOpacity") < 1)
            render()
            val drag = Drag(bounds("visualThumb").centerX(), bounds("visualThumb").centerY())
            (field("pressAnimator") as ValueAnimator).apply { pause(); setCurrentFraction(0.25f) }
            assertTrue(number("pressure") > 0)
            append("\u001b[?1049h")
            assertFalse(view.isHistoryScrollbarVisible)
            assertEquals(0f, number("tooltipOpacity"), 0f)
            assertEquals(0f, number("pressure"), 0f)
            for (name in listOf("animator", "pressAnimator", "tooltipAnimator")) {
                assertFalse("$name stops when the application owns the viewport", (field(name) as ValueAnimator).isStarted)
            }
            drag.cancel()
            assertEquals(0, output.size())
        }
    }

    @Test fun tooltipFadesOutAndClearsItsLabelWithoutClosingTheCapsule() = withTerminal {
        reveal()
        main {
            assertTrue(view.jumpToCommand(view.mEmulator.history.markers[20]))
            (field("tooltipAnimator") as ValueAnimator).apply { pause(); setCurrentFraction(1f) }
            assertEquals(1f, number("tooltipOpacity"), 0f)
            (field("clearTooltip") as Runnable).run()
            (field("tooltipAnimator") as ValueAnimator).apply { pause(); setCurrentFraction(0.25f) }
            assertTrue("The label fades rather than disappearing at once", number("tooltipOpacity") in 0.001f..0.999f)
            (field("tooltipAnimator") as ValueAnimator).setCurrentFraction(1f)
            assertEquals(0f, number("tooltipOpacity"), 0f)
            val bar = overlay()
            assertNull(bar.javaClass.getDeclaredField("tooltipText").apply { isAccessible = true }.get(bar))
            assertTrue(view.isHistoryScrollbarVisible)
            assertEquals(1f, number("reveal"), 0f)
        }
    }

    /** Run separately with the device animator duration scale set to zero. */
    @Test fun disabledMotionAppliesFinalStatesWithoutStartingAnimations() = withTerminal {
        ActivityScenario.launch(AppInfoActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContentView(view) }
            instrumentation.waitForIdleSync()
            org.junit.Assume.assumeFalse("Requires system animator scale 0", ValueAnimator.areAnimatorsEnabled())
            main {
                leftSwipe()
                assertEquals(1f, number("reveal"), 0f)
                assertTrue(view.isHistoryScrollbarVisible)
                assertTrue(view.jumpToCommand(view.mEmulator.history.markers[20]))
                assertEquals(1f, number("tooltipOpacity"), 0f)
                render()
                Drag(bounds("visualThumb").centerX(), bounds("visualThumb").centerY()).apply {
                    assertEquals(1f, number("pressure"), 0f)
                    cancel()
                }
                assertEquals(0f, number("pressure"), 0f)
                assertEquals(0f, number("tooltipOpacity"), 0f)
                assertTrue(view.jumpToCommand(view.mEmulator.history.markers[20]))
                (field("clearTooltip") as Runnable).run()
                assertEquals(0f, number("tooltipOpacity"), 0f)
                rightSwipe()
                assertEquals(0f, number("reveal"), 0f)
                assertFalse(view.isHistoryScrollbarVisible)
                val bar = overlay()
                for (name in listOf("animator", "pressAnimator", "tooltipAnimator")) {
                    val animator = bar.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(bar) as? ValueAnimator
                    assertFalse("$name must not start when animations are disabled", animator?.isStarted == true)
                }
                assertEquals(0, output.size())
            }
        }
    }

    private fun withTerminal(commands: Int = 40, test: () -> Unit) {
        main {
            output.reset()
            backend = RemoteTerminalBackend(write = { output.write(it) }, resize = { _, _ -> })
            val session = TerminalSession("", "/", emptyArray(), emptyArray(), 1000, TerminalCallbacks {}, backend)
            view = TerminalView(instrumentation.targetContext, null).apply {
                HistoryScrollbarMotion(MotionScheme.expressive()).applyTo(this)
                setTerminalViewClient(TerminalInputCallbacks())
                setTextSize(20)
                layout(0, 0, 1000, 1400)
                attachSession(session)
            }
            repeat(commands) { command ->
                append("\u001b]133;A\u0007$ \u001b]133;B\u0007tool-$command --private=value\r\n\u001b]133;C\u0007")
                repeat(4) { append("output-$command-$it\r\n") }
                append("\u001b]133;D;${command % 3}\u0007")
            }
        }
        try { test() } finally { main { backend.close() } }
    }

    private fun main(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else instrumentation.runOnMainSync(block)
    }
    private fun append(text: String) {
        val bytes = text.toByteArray()
        view.mEmulator.append(bytes, bytes.size)
        view.onScreenUpdated()
    }
    private fun density() = view.resources.displayMetrics.density
    private fun overlay(): Any = checkNotNull(TerminalView::class.java.getDeclaredField("mHistoryScrollbar")
        .apply { isAccessible = true }.get(view))
    private fun bounds(name: String): RectF = overlay().let { bar ->
        RectF(bar.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(bar) as RectF)
    }
    private fun number(name: String): Float = overlay().let { bar ->
        bar.javaClass.getDeclaredField(name).apply { isAccessible = true }.getFloat(bar)
    }
    private fun field(name: String): Any = overlay().let { bar ->
        checkNotNull(bar.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(bar))
    }
    private fun markerLabel(marker: CommandMarker): String = overlay().let { bar ->
        bar.javaClass.getDeclaredMethod("markerLabel", CommandMarker::class.java).apply { isAccessible = true }.invoke(bar, marker) as String
    }
    @Suppress("UNCHECKED_CAST")
    private fun buckets() = field("density") as List<HistoryScrollbarModel.Bucket>
    private fun bucketY(bucket: HistoryScrollbarModel.Bucket): Float {
        val bins = overlay().let { bar -> bar.javaClass.getDeclaredField("densityBins").apply { isAccessible = true }.getInt(bar) }
        if (bins == 1) return (number("trackTop") + number("trackBottom")) / 2
        return number("trackTop") + number("thumbHeight") / 2 + bucket.index *
            (number("trackBottom") - number("trackTop") - number("thumbHeight")) / maxOf(1, bins - 1)
    }
    private fun markerY(marker: CommandMarker): Float {
        val height = number("thumbHeight")
        val top = number("trackTop")
        return top + height / 2 + (number("trackBottom") - top - height) *
            (1 + marker.anchor.row!!.toFloat() / view.mEmulator.screen.activeTranscriptRows)
    }
    private fun snapshot(): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }
    private fun render() { snapshot().recycle() }
    private fun assertCircularThumbAt(y: Float) {
        val circle = bounds("visualThumb")
        assertEquals(20 * density(), circle.width(), 0.1f)
        assertEquals(circle.width(), circle.height(), 0.1f)
        assertEquals(bounds("capsule").centerX(), circle.centerX(), 0.1f)
        assertEquals(y, circle.centerY(), 0.1f)
    }
    private fun arrowSnapshot(name: String): Bitmap {
        val full = snapshot()
        try {
            val capsule = bounds("capsule")
            val arrow = bounds(name)
            return Bitmap.createBitmap(full, capsule.left.toInt(), arrow.top.toInt(), capsule.width().toInt(), arrow.height().toInt())
        } finally { full.recycle() }
    }
    private fun scroll(amount: Float) = main {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply { x = 300f; y = 300f; setAxisValue(MotionEvent.AXIS_VSCROLL, -amount) })
        val event = MotionEvent.obtain(0, SystemClock.uptimeMillis(), MotionEvent.ACTION_SCROLL, 1, properties,
            coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        view.onGenericMotionEvent(event)
        event.recycle()
    }
    private fun waitForVisible() {
        val until = SystemClock.uptimeMillis() + 2000
        while (SystemClock.uptimeMillis() < until) {
            var visible = false
            main { visible = view.isHistoryScrollbarVisible && number("reveal") == 1f }
            if (visible) return
            SystemClock.sleep(20)
        }
        fail("Scrollbar did not slide in")
    }
    private fun waitForHidden() {
        val until = SystemClock.uptimeMillis() + 2000
        while (SystemClock.uptimeMillis() < until) {
            var hidden = false
            main { hidden = number("reveal") == 0f }
            if (hidden) return
            SystemClock.sleep(20)
        }
        fail("Scrollbar did not slide out")
    }
    private fun seekPartialSlide(animator: ValueAnimator) {
        for (step in 1..99) {
            animator.setCurrentFraction(step / 100f)
            if (number("reveal") in 0.6f..0.8f) return
        }
        fail("The slide must pass through a visible, partially translated position")
    }
    private fun leftSwipe() = main {
        Drag(view.width * 0.7f, view.height * 0.5f).apply {
            moveTo(view.width * 0.5f, view.height * 0.5f)
            moveTo(view.width * 0.3f, view.height * 0.5f)
            finish()
        }
    }
    private fun rightSwipe() = main {
        Drag(view.width * 0.3f, view.height * 0.5f).apply {
            moveTo(view.width * 0.5f, view.height * 0.5f)
            moveTo(view.width * 0.7f, view.height * 0.5f)
            finish()
        }
    }
    private fun reveal() { leftSwipe(); waitForVisible() }
    private fun tap(x: Float, y: Float) { Drag(x, y).finish() }
    private inner class Drag(private var x: Float, private var y: Float, private val source: Int = InputDevice.SOURCE_TOUCHSCREEN) {
        private val start = SystemClock.uptimeMillis()
        private var time = start
        init { event(MotionEvent.ACTION_DOWN) }
        fun move(destination: Float) { y = destination; time += 30; event(MotionEvent.ACTION_MOVE) }
        fun moveTo(destinationX: Float, destinationY: Float) { x = destinationX; move(destinationY) }
        fun finish() { time += 30; event(MotionEvent.ACTION_UP) }
        fun cancel() { time += 30; event(MotionEvent.ACTION_CANCEL) }
        private fun event(action: Int) {
            val event = MotionEvent.obtain(start, time, action, x, y, 0)
            event.source = source
            view.onTouchEvent(event)
            event.recycle()
        }
    }
}
