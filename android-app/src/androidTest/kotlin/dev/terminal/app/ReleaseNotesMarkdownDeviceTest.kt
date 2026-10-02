package dev.terminal.app

import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.createReleaseNotesMarkwon
import dev.terminal.app.update.AppUpdateManifest
import dev.terminal.app.update.AppUpdateState
import dev.terminal.app.update.UpdateChannel
import io.noties.markwon.core.spans.BulletListItemSpan
import io.noties.markwon.core.spans.CodeSpan
import io.noties.markwon.core.spans.HeadingSpan
import io.noties.markwon.core.spans.StrongEmphasisSpan
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ReleaseNotesMarkdownDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val fixture = """
        # Markdown fixture

        ## 변경 사항

        - **강조 항목**과 `명령` 및 설명
          이어지는 문장
        - [안내](https://example.com/notes)
        - ![그림 설명](https://example.com/image.png)
    """.trimIndent()

    @Test fun rendersStructureAndJoinsSoftBreaksWithoutRemoteImages() {
        val text = createReleaseNotesMarkwon(context).toMarkdown(fixture)
        assertFalse(text.toString().contains("#"))
        assertFalse(text.toString().contains("**"))
        assertFalse(text.toString().contains("`"))
        assertTrue(text.toString(), text.toString().contains("설명 이어지는 문장"))
        assertTrue(text.toString().contains("그림 설명"))
        assertEquals(2, text.getSpans(0, text.length, HeadingSpan::class.java).size)
        assertEquals(3, text.getSpans(0, text.length, BulletListItemSpan::class.java).size)
        assertEquals(1, text.getSpans(0, text.length, StrongEmphasisSpan::class.java).size)
        assertEquals(1, text.getSpans(0, text.length, CodeSpan::class.java).size)
        assertEquals(1, text.getSpans(0, text.length, ClickableSpan::class.java).size)
    }

    @Test fun onlyTappedWebLinksCanLaunchAnotherApp() {
        instrumentation.runOnMainSync {
            val opened = mutableListOf<Intent>()
            val view = TextView(object : ContextWrapper(context) {
                override fun startActivity(intent: Intent) { opened += intent }
            })
            val markdown = "[web](https://example.com/notes) [private](content://dev.terminal.app/private) [intent](intent://settings)"
            val rendered = createReleaseNotesMarkwon(context).toMarkdown(markdown)
            assertTrue(opened.isEmpty())
            val links = rendered.getSpans(0, rendered.length, ClickableSpan::class.java)
            assertEquals(3, links.size)
            links.forEach { it.onClick(view) }
            assertEquals(1, opened.size)
            assertEquals(Intent.ACTION_VIEW, opened.single().action)
            assertEquals("https://example.com/notes", opened.single().dataString)
            assertTrue(opened.single().hasCategory(Intent.CATEGORY_BROWSABLE))
        }
    }

    @Test fun updateScreenKeepsRenderedNotesAcrossProgressRecreationAndReplacement() {
        val controller = (context.applicationContext as TerminalApplication).appUpdates
        val original = controller.state
        val manifest = AppUpdateManifest.parse("""{"schemaVersion":1,"applicationId":"dev.terminal.app","channel":"dev","commit":"${"1".repeat(40)}","versionCode":1000100,"versionName":"0.5.0+dev.1000100.${"1".repeat(12)}","size":1,"sha256":"${"0".repeat(64)}","minSdk":31}""", UpdateChannel.DEV)
            .copy(releaseNotes = fixture)
        // Supply an update without enabling networking or APK installation in the debug app.
        val setter = controller.javaClass.getDeclaredMethod("setState", AppUpdateState::class.java).apply { isAccessible = true }
        ActivityScenario.launch(AppUpdatesActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { setter.invoke(controller, AppUpdateState.Available(manifest)) }
                assertRendered(waitForMarkdown(scenario, "Markdown fixture"))
                scenario.onActivity { setter.invoke(controller, AppUpdateState.Downloading(manifest, 0)) }
                assertRendered(waitForMarkdown(scenario, "Markdown fixture"))
                scenario.recreate()
                assertRendered(waitForMarkdown(scenario, "Markdown fixture"))
                scenario.onActivity { setter.invoke(controller, AppUpdateState.Ready(manifest, File(context.cacheDir, "unused.apk"))) }
                assertRendered(waitForMarkdown(scenario, "Markdown fixture"))
                val replacement = manifest.copy(releaseNotes = "## Replacement fixture\n\n새 노트")
                scenario.onActivity { setter.invoke(controller, AppUpdateState.Available(replacement)) }
                assertFalse(waitForMarkdown(scenario, "Replacement fixture").text.toString().contains("Markdown fixture"))
            } finally {
                scenario.onActivity { setter.invoke(controller, original) }
            }
        }
    }

    private fun assertRendered(view: TextView) {
        assertSame("Native release notes must use the UI CJK fallback chain", BundledFonts.googleSans(view.context), view.typeface)
        val text = view.text as Spanned
        assertFalse(text.toString().contains("**"))
        assertEquals(2, text.getSpans(0, text.length, HeadingSpan::class.java).size)
        assertTrue(view.isTextSelectable)
        assertTrue(view.width > 0)
        assertTrue(view.height > 0)
    }

    private fun waitForMarkdown(scenario: ActivityScenario<AppUpdatesActivity>, title: String): TextView {
        fun find(view: View): TextView? {
            if (view is TextView && view.text.toString().contains(title) && view.width > 0) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            var view: TextView? = null
            scenario.onActivity { view = find(it.window.decorView) }
            view?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("Rendered release notes not found: $title")
    }
}
