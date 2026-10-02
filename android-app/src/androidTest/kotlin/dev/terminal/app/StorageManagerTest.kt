package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.session.SessionStorage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class StorageManagerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun legacyLaunchUsesMigratedEnvironmentAndFreshStorageRemainsIndependent() {
        val store = SessionProfiles(context)
        val old = store.builtIns().first { it.runtime == SessionRuntime.NATIVE }
            .copy(id = UUID.randomUUID().toString(), name = "Legacy upgrade fixture")
        val fresh = store.duplicate(old).copy(name = "Fresh upgrade fixture")
        val marker = UUID.randomUUID().toString()
        val fileName = "upgrade-fixture-${UUID.randomUUID()}"
        val sentinel = File(SessionStorage.legacyRoot(context.filesDir, old.runtime), "home/$fileName")
        requireNotNull(sentinel.parentFile).mkdirs()
        sentinel.writeText(marker)
        store.save(old)
        store.save(fresh)
        val queue = LinkedBlockingQueue<IRuntimeService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { queue.offer(IRuntimeService.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        val service = requireNotNull(queue.poll(15, TimeUnit.SECONDS))
        val sessionIds = mutableListOf<String>()
        val output = StringBuffer()
        val failures = LinkedBlockingQueue<String>()
        val listener = object : dev.terminal.runtime.ipc.ITerminalStateListener.Stub() {
            override fun onCheckpoint(state: android.os.ParcelFileDescriptor) {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(state).use { it.readBytes() }
            }
            override fun onOutput(bytes: ByteArray) { output.append(String(bytes, Charsets.UTF_8)) }
            override fun onResize(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) = Unit
            override fun onExited(code: Int) = Unit
            override fun onError(message: String) { failures.offer(message) }
        }
        fun sessions() = JSONArray(service.sessionsJson).let { a -> List(a.length()) { a.getJSONObject(it) } }
        try {
            service.startRuntime()
            await { service.state == "READY" }
            val migrated = StorageItem.parse(service.storageJson).single { row -> row.profiles.any { it.first == old.id } }
            assertNotEquals("legacy", migrated.id)
            assertEquals(migrated.id, store.find(old.id)!!.storageId)
            assertEquals(marker, sentinel.readText())
            assertEquals(SessionStorage.legacyRoot(context.filesDir, old.runtime), SessionStorage.root(context.filesDir, old.runtime, migrated.id))
            // An old cached launch request resolves to the adopted identity before Session creation.
            service.createSessionFromSpec(old.resolve().toJson().toString(), 24, 80)
            await { sessions().any { it.optString("profileId") == old.id } }
            val oldSession = sessions().single { it.optString("profileId") == old.id }
            val id = oldSession.getString("id").also { sessionIds += it }
            assertEquals(migrated.id, oldSession.getJSONObject("launchSpec").getString("storageId"))
            service.attachTerminal(id, listener, 24, 80, 10, 20, "{}")
            service.writeSessionInput(id, "cat \"\$HOME/$fileName\"\n".toByteArray())
            await { output.contains(marker) }
            assertTrue(service.deleteStorage(old.runtime.wireName, migrated.id).isNotEmpty())
            assertEquals(marker, sentinel.readText())
            service.createSessionFromSpec(fresh.resolve().toJson().toString(), 24, 80)
            await { sessions().any { it.optString("profileId") == fresh.id } }
            val freshId = sessions().single { it.optString("profileId") == fresh.id }.getString("id").also { sessionIds += it }
            assertFalse(File(SessionStorage.root(context.filesDir, fresh.runtime, fresh.storageId), "home/$fileName").exists())
            service.attachTerminal(freshId, listener, 24, 80, 10, 20, "{}")
            val suffix = UUID.randomUUID().toString()
            service.writeSessionInput(freshId, "if [ ! -e \"\$HOME/$fileName\" ]; then printf '%s%s' 'FRESH_' '$suffix'; fi\n".toByteArray())
            await { output.contains("FRESH_$suffix") }
            assertTrue(failures.isEmpty())
            // Restored legacy detail intents follow the same environment after adoption.
            ActivityScenario.launch<StorageDetailActivity>(StorageDetailActivity.intent(context, old.runtime.wireName, "legacy")).use {
                await { find { it.text?.toString()?.contains(migrated.id) == true } != null }
                assertNull(find { it.text?.toString() == context.getString(R.string.storage_missing) })
            }
        } finally {
            sessionIds.forEach { service.detachTerminal(it, listener); service.terminateSession(it) }
            await { sessions().none { it.getString("id") in sessionIds } }
            store.delete(old.id); store.delete(fresh.id)
            service.deleteStorage(fresh.runtime.wireName, fresh.storageId)
            sentinel.delete()
            context.unbindService(connection)
        }
    }
    @Test fun newProfileSelectsAnExistingStorageFromSettingsRows() {
        val store = SessionProfiles(context)
        val target = store.duplicate(store.builtIns().first()).copy(name = "Existing storage fixture")
        val profileName = "Shared profile ${UUID.randomUUID()}"
        val storageName = "Existing environment ${UUID.randomUUID()}"
        val registry = dev.terminal.runtime.session.StorageRegistry(context.filesDir)
        store.save(target, newStorageName = storageName)
        val before = registry.transaction { keys() }
        try {
            ActivityScenario.launch(ProfileEditActivity::class.java).use {
                clickContaining(context.getString(R.string.profile_name))
                await { find { it.isEditable } != null }
                assertTrue(requireNotNull(find { it.isEditable }).performAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                    android.os.Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, profileName) }))
                clickContaining(context.getString(R.string.save))
                await { find { it.isEditable } == null }
                await {
                    if (find { it.text?.toString() == context.getString(R.string.profile_storage) && it.parent?.isClickable == true } != null) true
                    else { find { it.isScrollable }?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); false }
                }
                clickContaining(context.getString(R.string.profile_storage))
                clickContaining(context.getString(R.string.profile_storage_existing))
                clickContaining(context.getString(R.string.save))
                await { find { it.text?.toString() == context.getString(R.string.cancel) } == null }
                assertNull(find { it.text?.toString() == context.getString(R.string.profile_storage_name) })
                await {
                    if (find { it.text?.toString() == target.storageId } != null) true
                    else { find { it.isScrollable }?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); false }
                }
                clickContaining(target.storageId)
                assertTrue(store.all().none { it.name == profileName })
                await {
                    if (find { it.text?.toString() == context.getString(R.string.profile_save) } != null) true
                    else { find { it.isScrollable }?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); false }
                }
                clickContaining(context.getString(R.string.profile_save))
                await { store.all().any { it.name == profileName } }
                assertEquals(target.storageId, store.all().single { it.name == profileName }.storageId)
                assertEquals(before, registry.transaction { keys() })
                assertEquals(storageName, registry.transaction { name(dev.terminal.runtime.session.StorageRegistry.Key(target.runtime, target.storageId)) })
            }
        } finally {
            store.all().filter { it.name == profileName }.forEach { store.delete(it.id) }
            store.delete(target.id)
        }
    }
    @Test fun profileSwitchKeepsLiveSessionAndServiceDeletesOnlyIdleOrphans() {
        val store = SessionProfiles(context)
        val original = store.duplicate(store.builtIns().first { it.runtime == SessionRuntime.NATIVE }).copy(name = "Storage manager test")
        val fresh = original.copy(storageId = UUID.randomUUID().toString())
        val shared = store.duplicate(fresh).copy(name = "Storage shared test", storageId = fresh.storageId)
        val queue = LinkedBlockingQueue<IRuntimeService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { queue.offer(IRuntimeService.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        store.save(original)
        assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        val service = requireNotNull(queue.poll(15, TimeUnit.SECONDS))
        val sessionIds = mutableListOf<String>()
        fun sessions() = JSONArray(service.sessionsJson).let { a -> List(a.length()) { a.getJSONObject(it) } }
        fun storage(id: String) = StorageItem.parse(service.storageJson).firstOrNull { it.runtime == original.runtime.wireName && it.id == id }
        try {
            service.startRuntime()
            await { service.state == "READY" }
            service.createSessionFromSpec(original.resolve().toJson().toString(), 24, 80)
            await { sessions().any { it.optString("profileId") == original.id } }
            sessionIds += sessions().first { it.optString("profileId") == original.id }.getString("id")
            val oldRoot = SessionStorage.root(context.filesDir, original.runtime, original.storageId)
            val sentinel = File(oldRoot, "sentinel").apply { writeText("original environment") }
            assertTrue(service.deleteStorage(original.runtime.wireName, original.storageId).isNotEmpty())
            store.save(fresh)
            assertEquals(original.storageId, sessions().first { it.getString("id") == sessionIds.first() }.getJSONObject("launchSpec").getString("storageId"))
            assertEquals(0, storage(original.storageId)!!.profiles.size)
            assertEquals(1, storage(original.storageId)!!.sessions.size)
            assertTrue(storage(original.storageId)!!.used)
            assertTrue(service.deleteStorage(original.runtime.wireName, original.storageId).isNotEmpty())
            assertEquals("original environment", sentinel.readText())
            service.createSessionFromSpec(fresh.resolve().toJson().toString(), 24, 80)
            await { sessions().count { it.optString("profileId") == original.id } == 2 }
            sessionIds += sessions().first { it.optString("profileId") == original.id && it.getString("id") != sessionIds.first() }.getString("id")
            assertFalse(File(SessionStorage.root(context.filesDir, fresh.runtime, fresh.storageId), "sentinel").exists())
            store.save(shared)
            assertEquals(2, storage(fresh.storageId)!!.profiles.size)
            service.terminateSession(sessionIds.last())
            await { sessions().none { it.getString("id") == sessionIds.last() } }
            assertTrue(service.deleteStorage(fresh.runtime.wireName, fresh.storageId).isNotEmpty()) // Profiles still reference the idle storage.
            store.delete(original.id); store.delete(shared.id)
            service.writeSessionInput(sessionIds.first(), "exit\n".toByteArray())
            await { sessions().any { it.getString("id") == sessionIds.first() && it.getString("state") == "EXITED" } }
            assertFalse(storage(original.storageId)!!.used)
            assertTrue(service.measureStorage(original.runtime.wireName, original.storageId) >= sentinel.length())
            assertEquals("", service.deleteStorage(original.runtime.wireName, original.storageId))
            assertFalse(oldRoot.exists())
            assertNull(storage(original.storageId))
            assertEquals("", service.deleteStorage(fresh.runtime.wireName, fresh.storageId))
            assertTrue(service.deleteStorage(SessionRuntime.NATIVE.wireName, "legacy").isNotEmpty())
            assertTrue(runCatching { store.save(original) }.isFailure) // A stale editor cannot resurrect a deleted storage ID.
        } finally {
            sessionIds.forEach { service.terminateSession(it) }
            store.delete(original.id); store.delete(shared.id)
            await { sessions().none { it.getString("id") in sessionIds } }
            listOf(original, fresh).forEach { if (storage(it.storageId) != null) service.deleteStorage(it.runtime.wireName, it.storageId) }
            context.unbindService(connection)
        }
    }
    @Test fun storageDetailShowsReferencesAndBlocksDeletionUntilProfileIsRemoved() {
        val store = SessionProfiles(context)
        val profile = store.duplicate(store.builtIns().first()).copy(name = "Storage UI fixture")
        store.save(profile)
        try {
            ActivityScenario.launch(StorageActivity::class.java).use {
                await {
                    if (find { it.text?.toString()?.contains(profile.storageId) == true } != null) true
                    else { find { it.isScrollable }?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); false }
                }
                clickContaining(profile.storageId)
                await { resumedActivity() is StorageDetailActivity }
                await { find { it.text?.toString() == profile.name } != null }
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                await { resumedActivity() is StorageActivity && find { it.text?.toString()?.contains(profile.storageId) == true } != null }
                clickContaining(profile.storageId)
                await { resumedActivity() is StorageDetailActivity && find { it.text?.toString() == profile.name } != null }
                assertNull(find { it.text?.toString() == context.getString(R.string.storage_sessions) })
                val registry = dev.terminal.runtime.session.StorageRegistry(context.filesDir)
                val key = dev.terminal.runtime.session.StorageRegistry.Key(profile.runtime, profile.storageId)
                val defaultName = registry.transaction { name(key) }
                assertTrue(requireNotNull(defaultName).startsWith("Storage for ${profile.name}"))
                fun editName(value: String, save: Boolean) {
                    clickContaining(context.getString(R.string.storage_name))
                    await { find { it.isEditable } != null }
                    assertTrue(requireNotNull(find { it.isEditable }).performAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                        android.os.Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
                    clickContaining(context.getString(if (save) R.string.save else R.string.cancel))
                    await { find { it.isEditable } == null }
                }
                editName("Discarded name", false)
                assertEquals(defaultName, registry.transaction { name(key) })
                editName("Storage UI name", true)
                await { registry.transaction { name(key) } == "Storage UI name" }
                await { find { it.text?.toString() == "Storage UI name" } != null }
                assertNull(find { it.text?.toString()?.contains("The coroutine scope left the composition") == true })
                val detail = requireNotNull(resumedActivity() as? StorageDetailActivity)
                InstrumentationRegistry.getInstrumentation().runOnMainSync { detail.recreate() }
                await { resumedActivity() is StorageDetailActivity && resumedActivity() !== detail && find { it.text?.toString() == "Storage UI name" } != null }
                editName("", true)
                await { registry.transaction { name(key) } == null }
                val deleteLabel = context.getString(R.string.storage_delete)
                assertNotNull(find { it.text?.toString() == deleteLabel })
                assertFalse(clickableAncestor(requireNotNull(find { it.text?.toString() == deleteLabel })).isEnabled)
                store.delete(profile.id)
                await { clickableAncestor(requireNotNull(find { it.text?.toString() == deleteLabel })).isEnabled }
                clickContaining(deleteLabel)
                await { find { it.text?.toString() == context.getString(R.string.storage_delete_confirm, profile.storageId) } != null }
                clickContaining(context.getString(R.string.cancel))
                assertTrue(dev.terminal.runtime.session.StorageRegistry(context.filesDir).transaction {
                    keys().any { it.storageId == profile.storageId }
                })
                clickContaining(deleteLabel)
                await { find { it.text?.toString() == context.getString(R.string.storage_delete_confirm, profile.storageId) } != null }
                clickContaining(deleteLabel)
                await { dev.terminal.runtime.session.StorageRegistry(context.filesDir).transaction {
                    keys().none { it.storageId == profile.storageId }
                } }
                await { resumedActivity() is StorageActivity }
            }
        } finally {
            (resumedActivity() as? StorageDetailActivity)?.let { detail ->
                InstrumentationRegistry.getInstrumentation().runOnMainSync { detail.finish() }
            }
            store.delete(profile.id)
        }
    }
    private fun resumedActivity(): android.app.Activity? {
        var resumed: android.app.Activity? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).singleOrNull()
        }
        return resumed
    }
    private fun clickableAncestor(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo {
        var current = node
        while (!current.isClickable && current.parent != null) current = current.parent
        return current
    }
    private fun clickContaining(text: String) {
        await {
            val label = find { it.text?.toString()?.contains(text) == true &&
                (it.isClickable || it.parent?.isClickable == true) }
            val button = label?.let { if (it.isClickable) it else it.parent }
            button?.isEnabled == true && button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        }
    }
    private fun find(predicate: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean): android.view.accessibility.AccessibilityNodeInfo? {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        fun search(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) search(node.getChild(i))?.let { return it }
            return null
        }
        return search(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (!check()) { check(SystemClock.elapsedRealtime() < deadline) { "Timed out" }; SystemClock.sleep(100) }
    }
}
