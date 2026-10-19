package dev.terminal.app

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.app.ui.LocalizedSettingsEntry
import dev.terminal.app.ui.settingsSearchEntries
import dev.terminal.runtime.storage.LegacyWorkspaces
import dev.terminal.runtime.storage.ScopedDirectory
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedFoldersLegacyOptionsUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && predicate(node)) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun awaitReady() {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            if (find { it.text?.toString() == context.getString(R.string.shared_folder_create) } != null) return
            SystemClock.sleep(50)
        }
        error("Shared Directory list did not load")
    }

    @Test fun retainedWorkspaceDataDoesNotOfferOptionsAfterRecreationOrAppearInSearch() {
        val id = UUID.randomUUID().toString()
        val source = File(context.filesDir.canonicalFile, "retained-workspaces/ROOTLESS-$id")
        assertTrue(source.mkdirs())
        File(source, "keep").writeText("retained user data")
        try {
            assertTrue(LegacyWorkspaces.sources(context.filesDir.canonicalFile, emptyList()).any { it.directory == source })
            ActivityScenario.launch(SharedFoldersActivity::class.java).use { scenario ->
                repeat(2) { pass ->
                    if (pass > 0) scenario.recreate()
                    awaitReady()
                    for (page in 0 until 32) {
                        assertNull(find { it.text?.toString()?.contains("/workspace") == true })
                        assertNull(find { it.text?.toString()?.contains(id) == true })
                        val scrolling = find { it.isScrollable }
                        val moved = scrolling?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
                        if (!moved) break
                        check(page < 31) { "Shared Directory list exceeded test scroll limit" }
                        instrumentation.uiAutomation.waitForIdle(100, 3_000)
                    }
                    assertEquals("retained user data", File(source, "keep").readText())
                }
            }
            val sharedEntries = settingsSearchEntries.filter { it.target.screen == "shared-folders" }
            assertTrue(sharedEntries.isNotEmpty())
            assertTrue(sharedEntries.none { entry ->
                LocalizedSettingsEntry(entry, context.getString(entry.title), entry.summary?.let(context::getString).orEmpty(),
                    entry.path.joinToString { context.getString(it) }).matches("/workspace")
            })
        } finally { ScopedDirectory.open(source).use { it.clear() }; source.delete() }
    }
}
