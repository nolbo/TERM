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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SessionProfilesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun newProfileUsesSettingsRowsAndPreservesDialogDraftUntilSaved() {
        val store = SessionProfiles(context)
        val name = "New profile ${java.util.UUID.randomUUID()}"
        val storageName = "Development ${java.util.UUID.randomUUID()}"
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun find(predicate: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean): android.view.accessibility.AccessibilityNodeInfo? {
            fun walk(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                if (node == null) return null
                if (predicate(node)) return node
                for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
                return null
            }
            instrumentation.waitForIdleSync()
            return walk(instrumentation.uiAutomation.rootInActiveWindow)
        }
        fun click(label: Int) {
            await {
                var node = find { it.text?.toString() == context.getString(label) }
                while (node != null && !node.isClickable) node = node.parent
                node?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
            }
        }
        fun typeName(value: String = name) {
            await { find { it.isEditable } != null }
            assertTrue(requireNotNull(find { it.isEditable }).performAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                android.os.Bundle().apply {
                    putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }))
        }
        try {
            ActivityScenario.launch(ProfileEditActivity::class.java).use { activity ->
                await { find { it.text?.toString() == context.getString(R.string.profile_name) } != null }
                assertNull(find { it.isEditable })
                click(R.string.profile_name); typeName(); click(R.string.cancel)
                await { find { it.isEditable } == null }
                assertNull(find { it.text?.toString() == name })
                click(R.string.profile_name); typeName()
                activity.recreate()
                await { find { it.isEditable && it.text?.toString() == name } != null }
                click(R.string.save)
                await { find { it.isEditable } == null }
                assertTrue(store.all().none { it.name == name })
                activity.recreate()
                await { find { it.text?.toString() == name } != null }
                await {
                    if (find { it.text?.toString() == context.getString(R.string.profile_storage_name) } != null) true
                    else { find { it.isScrollable }?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); false }
                }
                click(R.string.profile_storage_name); typeName(storageName); click(R.string.save)
                await { find { it.isEditable } == null }
                val registry = dev.terminal.runtime.session.StorageRegistry(context.filesDir)
                assertFalse(registry.transaction { keys().any { this.name(it) == storageName } })
                activity.recreate()
                await { find { it.text?.toString() == storageName } != null }
                await {
                    if (find { it.text?.toString() == context.getString(R.string.profile_save) } != null) true
                    else { find { it.isScrollable }?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); false }
                }
                click(R.string.profile_save)
                await { store.all().any { it.name == name } }
                val saved = store.all().single { it.name == name }
                assertEquals("auto", saved.icon)
                assertNotEquals("legacy", saved.storageId)
                assertEquals(storageName, registry.transaction { this.name(dev.terminal.runtime.session.StorageRegistry.Key(saved.runtime, saved.storageId)) })
            }
        } finally { store.all().filter { it.name == name }.forEach { store.delete(it.id) } }
    }

    @Test fun editorSelectsBaseAndExplicitStorageSharing() {
        val store = SessionProfiles(context)
        val profile = store.duplicate(store.builtIns().first()).copy(name = "Storage editor")
        val target = store.duplicate(store.builtIns().first {
            it.runtime == dev.terminal.runtime.session.SessionRuntime.ROOTLESS_UBUNTU
        }).copy(name = "Shared Ubuntu fixture")
        val storageName = "Ubuntu fixture ${java.util.UUID.randomUUID().toString().take(8)}"
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun find(predicate: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean): android.view.accessibility.AccessibilityNodeInfo? {
            fun walk(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                if (node == null) return null
                if (predicate(node)) return node
                for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
                return null
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            return walk(automation.rootInActiveWindow)
        }
        fun click(text: String) {
            await {
                var node = find { it.text?.toString() == text }
                while (node != null && !node.isClickable) node = node.parent
                node?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
            }
        }
        fun scrollStorageList() {
            val bounds = android.graphics.Rect()
            requireNotNull(find { it.isScrollable }).getBoundsInScreen(bounds)
            val x = bounds.exactCenterX()
            val start = bounds.top + bounds.height() * 0.75f
            val end = bounds.top + bounds.height() * 0.5f
            val downTime = SystemClock.uptimeMillis()
            fun touch(action: Int, y: Float) {
                android.view.MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).also { event ->
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    assertTrue(automation.injectInputEvent(event, true))
                    event.recycle()
                }
            }
            touch(android.view.MotionEvent.ACTION_DOWN, start)
            for (step in 1..8) {
                SystemClock.sleep(30)
                touch(android.view.MotionEvent.ACTION_MOVE, start + (end - start) * step / 8)
            }
            SystemClock.sleep(200)
            touch(android.view.MotionEvent.ACTION_MOVE, end)
            touch(android.view.MotionEvent.ACTION_UP, end)
        }
        try {
            store.save(profile); store.save(target, newStorageName = storageName)
            ActivityScenario.launch<ProfileEditActivity>(Intent(context, ProfileEditActivity::class.java)
                .putExtra("profileId", profile.id)).use {
                click(context.getString(R.string.profile_runtime))
                click(context.getString(R.string.runtime_rootless_title))
                click(context.getString(R.string.cancel))
                await { find { it.text?.toString() == context.getString(R.string.profile_distribution) } == null }
                assertEquals(profile.runtime, store.find(profile.id)?.runtime)
                click(context.getString(R.string.profile_runtime))
                click(context.getString(R.string.runtime_rootless_title))
                click(context.getString(R.string.save))
                await { find { it.text?.toString() == context.getString(R.string.cancel) } == null }
                await { find { it.text?.toString() == context.getString(R.string.profile_distribution) } != null }
                click(context.getString(R.string.profile_distribution))
                click(context.getString(R.string.runtime_ubuntu_title))
                click(context.getString(R.string.save))
                await { find { it.text?.toString() == context.getString(R.string.cancel) } == null }
                // Only storage belonging to the selected distribution can be connected.
                await {
                    var storage = find { it.text?.toString() == target.storageId }
                    while (storage != null && !storage.isClickable) storage = storage.parent
                    if (storage?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true) true
                    else { scrollStorageList(); false }
                }
                await {
                    if (find { it.text?.toString() == context.getString(R.string.profile_save) } != null) true
                    else {
                        find { it.isScrollable }?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        false
                    }
                }
                click(context.getString(R.string.profile_save))
                await { store.find(profile.id)?.storageId == target.storageId }
                assertEquals(target.runtime, store.find(profile.id)?.runtime)
            }
        } finally {
            store.delete(profile.id); store.delete(target.id)
        }
    }

    @Test fun nativeProfilesHaveIndependentStorageUnlessExplicitlyShared() {
        val store = SessionProfiles(context)
        val first = store.duplicate(store.builtIns().first()).copy(name = "Storage first")
        val separate = store.duplicate(first).copy(name = "Storage separate")
        val shared = store.duplicate(first).copy(name = "Storage shared", storageId = first.storageId)
        assertNotEquals(first.storageId, separate.storageId)
        val profiles = listOf(first, separate, shared)
        val queue = LinkedBlockingQueue<IRuntimeService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { queue.offer(IRuntimeService.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        val service = requireNotNull(queue.poll(15, TimeUnit.SECONDS))
        val owned = mutableListOf<String>()
        try {
            service.startRuntime()
            await { service.state == "READY" }
            profiles.forEach { profile ->
                store.save(profile)
                service.createSessionFromSpec(profile.resolve().toJson().toString(), 24, 80)
                await { sessions(service).any { it.optString("profileId") == profile.id } }
                owned += sessions(service).first { it.optString("profileId") == profile.id }.getString("id")
            }
            val firstHome = java.io.File(dev.terminal.runtime.session.SessionStorage.root(context.filesDir, first.runtime, first.storageId), "home")
            val secondHome = java.io.File(dev.terminal.runtime.session.SessionStorage.root(context.filesDir, separate.runtime, separate.storageId), "home")
            service.writeSessionInput(owned[0], "printf owned > \"\$HOME/profile-storage-marker\"\n".toByteArray())
            await { java.io.File(firstHome, "profile-storage-marker").readTextOrNull() == "owned" }
            assertFalse(java.io.File(secondHome, "profile-storage-marker").exists())
            store.delete(first.id)
            service.writeSessionInput(owned[2], "cat \"\$HOME/profile-storage-marker\" > \"\$HOME/shared-result\"\n".toByteArray())
            await { java.io.File(firstHome, "shared-result").readTextOrNull() == "owned" }
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                activity.recreate()
                owned.forEach { service.attachSession(it); service.detachSession(it) }
                assertEquals(3, sessions(service).count { it.getString("id") in owned })
            }
            service.terminateSession(owned[0])
            await { sessions(service).none { it.getString("id") == owned[0] } }
            assertTrue(sessions(service).any { it.getString("id") == owned[2] })
        } finally {
            owned.forEach { service.terminateSession(it) }
            profiles.forEach { store.delete(it.id) }
            context.unbindService(connection)
        }
    }

    private fun java.io.File.readTextOrNull(): String? = if (isFile) readText() else null

    @Test fun tappingDefaultOpensEditorAndDeletionRequiresConfirmation() {
        val store = SessionProfiles(context)
        val original = store.builtIns().first()
        val previous = profileOverrides()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun find(predicate: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean): android.view.accessibility.AccessibilityNodeInfo? {
            fun walk(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                if (node == null) return null
                if (predicate(node)) return node
                for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
                return null
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            return walk(automation.rootInActiveWindow)
        }
        fun click(text: String) {
            var node = find { it.text?.toString() == text } ?: error("Missing: $text")
            while (!node.isClickable) node = node.parent ?: error("Not clickable: $text")
            assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        }
        try {
            store.save(original)
            ActivityScenario.launch(ProfilesActivity::class.java).use {
                await { find { it.text?.toString() == original.name } != null }
                click(original.name)
                await { find { it.text?.toString() == context.getString(R.string.profile_name) } != null }
                val delete = context.getString(R.string.profile_delete)
                await {
                    if (find { it.text?.toString() == delete } != null) true
                    else {
                        find { it.isScrollable }?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        false
                    }
                }
                click(delete)
                await { find { it.text?.toString() == context.getString(R.string.profile_delete_message, original.name) } != null }
                click(context.getString(android.R.string.cancel))
                await { find { it.text?.toString() == context.getString(R.string.profile_delete_message, original.name) } == null }
                assertNotNull(store.find(original.id))
                click(delete)
                await { find { it.text?.toString() == context.getString(R.string.profile_delete_message, original.name) } != null }
                // The confirmation button is the clickable node with the delete label.
                var button = find { it.text?.toString() == delete && it.isClickable }
                if (button == null) {
                    button = find { it.text?.toString() == delete && it.parent?.isClickable == true }?.parent
                }
                assertNotNull(button)
                assertTrue(button!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                await { store.find(original.id) == null }
                assertNull(SessionProfiles(context).find(original.id))
            }
        } finally {
            restoreProfileOverrides(previous)
        }
    }

    @Test fun defaultProfilesCanBeEditedDeletedAndStayDeletedAfterReopening() {
        val store = SessionProfiles(context)
        val builtIns = store.builtIns()
        assertEquals(5, builtIns.size)
        val previous = profileOverrides()
        try {
            builtIns.filter { it.runtime != dev.terminal.runtime.session.SessionRuntime.AVF || store.hasAvfPrivileges() }.forEach { original ->
                val launch = original.resolve()
                val edited = original.copy(name = "Changed default", startupCommand = "echo changed")
                store.save(edited)
                assertEquals(edited, SessionProfiles(context).find(original.id))
                assertEquals(original.startupCommand, launch.startupCommand)
                store.delete(original.id)
                assertNull(SessionProfiles(context).find(original.id))
            }
            assertTrue(SessionProfiles(context).all().none { it.id in builtIns.map { it.id } })
            val copy = store.duplicate(store.builtIns().first()).copy(name = "After deletion")
            store.save(copy)
            assertEquals(copy, SessionProfiles(context).find(copy.id))
            store.delete(copy.id)
        } finally {
            restoreProfileOverrides(previous)
        }
    }

    @Test fun avfDefaultIsVisibleOnlyWithBothPrivilegedPermissions() {
        val store = SessionProfiles(context)
        val id = dev.terminal.runtime.session.SessionProfile.builtInId(dev.terminal.runtime.session.SessionRuntime.AVF)
        val previous = profileOverrides()
        try {
            store.save(store.builtIns().first { it.id == id })
            assertEquals(store.hasAvfPrivileges(), store.find(id) != null)
            store.save(store.builtIns().first { it.id == id })
            assertEquals(store.hasAvfPrivileges(), SessionProfiles(context).find(id) != null)
        } finally {
            restoreProfileOverrides(previous)
        }
    }

    @Test fun profileEditorKeepsUnsavedDraftAcrossRecreation() {
        val profile = SessionProfiles(context).duplicate(SessionProfiles(context).builtIns().first()).copy(name = "Editor original")
        val store = SessionProfiles(context)
        store.save(profile)
        try {
            val intent = Intent(context, ProfileEditActivity::class.java).putExtra("profileId", profile.id)
            ActivityScenario.launch<ProfileEditActivity>(intent).use { activity ->
                fun node(text: String, editable: Boolean = true): android.view.accessibility.AccessibilityNodeInfo? {
                    fun search(current: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                        if (current == null) return null
                        if ((!editable || current.isEditable) && current.text?.toString() == text) return current
                        for (index in 0 until current.childCount) search(current.getChild(index))?.let { return it }
                        return null
                    }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    return search(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
                }
                await { node("Editor original", false) != null }
                var row = requireNotNull(node("Editor original", false))
                while (!row.isClickable) row = requireNotNull(row.parent)
                assertTrue(row.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                await { node("Editor original") != null }
                val arguments = android.os.Bundle().apply {
                    putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Unsaved profile draft")
                }
                assertTrue(node("Editor original")!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
                activity.recreate()
                await { node("Unsaved profile draft") != null }
                assertEquals("Editor original", store.find(profile.id)!!.name)
            }
        } finally { store.delete(profile.id) }
    }

    @Test fun editingAndDeletingProfilePreservesLiveSessionThroughActivityRecreation() {
        val store = SessionProfiles(context)
        val profile = store.builtIns().first().copy(name = "Profile lifecycle test",
            workingDirectory = "/", environment = mapOf("PROFILE_TEST" to "original"),
            startupCommand = "export PROFILE_STARTUP=once", icon = "keyboard")
        val previous = profileOverrides()
        val queue = LinkedBlockingQueue<IRuntimeService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { queue.offer(IRuntimeService.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        store.save(profile)
        assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        val service = requireNotNull(queue.poll(15, TimeUnit.SECONDS))
        val owned = mutableListOf<String>()
        try {
            service.startRuntime()
            await { service.state == "READY" }
            service.createSessionFromSpec(profile.resolve().toJson().toString(), 24, 80)
            await { sessions(service).any { it.optString("profileId") == profile.id } }
            val original = sessions(service).first { it.optString("profileId") == profile.id }
            owned += original.getString("id")
            store.save(profile.copy(name = "Changed", environment = mapOf("PROFILE_TEST" to "changed"), icon = "terminal"))
            service.createSessionFromSpec(requireNotNull(store.find(profile.id)).resolve().toJson().toString(), 24, 80)
            await { sessions(service).count { it.optString("profileId") == profile.id } == 2 }
            owned += sessions(service).first { it.optString("profileId") == profile.id && it.getString("id") != owned.first() }.getString("id")
            store.delete(profile.id)
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                owned.forEach { service.attachSession(it); service.detachSession(it) }
                service.resizeSession(owned.first(), 31, 91)
                activity.recreate()
                await { sessions(service).filter { it.getString("id") in owned }.size == 2 }
                val restored = sessions(service).first { it.getString("id") == owned.first() }
                assertEquals("keyboard", restored.getJSONObject("launchSpec").getString("icon"))
                assertEquals("terminal", sessions(service).first { it.getString("id") == owned.last() }.getJSONObject("launchSpec").getString("icon"))
                assertEquals("original", restored.getJSONObject("launchSpec").getJSONObject("environment").getString("PROFILE_TEST"))
                assertEquals("changed", sessions(service).first { it.getString("id") == owned.last() }.getJSONObject("launchSpec").getJSONObject("environment").getString("PROFILE_TEST"))
                val marker = java.io.File(context.cacheDir, "profile-test-${profile.id}")
                service.writeSessionInput(owned.first(), "printf '%s:%s' \"\$PROFILE_TEST\" \"\$PROFILE_STARTUP\" > '${marker.absolutePath}'\n".toByteArray())
                await { marker.exists() && marker.readText() == "original:once" }
                marker.delete()
                service.terminateSession(owned.first())
                await { sessions(service).none { it.getString("id") == owned.first() } }
                assertTrue(sessions(service).any { it.getString("id") == owned.last() })
            }
        } finally {
            owned.forEach { service.terminateSession(it) }
            store.delete(profile.id)
            context.unbindService(connection)
            restoreProfileOverrides(previous)
        }
    }
    private fun profileRegistry() = dev.terminal.runtime.session.StorageRegistry(context.filesDir) {
        context.getSharedPreferences("session-profiles", Context.MODE_PRIVATE).all.mapValues { it.value as String }
    }
    private fun profileOverrides() = profileRegistry().transaction { overrides() }
    private fun restoreProfileOverrides(previous: Map<String, String>) = profileRegistry().transaction {
        // Test cleanup preserves discovered storage records and restores only profile overrides.
        val manifest = java.io.File(context.filesDir, "storage-registry.json")
        val state = JSONObject(manifest.readText()).put("profiles", JSONObject(previous))
        val atomic = android.util.AtomicFile(manifest)
        val output = atomic.startWrite()
        try { output.write(state.toString().toByteArray()); atomic.finishWrite(output) }
        catch (failure: Throwable) { atomic.failWrite(output); throw failure }
    }
    private fun sessions(service: IRuntimeService): List<JSONObject> {
        val array = JSONArray(service.sessionsJson)
        return List(array.length()) { array.getJSONObject(it) }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (!condition()) { check(SystemClock.elapsedRealtime() < deadline) { "Timed out" }; SystemClock.sleep(100) }
    }
}
