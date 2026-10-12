package dev.terminal.app

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.terminal.image.AvfImageVerifier
import dev.terminal.runtime.image.DebianImageDownloadWorker
import dev.terminal.runtime.image.RuntimeSetupCoordinator
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.session.SessionStorage
import dev.terminal.runtime.session.StorageRegistry
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Delayed worker fixtures exercise persisted progress without downloads or a privileged VM. */
@RunWith(AndroidJUnit4::class)
class AvfProfileImageTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val store get() = SessionProfiles(context)
    private val manager get() = WorkManager.getInstance(context)
    private fun label(id: Int) = context.getString(id)
    private fun workName(storageId: String) = "debian-image-download-$storageId"
    private fun work(storageId: String) = manager.getWorkInfosForUniqueWork(workName(storageId)).get(5, TimeUnit.SECONDS)

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && predicate(node)) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.clearCache()
        return walk(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun visible(text: String) = find { it.text?.toString() == text } != null
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        val texts = mutableListOf<String>()
        find { it.text?.toString()?.let(texts::add); false }
        assertTrue("Expected profile image state was not reached; editor: ${resumedEditor()?.intent?.extras}; " +
            "saved fixtures: ${store.all().filter { it.name.startsWith("AVF consent ") }.map { it.name }}; visible text: $texts", condition())
    }

    private fun click(id: Int) {
        await { visible(label(id)) }
        var node = find { it.text?.toString() == label(id) }
        while (node != null && !node.isClickable) node = node.parent
        val bounds = android.graphics.Rect().also(requireNotNull(node)::getBoundsInScreen)
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0).also { event ->
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
                event.recycle()
            }
            SystemClock.sleep(50)
        }
    }

    private fun scrollTo(id: Int) {
        await {
            if (visible(label(id))) true else {
                val bounds = android.graphics.Rect()
                find { it.isScrollable }?.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    val x = bounds.exactCenterX()
                    val start = bounds.top + bounds.height() * 0.75f
                    val end = bounds.top + bounds.height() * 0.5f
                    val downTime = SystemClock.uptimeMillis()
                    fun touch(action: Int, y: Float) {
                        MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).also { event ->
                            event.source = InputDevice.SOURCE_TOUCHSCREEN
                            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
                            event.recycle()
                        }
                    }
                    touch(MotionEvent.ACTION_DOWN, start)
                    for (step in 1..8) {
                        SystemClock.sleep(25)
                        touch(MotionEvent.ACTION_MOVE, start + (end - start) * step / 8)
                    }
                    SystemClock.sleep(150)
                    touch(MotionEvent.ACTION_MOVE, end)
                    touch(MotionEvent.ACTION_UP, end)
                }
                false
            }
        }
    }

    private fun resumedEditor(): EnvironmentDetailActivity? {
        var activity: EnvironmentDetailActivity? = null
        instrumentation.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<EnvironmentDetailActivity>().singleOrNull()
        }
        return activity
    }

    private fun delayedInstall(storageId: String): UUID {
        val request = OneTimeWorkRequestBuilder<DebianImageDownloadWorker>()
            .setInitialDelay(1, TimeUnit.DAYS)
            .addTag("avf-image-storage:$storageId")
            .addTag("avf-image-requested:${System.currentTimeMillis()}")
            .build()
        manager.enqueueUniqueWork(workName(storageId), ExistingWorkPolicy.KEEP, request).result.get(5, TimeUnit.SECONDS)
        return request.id
    }

    private fun cleanup(prefix: String) {
        store.all().filter { it.name.startsWith(prefix) }.forEach { store.delete(it.id) }
        val environments = dev.terminal.runtime.session.EnvironmentRegistry(context.filesDir)
        environments.transaction {
            all().filter { it.name.startsWith(prefix) }.forEach { environment ->
                manager.cancelUniqueWork(workName(environment.storage.id)).result.get(5, TimeUnit.SECONDS)
                delete(environment.id) // fixtures never start a runtime
            }
        }
    }

    @Test fun freshProfileIsSavedBeforeConsentAndDeferringDoesNotInstall() {
        val prefix = "AVF consent ${UUID.randomUUID()}"
        val seed = store.duplicate(store.builtIns().first { it.runtime == SessionRuntime.AVF }).copy(name = "$prefix seed")
        var savedStorage: String? = null
        store.save(seed)
        try {
            ActivityScenario.launch<ProfileEditActivity>(Intent(context, ProfileEditActivity::class.java)
                .putExtra("profileId", seed.id).putExtra("duplicate", true)).use {
                click(R.string.profile_name)
                await { find { it.isEditable } != null }
                assertTrue(requireNotNull(find { it.isEditable }).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "$prefix saved") }))
                click(R.string.confirm)
                scrollTo(R.string.profile_save)
                click(R.string.profile_save)
                await { visible(label(R.string.avf_image_install_confirm_title)) }
                val saved = store.all().single { it.name == "$prefix saved" }
                savedStorage = saved.storageId
                assertNotEquals(seed.storageId, saved.storageId)
                assertTrue(work(saved.storageId).isEmpty())
                val root = SessionStorage.root(context.filesDir, SessionRuntime.AVF, saved.storageId)
                val error = assertThrows(IllegalStateException::class.java) {
                    runBlocking { withTimeout(5_000) { RuntimeSetupCoordinator(context).awaitInstalledImage(root.toPath(), saved.storageId) } }
                }
                assertTrue(error.message!!.contains("환경 설정"))
                assertTrue("Session startup must not enqueue an unapproved installation", work(saved.storageId).isEmpty())
                val editor = requireNotNull(resumedEditor())
                instrumentation.runOnMainSync { editor.recreate() }
                await { resumedEditor()?.let { it !== editor } == true && visible(label(R.string.avf_image_install_confirm_title)) }
                click(R.string.avf_image_install_later)
                scrollTo(R.string.avf_image_not_installed)
                assertFalse(root.exists())
                assertTrue(work(saved.storageId).isEmpty())
                val deferred = requireNotNull(resumedEditor())
                instrumentation.runOnMainSync { deferred.recreate() }
                await { resumedEditor()?.let { it !== deferred } == true }
                scrollTo(R.string.avf_image_not_installed)
                assertFalse(visible(label(R.string.avf_image_install_confirm_title)))
                // A delayed fixture prevents real network activity while the consent action is exercised.
                scrollTo(R.string.avf_image_install)
                click(R.string.avf_image_install)
                await { visible(label(R.string.avf_image_install_confirm_title)) }
                val requestId = delayedInstall(saved.storageId)
                click(R.string.avf_image_install)
                await { !visible(label(R.string.avf_image_install_confirm_title)) }
                scrollTo(R.string.avf_install_waiting)
                assertEquals(requestId, work(saved.storageId).single().id)
                assertEquals(WorkInfo.State.ENQUEUED, work(saved.storageId).single().state)
            }
        } finally {
            resumedEditor()?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
            savedStorage?.let { manager.cancelUniqueWork(workName(it)).result.get(5, TimeUnit.SECONDS) }
            cleanup(prefix)
        }
    }

    @Test fun sharedProfilesRestoreInlineProgressAndUseFilesForInstallationStatus() {
        val prefix = "AVF shared ${UUID.randomUUID()}"
        val first = store.duplicate(store.builtIns().first { it.runtime == SessionRuntime.AVF }).copy(name = "$prefix first")
        val second = store.duplicate(first).copy(name = "$prefix second", storageId = first.storageId)
        val other = store.duplicate(first).copy(name = "$prefix other")
        val root = SessionStorage.root(context.filesDir, SessionRuntime.AVF, first.storageId).toPath()
        store.save(first); store.save(second); store.save(other)
        val requestId = delayedInstall(first.storageId)
        fun open(id: String) = ActivityScenario.launch<EnvironmentDetailActivity>(EnvironmentDetailActivity.intent(context,
            requireNotNull(store.find(id)?.environmentId).value))
        try {
            open(first.id).use { scenario ->
                scrollTo(R.string.avf_install_waiting)
                assertFalse(visible(label(R.string.avf_image_install_confirm_title)))
                scenario.recreate()
                scrollTo(R.string.avf_install_waiting)
                assertEquals(requestId, work(first.storageId).single().id)
            }
            open(second.id).use {
                scrollTo(R.string.avf_install_waiting)
                if (InstrumentationRegistry.getArguments().getString("preview") == "true") {
                    scrollTo(R.string.avf_install_background_note)
                    SystemClock.sleep(20_000)
                }
            }
            open(other.id).use {
                scrollTo(R.string.avf_image_not_installed)
                assertFalse(visible(label(R.string.avf_install_waiting)))
            }
            assertThrows(TimeoutCancellationException::class.java) {
                runBlocking { withTimeout(1_300) { RuntimeSetupCoordinator(context).awaitInstalledImage(root, first.storageId) } }
            }
            assertEquals("Closing a session wait must preserve the approved installation", WorkInfo.State.ENQUEUED,
                work(first.storageId).single().state)
            manager.cancelUniqueWork(workName(first.storageId)).result.get(5, TimeUnit.SECONDS)
            Files.createDirectories(root)
            val names = mapOf("kernel" to "vmlinuz", "initrd" to "initrd.img", "system" to "system.img", "home" to "home.img", "config" to "config.img")
            names.values.forEach { Files.write(root.resolve(it), it.toByteArray()) }
            Files.write(root.resolve("manifest.properties"), ("formatVersion=1\ndistribution=debian\narchitecture=arm64\n" +
                names.entries.joinToString("\n") { (key, file) -> "$key.sha256=${AvfImageVerifier.sha256(root.resolve(file))}" }).toByteArray())
            for (id in listOf(first.id, second.id)) open(id).use {
                scrollTo(R.string.avf_image_installed)
                assertFalse(visible(label(R.string.avf_install_cancelled)))
            }
            Files.delete(root.resolve("system.img"))
            open(second.id).use {
                scrollTo(R.string.avf_image_not_installed)
            }
        } finally {
            manager.cancelUniqueWork(workName(first.storageId)).result.get(5, TimeUnit.SECONDS)
            root.toFile().deleteRecursively()
            cleanup(prefix)
        }
    }
}
