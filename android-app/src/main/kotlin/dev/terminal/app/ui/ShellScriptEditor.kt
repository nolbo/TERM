package dev.terminal.app.ui

import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import dev.terminal.app.BundledFonts
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.ScrollEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

@Composable
internal fun ShellScriptEditor(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier, onReady: (ShellCodeEditor) -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    val change by rememberUpdatedState(onValueChange)
    var scrollX by rememberSaveable { mutableIntStateOf(0) }
    var scrollY by rememberSaveable { mutableIntStateOf(0) }
    AndroidView(modifier = modifier, factory = { context ->
        ShellCodeEditor(context).apply {
            colorScheme = ShellTextMate.colorScheme(context)
            setEditorLanguage(ShellTextMate.language(context))
            typefaceText = BundledFonts.googleSansCode(context)
            typefaceLineNumber = typefaceText
            setTextSize(14f)
            setLineSpacing((20 * resources.displayMetrics.scaledDensity - rowHeight).coerceAtLeast(0f), 1f)
            setWordwrap(true)
            isLineNumberEnabled = true
            setScalable(false)
            setCursorAnimationEnabled(false)
            props.symbolPairAutoCompletion = false
            render(value)
            val initialX = scrollX
            val initialY = scrollY
            doOnLayout {
                scroller.startScroll(0, 0, initialX.coerceIn(0, scrollMaxX), initialY.coerceIn(0, scrollMaxY), 0)
                scroller.abortAnimation()
                invalidate()
            }
            subscribeEvent(ContentChangeEvent::class.java) { _, _ -> publish { change(it) } }
            subscribeEvent(SelectionChangeEvent::class.java) { _, _ -> publish { change(it) } }
            subscribeEvent(ScrollEvent::class.java) { event, _ -> scrollX = event.endX; scrollY = event.endY }
            onReady(this)
        }
    }, onRelease = { it.release() }, update = { editor ->
        editor.colorScheme.apply {
            setColor(EditorColorScheme.WHOLE_BACKGROUND, colors.surfaceContainerLow.toArgb())
            setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, colors.surfaceContainerLow.toArgb())
            setColor(EditorColorScheme.LINE_DIVIDER, colors.surfaceContainerLow.toArgb())
            setColor(EditorColorScheme.TEXT_NORMAL, colors.onSurface.toArgb())
            setColor(EditorColorScheme.LINE_NUMBER, colors.onSurfaceVariant.toArgb())
            setColor(EditorColorScheme.LINE_NUMBER_CURRENT, colors.onSurface.toArgb())
            setColor(EditorColorScheme.CURRENT_LINE, colors.surfaceContainer.toArgb())
            setColor(EditorColorScheme.SELECTION_INSERT, colors.primary.toArgb())
            setColor(EditorColorScheme.SELECTION_HANDLE, colors.primary.toArgb())
            setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, colors.secondaryContainer.toArgb())
            setColor(EditorColorScheme.SCROLL_BAR_THUMB, colors.outline.toArgb())
            setColor(EditorColorScheme.TEXT_ACTION_WINDOW_BACKGROUND, colors.surfaceContainerHigh.toArgb())
            setColor(EditorColorScheme.TEXT_ACTION_WINDOW_ICON_COLOR, colors.onSurface.toArgb())
        }
        editor.render(value)
    })
}

/** Keep the native IME/undo state intact when Compose publishes the editor's own changes back. */
internal class ShellCodeEditor(context: Context) : CodeEditor(context) {
    private var synchronizing = false

    fun render(value: TextFieldValue) {
        synchronizing = true
        try {
            if (text.toString() != value.text) setText(value.text)
            if (cursor.left != value.selection.min || cursor.right != value.selection.max) {
                val left = text.indexer.getCharPosition(value.selection.min)
                val right = text.indexer.getCharPosition(value.selection.max)
                setSelectionRegion(left.line, left.column, right.line, right.column, false)
            }
        } finally { synchronizing = false }
    }

    fun publish(change: (TextFieldValue) -> Unit) {
        if (!synchronizing) change(TextFieldValue(text.toString(), TextRange(cursor.left, cursor.right)))
    }

    // Sora exposes editable text, but does not implement Android's set-selection action.
    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo = super.createAccessibilityNodeInfo().apply {
        className = "android.widget.EditText"
        addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_SET_SELECTION) {
            val start = arguments?.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, -1) ?: -1
            val end = arguments?.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, -1) ?: -1
            if (start !in 0..text.length || end !in 0..text.length) return false
            val left = text.indexer.getCharPosition(minOf(start, end))
            val right = text.indexer.getCharPosition(maxOf(start, end))
            setSelectionRegion(left.line, left.column, right.line, right.column)
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }
}
