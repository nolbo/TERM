package dev.terminal.app

import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.storage.ScopedDirectory
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

/** Reproduces the Activity stop/rebind caused by the real Android document picker. */
@RunWith(AndroidJUnit4::class)
class SharedFoldersSafUiTest {
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
    private fun await(scroll: Boolean = false, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            find(predicate)?.let { return it }
            if (scroll && find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true) {
                // Compose's animated scroll outlives main-loop idle.
                instrumentation.uiAutomation.waitForIdle(100, 3_000)
            }
            SystemClock.sleep(50)
        }
        throw AssertionError("Shared Folder/SAF element missing")
    }
    private fun click(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) { assertTrue(current.performAction(AccessibilityNodeInfo.ACTION_CLICK)); return }
            current = current.parent
        }
        error("Element is not clickable")
    }
    private fun containsText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (node.text?.toString()?.contains(text) == true) return true
        return (0 until node.childCount).any { node.getChild(it)?.let { child -> containsText(child, text) } == true }
    }
    @Test fun safReturnSubmitsExportAfterServiceReconnectionAndPreservesOriginal() {
        val id = UUID.randomUUID().toString()
        val source = File(context.filesDir.canonicalFile, "retained-workspaces/ROOTLESS-$id")
        assertTrue(source.mkdirs())
        File(source, "fixture.txt").writeText("SAF export regression")
        val name = "TERM-shared-saf-$id.zip"
        var exported: android.net.Uri? = null
        try {
            ActivityScenario.launch(SharedFoldersActivity::class.java).use { scenario ->
                scenario.recreate()
                // Both actions now identify their source in the same navigation row.
                // Select the export row itself rather than a button below an import label.
                click(await(scroll = true) { node -> node.isClickable && containsText(node, "ROOTLESS-$id") &&
                    containsText(node, context.getString(R.string.shared_legacy_export)) })
                val field = await { it.packageName?.toString() == "com.android.documentsui" && it.className?.toString() == "android.widget.EditText" }
                assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
                }))
                click(await { it.packageName?.toString() == "com.android.documentsui" &&
                    (it.text?.toString()?.equals("SAVE", ignoreCase = true) == true || it.text?.toString() == "저장") })
                await { it.packageName?.toString() == context.packageName }
                val deadline = SystemClock.elapsedRealtime() + 15_000
                var bytes: ByteArray? = null
                while (SystemClock.elapsedRealtime() < deadline && bytes == null) {
                    context.contentResolver.persistedUriPermissions.forEach { permission ->
                        runCatching { context.contentResolver.query(permission.uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst() && cursor.getString(0) == name) {
                                exported = permission.uri
                                runCatching { ZipInputStream(context.contentResolver.openInputStream(permission.uri)).use { zip ->
                                    if (zip.nextEntry?.name == "data/fixture.txt") bytes = zip.readBytes()
                                } }
                            }
                        } }
                    }
                    if (bytes == null) SystemClock.sleep(50)
                }
                assertArrayEquals("SAF export regression".toByteArray(), bytes)
                assertEquals("SAF export regression", File(source, "fixture.txt").readText())
                val records = JSONArray(context.getSharedPreferences("shared-folder-jobs", 0).getString("records", "[]"))
                assertTrue((0 until records.length()).map { records.getJSONObject(it) }
                    .any { it.getString("mode") == "EXPORT_LEGACY" && it.getString("state") == "COMPLETED" && it.optLong("bytes") == 21L })
            }
        } finally {
            try {
                exported?.let {
                    DocumentsContract.deleteDocument(context.contentResolver, it)
                    // Downloads revokes document grants as part of successful deletion.
                    runCatching { context.contentResolver.releasePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                }
            } finally { ScopedDirectory.open(source).use { it.clear() }; source.delete() }
        }
    }
}
