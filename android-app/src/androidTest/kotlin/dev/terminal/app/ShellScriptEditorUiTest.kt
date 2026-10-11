package dev.terminal.app

import android.graphics.Paint
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.DroidTheme
import dev.terminal.app.ui.LocalTerminalMonospaceFontFamily
import dev.terminal.app.ui.ShellCodeEditor
import dev.terminal.app.ui.ShellScriptEditor
import dev.terminal.app.ui.ShellTextMate
import io.github.rosemoe.sora.lang.styling.TextStyle
import org.eclipse.tm4e.core.grammar.IStateStack
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        withEditor(script) { editor, _ ->
            onUi {
                assertSame(BundledFonts.googleSansCode(context), editor.typefaceText)
                assertSame(editor.typefaceText, editor.typefaceLineNumber)
                assertEquals(14 * context.resources.displayMetrics.scaledDensity, editor.textSizePx, 0.5f)
                assertEquals(script, editor.text.toString())
                val paint = Paint().apply { typeface = editor.typefaceText; textSize = editor.textSizePx }
                val width = paint.measureText("i")
                for (char in "W0@ ") assertEquals(width, paint.measureText(char.toString()), 0.5f)
            }
            await {
                val colors = mutableSetOf<Int>()
                onUi {
                    val reader = editor.styles?.spans?.read()
                    if (reader != null) for (line in 0 until editor.lineCount) {
                        reader.getSpansOnLine(line).forEach { colors.add(editor.colorScheme.getColor(TextStyle.getForegroundColorId(it.style))) }
                    }
                }
                colors.size >= 6
            }
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

    @Test fun incrementalEditsAndUndoRecolorVariablesWithoutLosingTheDraft() {
        val initial = "echo \"\$HOME\""
        withEditor(initial) { editor, draft ->
            fun colorAtHome(): Int? {
                var color: Int? = null
                onUi {
                    val span = editor.styles?.spans?.read()?.getSpansOnLine(0)?.lastOrNull { it.column <= 7 }
                    if (span != null) color = editor.colorScheme.getColor(TextStyle.getForegroundColorId(span.style))
                }
                return color
            }
            await { colorAtHome() == 0xFFD8BDF8.toInt() }
            onUi {
                editor.text.beginBatchEdit()
                try {
                    editor.text.replace(5, 6, "'")
                    editor.text.replace(11, 12, "'")
                } finally { editor.text.endBatchEdit() }
            }
            await { draft.get().text == "echo '\$HOME'" && colorAtHome() == 0xFFE6C384.toInt() }
            onUi { assertTrue(editor.canUndo()); editor.undo() }
            await { draft.get().text == initial && colorAtHome() == 0xFFD8BDF8.toInt() }
            onUi { assertEquals(initial, editor.text.toString()) }
        }
    }

    private fun withEditor(script: String, test: (ShellCodeEditor, AtomicReference<TextFieldValue>) -> Unit) {
        val editor = AtomicReference<ShellCodeEditor>()
        val draft = AtomicReference(TextFieldValue(script))
        val ready = CountDownLatch(1)
        ActivityScenario.launch<ToolbarCommandEditActivity>(ToolbarCommandEditActivity.intent(context)).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme {
                    CompositionLocalProvider(LocalTerminalMonospaceFontFamily provides FontFamily.Serif) {
                        var value by remember { mutableStateOf(draft.get()) }
                        ShellScriptEditor(value, { value = it; draft.set(it) }, Modifier.fillMaxSize()) {
                            editor.set(it); ready.countDown()
                        }
                    }
                }
            } }
            assertTrue("Native editor was created", ready.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            test(requireNotNull(editor.get()), draft)
        }
    }

    private fun onUi(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (check()) return
            SystemClock.sleep(100)
        }
        error("Shell grammar/styles did not reach the expected state")
    }
}
