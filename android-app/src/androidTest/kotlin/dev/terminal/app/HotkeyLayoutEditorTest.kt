package dev.terminal.app

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.TerminalHapticFeedbackProvider
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.roundToInt

/** Exercises real move/resize gestures and restores the original preferences after every test. */
@RunWith(AndroidJUnit4::class)
class HotkeyLayoutEditorTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val density get() = context.resources.displayMetrics.density
    private val initial = HotkeyLayout(columns = 4, keys = listOf(
        Hotkey("A", "A", bounds = HotkeyBounds(0f, 0f)),
        Hotkey("B", "B", bounds = HotkeyBounds(3f, 0f)),
    ))

    @Test fun keySnapsIntoDistantEmptySpaceWithoutMovingItsNeighborAndSaves() = withEditor { _ ->
        val a = keyBounds(0, "A")
        val b = keyBounds(1, "B")
        val pitch = (b.left - a.left) / 3f
        drag(a, a.exactCenterX() + pitch * 1.25f, a.exactCenterY() + 44 * density * 2.25f)
        val moved = keyBounds(0, "A")
        assertTrue(moved.top > b.bottom + 40 * density)
        assertEquals(b, keyBounds(1, "B"))
        save()
        val layout = readHotkeyLayout(preferences())
        assertEquals(listOf("A", "B"), layout.keys.map { it.name })
        assertEquals(HotkeyBounds(1f, 2f), layout.keys[0].bounds)
        assertEquals(initial.keys[1], layout.keys[1])
    }

    @Test fun cornerDragChangesWidthAndHeightAndKeepsTheOppositeCorner() = withEditor { _ ->
        val a = keyBounds(0, "A")
        val b = keyBounds(1, "B")
        tap(a)
        val corner = awaitLabel(context.getString(R.string.hotkey_resize_handle, "A", context.getString(R.string.hotkey_bottom_right)))
        val pitch = (b.left - a.left) / 3f
        drag(corner, corner.exactCenterX() + pitch * 1.25f, corner.exactCenterY() + 44 * density * 1.65f, longPress = false)
        val resized = keyBounds(0, "A")
        assertEquals(a.left, resized.left)
        assertEquals(a.top, resized.top)
        assertTrue(resized.width() > a.width() * 1.7)
        assertTrue(resized.height() > a.height() * 2)
        save()
        val bounds = readHotkeyLayout(preferences()).keys[0].bounds!!
        assertEquals(0f, bounds.x, 0.01f)
        assertEquals(0f, bounds.y, 0.01f)
        assertEquals(2f, bounds.width, 0f)
        assertEquals(3f, bounds.height, 0f)
    }

    @Test fun gesturesShorterThanHalfACellKeepTheKeyUnchanged() = withEditor { _ ->
        val a = keyBounds(0, "A")
        val pitch = (keyBounds(1, "B").left - a.left) / 3f
        drag(a, a.exactCenterX() + pitch * 0.3f, a.exactCenterY() + 44 * density * 0.3f)
        assertEquals(a, keyBounds(0, "A"))
        tap(a)
        val corner = awaitLabel(context.getString(R.string.hotkey_resize_handle, "A", context.getString(R.string.hotkey_bottom_right)))
        drag(corner, corner.exactCenterX() + pitch * 0.3f, corner.exactCenterY() + 44 * density * 0.3f, longPress = false)
        assertEquals(a, keyBounds(0, "A"))
        save()
        assertEquals(initial, readHotkeyLayout(preferences()))
    }

    @Test fun bodyDragTracksTheFingerBetweenCellsAndSnapsOnlyWhenReleased() = withEditor { _ ->
        val a = keyBounds(0, "A")
        val b = keyBounds(1, "B")
        val pitch = (b.left - a.left) / 3f
        val rowPitch = 44 * density
        val dropX = a.exactCenterX() + pitch * 1.25f
        val dropY = a.exactCenterY() + rowPitch * 1.25f
        drag(a, dropX, dropY, whileHeld = { down ->
            // Reverse direction and cross cell boundaries without snapping the visual path.
            for ((dx, dy) in listOf(1.25f to 1.25f, 0.3f to 0.3f, 1.65f to 0.55f, 1.25f to 1.25f)) {
                val x = a.exactCenterX() + pitch * dx
                val y = a.exactCenterY() + rowPitch * dy
                event(down, MotionEvent.ACTION_MOVE, x, y)
                awaitCondition("The dragged key did not follow the fractional pointer position") {
                    val current = keyBounds(0, "A")
                    abs(current.exactCenterX() - x) <= 2 && abs(current.exactCenterY() - y) <= 2
                }
                assertEquals(b, keyBounds(1, "B"))
                assertEquals(initial, readHotkeyLayout(preferences()))
            }
        })
        awaitCondition("The released key did not settle on the grid") {
            val current = keyBounds(0, "A")
            abs(current.left - a.left - pitch) <= 2 && abs(current.top - a.top - rowPitch) <= 2
        }
        save()
        assertEquals(HotkeyBounds(1f, 1f), readHotkeyLayout(preferences()).keys[0].bounds)
    }

    @Test fun accessibilityActionsMoveAndResizeByOneCell() = withEditor { _ ->
        for (label in listOf(R.string.hotkey_move_down, R.string.hotkey_move_right, R.string.hotkey_wider, R.string.hotkey_taller)) {
            var node = findNode(context.getString(R.string.hotkey_edit_description, 1, "A", "A"))
            var performed = false
            while (node != null && !performed) {
                val action = node.actionList.firstOrNull { it.label?.toString() == context.getString(label) }
                if (action != null) performed = node.performAction(action.id)
                node = node.parent
            }
            assertTrue("Missing or rejected action: ${context.getString(label)}", performed)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(180)
        }
        save()
        val layout = readHotkeyLayout(preferences())
        assertEquals(HotkeyBounds(1f, 1f, 2f, 2f), layout.keys[0].bounds)
        assertEquals(initial.keys[1], layout.keys[1])
    }

    @Test fun selectedKeyIconsOpenTheSimplifiedDialogAndDeleteOnlyTheDraftUntilSaved() = withEditor { _ ->
        val a = keyBounds(0, "A")
        val editLabel = context.getString(R.string.hotkey_edit)
        val deleteLabel = context.getString(R.string.hotkey_remove)
        tap(a)
        val edit = awaitLabel(editLabel)
        val delete = awaitLabel(deleteLabel)
        assertTrue("Edit and delete actions must sit beside each other", delete.left >= edit.right)
        assertTrue(findNode(editLabel)?.text.isNullOrEmpty())
        assertTrue(findNode(deleteLabel)?.text.isNullOrEmpty())
        tap(edit)
        awaitLabel(context.getString(R.string.hotkey_supported_keys))
        assertNull("Delete belongs in the preview", findNode(deleteLabel))
        for (preset in listOf("Ctrl+C", "Ctrl+D", "Ctrl+L", "Ctrl", "Alt", "Tab", "Shift", "Space", "Ctrl+Space")) {
            assertNull("The edit dialog must not offer preset buttons: $preset", findNode(preset))
        }
        tap(awaitLabel(context.getString(R.string.cancel)))
        tap(awaitLabel(deleteLabel))
        awaitCondition("The deleted key is still in the preview") {
            findLabel(context.getString(R.string.hotkey_edit_description, 1, "A", "A")) == null
        }
        keyBounds(0, "B")
        assertEquals(initial, readHotkeyLayout(preferences()))
        tap(keyBounds(0, "B"))
        // The tooltip anchor stays enabled; exercise the disabled button beneath it.
        tap(awaitLabel(deleteLabel))
        keyBounds(0, "B")
        save()
        assertEquals(listOf(initial.keys[1]), readHotkeyLayout(preferences()).keys)
    }

    @Test fun cancellingRestoresSwappedKeysAndDroppingOnANeighborExchangesPositions() = withEditor { _ ->
        val a = keyBounds(0, "A")
        val b = keyBounds(1, "B")
        drag(a, b.exactCenterX(), b.exactCenterY(), cancel = true, whileHeld = {
            assertEquals(a, keyBounds(1, "B"))
            assertEquals(initial, readHotkeyLayout(preferences()))
        })
        awaitCondition("Cancelled keys did not return") { a == keyBounds(0, "A") && b == keyBounds(1, "B") }
        assertEquals(a, keyBounds(0, "A"))
        assertEquals(b, keyBounds(1, "B"))
        drag(a, b.exactCenterX(), b.exactCenterY())
        assertEquals(initial, readHotkeyLayout(preferences()))
        save()
        assertEquals(listOf(HotkeyBounds(3f, 0f), HotkeyBounds(0f, 0f)), hotkeyBounds(readHotkeyLayout(preferences())))
    }

    @Test fun hoveringAtAnEdgeInsertsAndHoveringAtTheCenterSwapsWithinTheSameCell() {
        val row = initial.copy(keys = listOf("A", "B", "C", "D").mapIndexed { index, name ->
            Hotkey(name, name, bounds = HotkeyBounds(index.toFloat(), 0f))
        })
        withEditor(row) { _ ->
            val a = keyBounds(0, "A")
            val b = keyBounds(1, "B")
            val c = keyBounds(2, "C")
            val d = keyBounds(3, "D")
            val edge = c.left + (b.left - a.left) * 0.05f
            drag(a, edge, c.exactCenterY(), cancel = true, whileHeld = {
                assertEquals(a, keyBounds(1, "B"))
                assertEquals(c, keyBounds(2, "C"))
                assertEquals(edge, keyBounds(0, "A").exactCenterX(), 2f)
            })
            awaitCondition("Cancelled insertion did not restore the row") { a == keyBounds(0, "A") && b == keyBounds(1, "B") }
            drag(a, edge, c.exactCenterY(), whileHeld = { down ->
                assertEquals(a, keyBounds(1, "B"))
                assertEquals(c, keyBounds(2, "C"))
                assertEquals(edge, keyBounds(0, "A").exactCenterX(), 2f)
                event(down, MotionEvent.ACTION_MOVE, c.exactCenterX(), c.exactCenterY())
                awaitCondition("Center hover did not swap") { a == keyBounds(2, "C") && b == keyBounds(1, "B") }
                event(down, MotionEvent.ACTION_MOVE, edge, c.exactCenterY())
                awaitCondition("Edge hover did not reopen the insertion slot") { a == keyBounds(1, "B") && c == keyBounds(2, "C") }
                assertEquals(d, keyBounds(3, "D"))
                assertEquals(row, readHotkeyLayout(preferences()))
            })
            save()
            assertEquals(listOf(HotkeyBounds(1f, 0f), HotkeyBounds(0f, 0f), HotkeyBounds(2f, 0f), HotkeyBounds(3f, 0f)),
                hotkeyBounds(readHotkeyLayout(preferences())))
        }
    }

    @Test fun movingAndResizingOnlyCommitWhenThePointerIsReleased() {
        val adjacent = initial.copy(keys = listOf(initial.keys[0], initial.keys[1].copy(bounds = HotkeyBounds(1f, 0f))))
        val model = mutableStateOf(adjacent)
        val committed = AtomicReference(adjacent)
        val changes = AtomicInteger()
        val viewport = AtomicReference<ComposeRect?>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme { TerminalHapticFeedbackProvider {
                    EditableHotkeyPreview(model.value, rememberScrollState(), viewport::get,
                        onChange = { model.value = it; committed.set(it); changes.incrementAndGet() }, onEdit = {},
                        modifier = Modifier.padding(32.dp).systemBarsPadding().onGloballyPositioned {
                            viewport.set(ComposeRect(it.localToRoot(Offset.Zero), Size(it.size.width.toFloat(), it.size.height.toFloat())))
                        })
                } }
            } }
            val a = keyBounds(0, "A")
            val b = keyBounds(1, "B")
            val pitch = (b.left - a.left).toFloat()
            drag(a, b.exactCenterX(), b.exactCenterY(), whileHeld = {
                assertEquals(a, keyBounds(1, "B"))
                assertEquals(0, changes.get())
                assertEquals(adjacent, committed.get())
            })
            assertEquals(1, changes.get())
            assertEquals(listOf(HotkeyBounds(1f, 0f), HotkeyBounds(0f, 0f)), hotkeyBounds(committed.get()))
            tap(keyBounds(0, "A"))
            val corner = awaitLabel(context.getString(R.string.hotkey_resize_handle, "A", context.getString(R.string.hotkey_bottom_left)))
            drag(corner, corner.exactCenterX() - pitch * 0.8f, corner.exactCenterY() + 44 * density * 1.2f,
                longPress = false, whileHeld = {
                    assertEquals(1, changes.get())
                    assertEquals(1f, committed.get().keys[0].bounds!!.width, 0f)
                    assertTrue(keyBounds(1, "B").top > b.top + 60 * density)
                })
            assertEquals(2, changes.get())
            assertEquals(listOf(HotkeyBounds(0f, 0f, 2f, 2f), HotkeyBounds(0f, 2f)), hotkeyBounds(committed.get()))
        }
    }

    @Test fun activeAndDisplacedKeysAnimateTheirPositionAndSize() {
        assumeTrue(android.provider.Settings.Global.getFloat(context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f)
        val layout = initial.copy(keys = listOf(initial.keys[0], initial.keys[1].copy(bounds = HotkeyBounds(1f, 0f))))
        val target = mutableStateOf(layout)
        val draggingIndex = mutableStateOf<Int?>(null)
        val frames = CopyOnWriteArrayList<Pair<Int, Rect>>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme { TerminalHapticFeedbackProvider {
                    HotkeyGrid(animatedHotkeyLayout(target.value, draggingIndex.value), modifier = Modifier.padding(32.dp).systemBarsPadding(),
                        cellModifier = { index -> Modifier.onGloballyPositioned {
                            val origin = it.localToRoot(Offset.Zero)
                            frames.add(index to Rect(origin.x.roundToInt(), origin.y.roundToInt(),
                                origin.x.roundToInt() + it.size.width, origin.y.roundToInt() + it.size.height))
                        } }) {}
                } }
            } }
            val a = awaitLabel("A: A")
            val b = awaitLabel("B: B")
            val pitch = b.left - a.left
            scenario.onActivity {
                draggingIndex.value = 0
                target.value = layout.copy(keys = layout.keys.toMutableList().apply {
                    set(0, get(0).copy(bounds = HotkeyBounds(0.3f, 0.3f)))
                })
            }
            awaitCondition("Direct dragging did not display fractional coordinates") {
                val current = findLabel("A: A") ?: return@awaitCondition false
                abs(current.left - a.left - pitch * 0.3f) <= 2 && abs(current.top - a.top - 44 * density * 0.3f) <= 2
            }
            SystemClock.sleep(100)
            frames.clear()
            scenario.onActivity { draggingIndex.value = null; target.value = layout }
            awaitCondition("The released key did not return to its grid cell") { findLabel("A: A") == a }
            assertTrue("Release must animate from the fractional drag position", frames.any { (index, rect) ->
                index == 0 && rect.left > a.left && rect.left < a.left + pitch * 0.3f &&
                    rect.top > a.top && rect.top < a.top + 44 * density * 0.3f
            })
            frames.clear()
            scenario.onActivity { target.value = placeHotkey(layout, 0, HotkeyBounds(1f, 0f, 2f, 2f)) }
            awaitCondition("Animated keys did not reach their placement") {
                val currentA = findLabel("A: A") ?: return@awaitCondition false
                val currentB = findLabel("B: B") ?: return@awaitCondition false
                abs(currentA.left - b.left) <= 2 && abs(currentA.width() - a.width() - pitch) <= 2 &&
                    abs(currentA.height() - (84 * density).roundToInt()) <= 2 &&
                    abs(currentB.top - b.top - (88 * density).roundToInt()) <= 2
            }
            assertTrue("The active key position must animate", frames.any { (index, rect) -> index == 0 && rect.left > a.left && rect.left < b.left })
            assertTrue("The active key width must animate", frames.any { (index, rect) -> index == 0 && rect.width() > a.width() && rect.width() < a.width() + pitch })
            assertTrue("The active key height must animate", frames.any { (index, rect) -> index == 0 && rect.height() > a.height() && rect.height() < 84 * density })
            assertTrue("The displaced key must animate", frames.any { (index, rect) -> index == 1 && rect.top > b.top && rect.top < b.top + 88 * density })
        }
    }

    @Test fun floatingToolbarRestoresTheLatestSaveAfterRecreation() = withEditor { scenario ->
        val restoreLabel = context.getString(R.string.hotkey_restore_saved)
        val restore = awaitLabel(restoreLabel)
        val add = awaitLabel(context.getString(R.string.hotkey_add))
        val saveButton = awaitLabel(context.getString(R.string.save))
        assertTrue(restore.right <= add.left && add.right <= saveButton.left)
        assertEquals(restore.centerY(), saveButton.centerY())
        assertTrue(findNode(context.getString(R.string.save))?.text.isNullOrEmpty())
        assertNull("The page must no longer offer Cancel", findNode(context.getString(R.string.cancel)))

        val a = keyBounds(0, "A")
        drag(a, a.exactCenterX(), a.exactCenterY() + 44 * density * 2)
        assertEquals(initial, readHotkeyLayout(preferences()))
        tap(awaitLabel(restoreLabel))
        awaitCondition("Restore did not return the entire draft to its saved placement") { keyBounds(0, "A") == a }
        assertEquals(initial, readHotkeyLayout(preferences()))

        drag(a, a.exactCenterX(), a.exactCenterY() + 44 * density * 2)
        save()
        val saved = readHotkeyLayout(preferences())
        assertEquals(HotkeyBounds(0f, 2f), saved.keys[0].bounds)
        scenario.onActivity { assertFalse("Saving must keep the editor open", it.isFinishing) }

        val moved = keyBounds(0, "A")
        drag(moved, moved.exactCenterX(), moved.exactCenterY() + 44 * density)
        scenario.recreate()
        scrollToPreview()
        tap(awaitLabel(restoreLabel))
        awaitCondition("Restore must use the latest saved layout after recreation") {
            abs(keyBounds(0, "A").top - keyBounds(1, "B").top - 88 * density) <= 2
        }
        save()
        assertEquals(saved, readHotkeyLayout(preferences()))
    }

    @Test fun floatingToolbarAddsOnlyToTheDraftAndRestoresAllEditedSettings() = withEditor { _ ->
        tap(awaitLabel(context.getString(R.string.hotkey_add)))
        awaitLabel(context.getString(R.string.hotkey_name))
        val nameField = findNode { it.isEditable } ?: error("Missing hotkey name field")
        assertTrue(nameField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "New key")
        }))
        save()
        awaitLabel(context.getString(R.string.hotkey_edit_description, 3, "New key", "CTRL+C"))
        assertEquals(initial, readHotkeyLayout(preferences()))
        val columns = findNode(context.getString(R.string.hotkey_columns)) ?: error("Missing canvas columns slider")
        assertTrue(columns.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 6f)
        }))
        val font = findNode(context.getString(R.string.hotkey_font_size)) ?: error("Missing font size slider")
        assertTrue(font.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 20f)
        }))
        awaitLabel("${context.getString(R.string.hotkey_columns)}: 6")
        awaitLabel("${context.getString(R.string.hotkey_font_size)}: 20")
        tap(awaitLabel(context.getString(R.string.hotkey_restore_saved)))
        awaitCondition("Restore did not discard the new key") {
            findNode(context.getString(R.string.hotkey_edit_description, 3, "New key", "CTRL+C")) == null
        }
        save()
        assertEquals("Restore must include canvas columns and font size", initial, readHotkeyLayout(preferences()))

        tap(awaitLabel(context.getString(R.string.hotkey_add)))
        awaitLabel(context.getString(R.string.hotkey_name))
        assertTrue(requireNotNull(findNode { it.isEditable }).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Saved key")
        }))
        save()
        awaitLabel(context.getString(R.string.hotkey_edit_description, 3, "Saved key", "CTRL+C"))
        save()
        assertEquals(listOf("A", "B", "Saved key"), readHotkeyLayout(preferences()).keys.map { it.name })
    }

    @Test fun draftPlacementSurvivesRecreationAndBackDoesNotPersistIt() = withEditor { scenario ->
        val a = keyBounds(0, "A")
        drag(a, a.exactCenterX(), a.exactCenterY() + 44 * density * 2.5f)
        scenario.recreate()
        scrollToPreview()
        assertTrue(keyBounds(0, "A").top > keyBounds(1, "B").bottom + 40 * density)
        assertEquals(initial, readHotkeyLayout(preferences()))
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        tap(awaitLabel(context.getString(R.string.confirm)))
        assertEquals(initial, readHotkeyLayout(preferences()))
    }

    @Test fun renderedKeysKeepTheirGapsHeightsAndInputTargets() {
        val selected = AtomicInteger(-1)
        val layout = HotkeyLayout(columns = 4, keys = listOf(
            Hotkey("A", "A", bounds = HotkeyBounds(0f, 0f)),
            Hotkey("B", "B", bounds = HotkeyBounds(2.5f, 3.25f, 1.25f, 2.5f)),
        ))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                MaterialTheme { TerminalHapticFeedbackProvider {
                    HotkeyGrid(layout, modifier = Modifier.padding(32.dp)) { selected.set(it) }
                } }
            } }
            val a = awaitLabel("A: A")
            val b = awaitLabel("B: B")
            assertEquals(44 * density * 3.25f, (b.top - a.top).toFloat(), 2f)
            assertEquals((44 * 2.5f - 4) * density, b.height().toFloat(), 2f)
            assertTrue(b.left > a.right)
            val hole = Rect(a.left, a.bottom + 10, a.right, b.top - 10)
            tap(hole)
            assertEquals(-1, selected.get())
            tap(b)
            assertEquals(1, selected.get())
        }
    }

    private fun preferences() = context.getSharedPreferences(SettingsDetailActivity.TERMINAL_PREFERENCES, 0)
    private fun save() = tap(awaitLabel(context.getString(R.string.save)))

    private fun withEditor(layout: HotkeyLayout = initial, block: (ActivityScenario<SettingsDetailActivity>) -> Unit) {
        val original = preferences().getString(KEY_HOTKEYS, null)
        preferences().edit().putString(KEY_HOTKEYS, serializeHotkeyLayout(layout)).commit()
        try {
            val intent = Intent(context, SettingsDetailActivity::class.java)
                .putExtra(SettingsDetailActivity.EXTRA_CATEGORY, SettingsDetailActivity.HOTKEYS)
            ActivityScenario.launch<SettingsDetailActivity>(intent).use { scenario ->
                scenario.onActivity {
                    androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView)
                        .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
                }
                SystemClock.sleep(600)
                scrollToPreview()
                block(scenario)
            }
        } finally {
            preferences().edit().apply { if (original == null) remove(KEY_HOTKEYS) else putString(KEY_HOTKEYS, original) }.commit()
        }
    }

    private fun scrollToPreview() {
        repeat(6) {
            instrumentation.waitForIdleSync()
            val a = findLabel(context.getString(R.string.hotkey_edit_description, 1, "A", "A"))
            val screen = Rect().also { awaitRoot().getBoundsInScreen(it) }
            if (a != null && a.height() > 35 * density && a.top > screen.top + 80 * density && a.bottom < screen.bottom - 250 * density) return
            val down = SystemClock.uptimeMillis()
            val x = screen.width() * 0.8f
            val y = screen.height() * 0.8f
            event(down, MotionEvent.ACTION_DOWN, x, y)
            repeat(12) { step ->
                event(down, MotionEvent.ACTION_MOVE, x, y - screen.height() * 0.35f * (step + 1) / 12f)
                SystemClock.sleep(16)
            }
            event(down, MotionEvent.ACTION_UP, x, y - screen.height() * 0.35f)
            SystemClock.sleep(250)
        }
        throw AssertionError("Preview is not visible")
    }

    private fun keyBounds(index: Int, name: String) = awaitLabel(context.getString(R.string.hotkey_edit_description, index + 1, name, name))
    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(32)
        }
        throw AssertionError(message)
    }

    private fun awaitRoot(): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.uiAutomation.rootInActiveWindow?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("Active window did not appear")
    }

    private fun awaitLabel(label: String): Rect {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            findLabel(label)?.takeIf { it.width() > 0 && it.height() > 0 }?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("UI label did not appear: $label")
    }

    private fun findLabel(label: String): Rect? = findNode(label)?.let { node -> Rect().also(node::getBoundsInScreen) }

    private fun findNode(label: String): AccessibilityNodeInfo? = findNode {
        it.text?.toString() == label || it.contentDescription?.toString() == label
    }

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            node.refresh()
            if (predicate(node)) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }

    private fun tap(rect: Rect) {
        val down = SystemClock.uptimeMillis()
        event(down, MotionEvent.ACTION_DOWN, rect.exactCenterX(), rect.exactCenterY())
        event(down, MotionEvent.ACTION_UP, rect.exactCenterX(), rect.exactCenterY())
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
    }

    private fun drag(from: Rect, x: Float, y: Float, longPress: Boolean = true, cancel: Boolean = false, whileHeld: ((Long) -> Unit)? = null) {
        val down = SystemClock.uptimeMillis()
        event(down, MotionEvent.ACTION_DOWN, from.exactCenterX(), from.exactCenterY())
        if (longPress) SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150)
        repeat(16) { step ->
            val fraction = (step + 1) / 16f
            event(down, MotionEvent.ACTION_MOVE,
                from.exactCenterX() + (x - from.exactCenterX()) * fraction,
                from.exactCenterY() + (y - from.exactCenterY()) * fraction)
            SystemClock.sleep(20)
        }
        if (whileHeld != null) {
            SystemClock.sleep(400)
            whileHeld(down)
        }
        event(down, if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, x, y)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(180)
    }

    private fun event(down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
    }
}
