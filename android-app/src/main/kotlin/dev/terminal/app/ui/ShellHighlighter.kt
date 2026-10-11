package dev.terminal.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.eclipse.tm4e.core.grammar.IGrammar
import org.eclipse.tm4e.core.grammar.IStateStack
import org.eclipse.tm4e.core.internal.grammar.tokenattrs.EncodedTokenAttributes
import org.eclipse.tm4e.core.internal.theme.FontStyle as TextMateFontStyle

internal data class ShellHighlight(val text: AnnotatedString, val analyzedLines: Int)

/** Reuse unchanged line states, including a shifted suffix, until a changed shell state converges. */
internal class ShellHighlighter(
    private val grammar: IGrammar,
    private val colors: List<Color>,
    private val tokenization: Mutex,
) {
    private data class Line(val source: String, val before: IStateStack?, val after: IStateStack,
        val styles: List<AnnotatedString.Range<SpanStyle>>)
    private var cache = emptyList<Line>()

    suspend fun highlight(source: String): ShellHighlight = tokenization.withLock {
        val lines = source.split('\n')
        var suffix = 0
        while (suffix < minOf(lines.size, cache.size) &&
            lines[lines.lastIndex - suffix] == cache[cache.lastIndex - suffix].source) suffix++
        var state: IStateStack? = null
        var analyzed = 0
        val next = lines.mapIndexed { index, line ->
            currentCoroutineContext().ensureActive()
            val previous = if (index >= lines.size - suffix)
                cache.getOrNull(index + cache.size - lines.size) else cache.getOrNull(index)
            val entry = if (previous?.source == line && previous.before == state) previous else {
                val result = grammar.tokenizeLine2(line, state, null)
                analyzed++
                val styles = result.tokens.toList().chunked(2).mapIndexedNotNull { token, pair ->
                    val start = pair[0].coerceIn(0, line.length)
                    val end = (result.tokens.getOrNull((token + 1) * 2) ?: line.length).coerceIn(start, line.length)
                    if (start == end) null else AnnotatedString.Range(style(pair[1]), start, end)
                }
                Line(line, state, result.ruleStack, styles)
            }
            state = entry.after
            entry
        }
        val highlighted = buildAnnotatedString {
            append(source)
            var offset = 0
            next.forEach { line ->
                line.styles.forEach { addStyle(it.item, offset + it.start, offset + it.end) }
                offset += line.source.length + 1
            }
        }
        // A cancelled analysis must never publish a partially rebuilt cache.
        currentCoroutineContext().ensureActive()
        cache = next
        ShellHighlight(highlighted, analyzed)
    }

    private fun style(metadata: Int): SpanStyle {
        val font = EncodedTokenAttributes.getFontStyle(metadata)
        val decoration = buildList {
            if (font and TextMateFontStyle.Underline != 0) add(TextDecoration.Underline)
            if (font and TextMateFontStyle.Strikethrough != 0) add(TextDecoration.LineThrough)
        }
        return SpanStyle(
            color = colors.getOrElse(EncodedTokenAttributes.getForeground(metadata)) { Color.Unspecified },
            fontWeight = if (font and TextMateFontStyle.Bold != 0) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (font and TextMateFontStyle.Italic != 0) FontStyle.Italic else FontStyle.Normal,
            textDecoration = if (decoration.isEmpty()) TextDecoration.None else TextDecoration.combine(decoration),
        )
    }
}
