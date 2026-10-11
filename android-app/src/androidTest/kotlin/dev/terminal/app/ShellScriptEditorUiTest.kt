package dev.terminal.app

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.DroidTheme
import dev.terminal.app.ui.GoogleSansCodeFontFamily
import dev.terminal.app.ui.LocalTerminalMonospaceFontFamily
import dev.terminal.app.ui.ShellScriptEditor
import dev.terminal.app.ui.ShellSyntaxTransformation
import dev.terminal.app.ui.ShellTextMate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.eclipse.tm4e.core.grammar.IStateStack
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShellScriptEditorUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun rendersHighlightedCodeWithGoogleSansCodeEvenWhenTheUserFontDiffers() {
        val script = "iW0@ \nif true; then\n\tprintf '%s\\n' \"\$HOME\"\nfi # 한글🙂"
        withEditor(script) { editor ->
            await { (editor.layout.get()?.layoutInput?.text?.spanStyles?.map { it.item.color }?.distinct()?.size ?: 0) >= 6 }
            val layout = requireNotNull(editor.layout.get())
            assertEquals(GoogleSansCodeFontFamily, layout.layoutInput.style.fontFamily)
            assertEquals(script, layout.layoutInput.text.text)
            assertEquals(script, editor.draft.get().text)
            val width = layout.getBoundingBox(0).width
            for (index in 1..4) assertEquals("Fixed-width code cells", width, layout.getBoundingBox(index).width, 0.5f)
        }
    }

    @Test fun grammarDistinguishesNestedShellSyntaxAndQuotedHeredocs() {
        val lines = listOf(
            "build() {",
            "  local result=\"\$(printf '%s' \"\${items[0]:-fallback}\")\"",
            "  if [[ \$result =~ ^[0-9]+\$ ]]; then",
            "    echo \$((count + 1)) | sed 's/foo/bar/g' > output",
            "  fi",
            "}",
            "cat <<'LITERAL'",
            "\$HOME # literal 한글🙂",
            "LITERAL",
            "cat <<EXPAND",
            "\$HOME",
            "EXPAND",
            "echo 'unfinished",
            "\$HOME still a string",
            "'; if true; then echo done; fi",
        )
        val grammar = ShellTextMate.grammar(context)
        var state: IStateStack? = null
        val tokens = lines.map { line ->
            val result = grammar.tokenizeLine(line, state, null)
            state = result.ruleStack
            result.tokens
        }
        fun scopes(line: Int, word: String) = tokens[line].first {
            val position = lines[line].indexOf(word)
            it.startIndex <= position && position < it.endIndex
        }.scopes
        assertTrue(scopes(0, "build").any { it.startsWith("entity.name.function") })
        assertTrue("A substitution retains the inner command's own scope",
            scopes(1, "printf").any { it.startsWith("entity.name.function") || it.startsWith("support.function") })
        assertTrue(scopes(1, "items").any { it.startsWith("variable") })
        assertTrue(scopes(2, "if").any { it.startsWith("keyword.control") })
        assertTrue(scopes(3, "1").any { it.startsWith("constant.numeric") })
        assertTrue(scopes(7, "HOME").any { it.startsWith("string") })
        assertFalse(scopes(7, "HOME").any { it.startsWith("variable") })
        assertFalse(scopes(7, "#").any { it.startsWith("comment") })
        assertTrue(scopes(10, "HOME").any { it.startsWith("variable") })
        assertTrue(scopes(13, "HOME").any { it.startsWith("string") })
        assertFalse(scopes(13, "HOME").any { it.startsWith("variable") })
        assertTrue(scopes(14, "if").any { it.startsWith("keyword.control") })
    }

    @Test fun incrementalEditsRecolorVariablesWithoutChangingInputOffsets() {
        val initial = "echo \"\$HOME\""
        withEditor(initial) { editor ->
            // Color-only changes need not trigger a new TextLayoutResult. Check the painted glyphs.
            await { highlightPixelCounts().first > 0 }
            val initialStringPixels = highlightPixelCounts().second
            val edited = TextFieldValue("echo '\$HOME'", TextRange(7, 10), TextRange(7, 10))
            instrumentation.runOnMainSync { editor.edit.get().invoke(edited) }
            await {
                val (variablePixels, stringPixels) = highlightPixelCounts()
                variablePixels == 0 && stringPixels > initialStringPixels
            }
            assertEquals(edited, editor.draft.get())
            val transformed = ShellSyntaxTransformation(editor.layout.get()!!.layoutInput.text)
                .filter(androidx.compose.ui.text.AnnotatedString(edited.text))
            for (offset in 0..edited.text.length) {
                assertEquals(offset, transformed.offsetMapping.originalToTransformed(offset))
                assertEquals(offset, transformed.offsetMapping.transformedToOriginal(offset))
            }
            instrumentation.runOnMainSync { editor.edit.get().invoke(TextFieldValue(initial)) }
            await { highlightPixelCounts().first > 0 }
            assertEquals(initial, editor.draft.get().text)
        }
    }

    private fun highlightPixelCounts(): Pair<Int, Int> {
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        return try {
            val pixels = IntArray(screenshot.width * screenshot.height)
            screenshot.getPixels(pixels, 0, screenshot.width, 0, 0, screenshot.width, screenshot.height)
            val variable = Color(0xFFD8BDF8).toArgb()
            val string = Color(0xFFE6C384).toArgb()
            pixels.count { it == variable } to pixels.count { it == string }
        } finally { screenshot.recycle() }
    }

    @Test fun highlightingReusesStableLinesAndPropagatesChangedHeredocState() = runBlocking {
        val highlighter = ShellTextMate.highlighter(context)
        val script = "echo \"\$HOME\"\ncat <<'END'\n\$HOME # literal\nEND\necho done"
        withContext(Dispatchers.Default) {
            val first = highlighter.highlight(script)
            assertEquals(5, first.analyzedLines)
            assertEquals(first.text, highlighter.highlight(script).text)
            assertEquals(0, highlighter.highlight(script).analyzedLines)
            val edited = script.replace("<<'END'", "<<END")
            val next = highlighter.highlight(edited)
            assertTrue("Stable lines are reused", next.analyzedLines < 5)
            val offset = edited.indexOf("HOME", edited.indexOf('\n') + 1)
            assertEquals(Color(0xFFD8BDF8), next.text.spanStyles.last { it.start <= offset && it.end > offset }.item.color)
            val fresh = ShellTextMate.highlighter(context).highlight(edited)
            assertEquals("Incremental and full parsing agree", fresh.text, next.text)
            val inserted = "echo inserted\n" + edited
            assertEquals(ShellTextMate.highlighter(context).highlight(inserted).text, highlighter.highlight(inserted).text)
        }
    }

    private class Editor {
        val layout = AtomicReference<TextLayoutResult>()
        val draft = AtomicReference<TextFieldValue>()
        val edit = AtomicReference<(TextFieldValue) -> Unit>()
    }

    private fun withEditor(script: String, test: (Editor) -> Unit) {
        val editor = Editor()
        editor.draft.set(TextFieldValue(script))
        ActivityScenario.launch<ToolbarCommandEditActivity>(ToolbarCommandEditActivity.intent(context)).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme {
                    CompositionLocalProvider(LocalTerminalMonospaceFontFamily provides FontFamily.Serif) {
                        var value by remember { mutableStateOf(editor.draft.get()) }
                        val change = { draft: TextFieldValue -> value = draft; editor.draft.set(draft) }
                        editor.edit.set(change)
                        ShellScriptEditor(value, change, Modifier.fillMaxSize()) { editor.layout.set(it) }
                    }
                }
            } }
            await { editor.layout.get() != null }
            test(editor)
        }
    }

    private fun await(details: () -> String = { "" }, check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (check()) return
            SystemClock.sleep(100)
        }
        error("Shell grammar/styles did not reach the expected state. ${details()}")
    }
}
