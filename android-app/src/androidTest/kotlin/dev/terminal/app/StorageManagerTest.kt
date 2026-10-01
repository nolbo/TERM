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
            ActivityScenario.launch(StorageActivity::class.java).use { scenario ->
                await {
                    if (find { it.text?.toString()?.contains(profile.storageId) == true } != null) true
                    else { find { it.isScrollable }?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); false }
                }
                clickContaining(profile.storageId)
                await { find { it.text?.toString() == profile.name } != null }
                assertNull(find { it.text?.toString() == context.getString(R.string.storage_sessions) })
                val registry = dev.terminal.runtime.session.StorageRegistry(context.filesDir)
                val key = dev.terminal.runtime.session.StorageRegistry.Key(profile.runtime, profile.storageId)
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
                assertNull(registry.transaction { name(key) })
                editName("Storage UI name", true)
                await { registry.transaction { name(key) } == "Storage UI name" }
                scenario.recreate()
                await { find { it.text?.toString() == "Storage UI name" } != null }
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
            }
        } finally { store.delete(profile.id) }
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
