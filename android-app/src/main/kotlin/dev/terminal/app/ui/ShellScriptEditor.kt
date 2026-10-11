package dev.terminal.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ShellScriptEditor(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier, onTextLayout: (TextLayoutResult) -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val highlighter = remember(context.applicationContext) { ShellTextMate.highlighter(context) }
    val code = value.text
    val highlighted by produceState<AnnotatedString?>(initialValue = null, key1 = highlighter, key2 = code) {
        this.value = withContext(Dispatchers.Default) { highlighter.highlight(code).text }
    }
    val transformation = remember(highlighted) { ShellSyntaxTransformation(highlighted) }
    val style = MaterialTheme.typography.bodyLarge.copy(fontFamily = GoogleSansCodeFontFamily,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp, color = colors.onSurface)
    val lineStarts = remember(value.text) { buildList {
        add(0)
        value.text.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
    } }
    val currentLine = lineStarts.indexOfLast { it <= value.selection.end }
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val numberWidth = measurer.measure("9".repeat(lineStarts.size.toString().length), style).size.width
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val scroll = rememberScrollState()
    BoxWithConstraints(modifier.background(colors.surfaceContainerLow).clipToBounds()) {
        val gutterWidth = with(androidx.compose.ui.platform.LocalDensity.current) { numberWidth.toDp() } + 24.dp
        // Scroll the whole document, including the gutter. The field has no internal height limit,
        // so its caret's bring-into-view requests reach this scroll container when the IME opens.
        BasicTextField(value, onValueChange,
            modifier = Modifier.fillMaxWidth().verticalScroll(scroll).heightIn(min = maxHeight)
                .drawBehind {
                    val result = layout?.takeIf { it.layoutInput.text.text == value.text } ?: return@drawBehind
                    val top = 12.dp.toPx()
                    val visibleTop = scroll.value.toFloat()
                    val visibleBottom = visibleTop + maxHeight.toPx()
                    lineStarts.forEachIndexed { index, offset ->
                        // Soft-wrapped continuations get no extra number; use the real text baseline.
                        val baseline = top + result.getLineBaseline(result.getLineForOffset(offset))
                        if (baseline in visibleTop - style.lineHeight.toPx()..visibleBottom + style.lineHeight.toPx()) {
                            val number = measurer.measure((index + 1).toString(), style)
                            drawText(number, color = if (index == currentLine) colors.onSurface else colors.onSurfaceVariant,
                                topLeft = Offset(gutterWidth.toPx() - 12.dp.toPx() - number.size.width,
                                    baseline - number.firstBaseline))
                        }
                    }
                }.padding(start = gutterWidth + 8.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            textStyle = style,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            cursorBrush = SolidColor(colors.primary), visualTransformation = transformation,
            onTextLayout = { layout = it; onTextLayout(it) })
    }
}

/** Highlighting only adds spans. IME composition, selections and raw source retain their offsets. */
internal class ShellSyntaxTransformation(private val highlighted: AnnotatedString?) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val snapshot = highlighted?.takeIf { it.text == text.text }
        return TransformedText(if (snapshot == null) text else buildAnnotatedString {
            append(text)
            snapshot.spanStyles.forEach { addStyle(it.item, it.start, it.end) }
        }, OffsetMapping.Identity)
    }
}
