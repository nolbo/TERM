package dev.terminal.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.DroidTheme
import dev.terminal.app.ui.GoogleSansCodeFontFamily
import dev.terminal.app.ui.LocalTerminalMonospaceFontFamily
import dev.terminal.app.ui.ShellScriptEditor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShellScriptEditorUiTest {
    @Test fun rendersHighlightedCodeWithGoogleSansCodeEvenWhenTheUserFontDiffers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val script = "iW0@ \nif true; then\n\tprintf '%s\\n' \"\$HOME\"\nfi # 한글🙂"
        val layout = AtomicReference<TextLayoutResult>()
        val ready = CountDownLatch(1)
        ActivityScenario.launch<ToolbarCommandEditActivity>(ToolbarCommandEditActivity.intent(context)).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme {
                    CompositionLocalProvider(LocalTerminalMonospaceFontFamily provides FontFamily.Serif) {
                        ShellScriptEditor(TextFieldValue(script), {}, Modifier.fillMaxSize()) {
                            layout.set(it)
                            ready.countDown()
                        }
                    }
                }
            } }
            assertTrue("Code was laid out on the device", ready.await(5, TimeUnit.SECONDS))
            val result = requireNotNull(layout.get())
            assertEquals(GoogleSansCodeFontFamily, result.layoutInput.style.fontFamily)
            assertEquals(script, result.layoutInput.text.text)
            assertTrue(result.layoutInput.text.spanStyles.map { it.item.color }.distinct().size >= 4)
            val cellWidth = result.getBoundingBox(0).width
            for (offset in 1..4) assertEquals("The editor retains fixed-width cells", cellWidth, result.getBoundingBox(offset).width, 0.5f)
        }
    }
}
