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

    @Test fun editorSelectsBaseAndExplicitStorageSharing() {
        val store = SessionProfiles(context)
        val profile = store.duplicate(store.builtIns().first()).copy(name = "Storage editor")
        val target = store.duplicate(store.builtIns().first {
            it.runtime == dev.terminal.runtime.session.SessionRuntime.ROOTLESS_UBUNTU
        }).copy(name = "Shared Ubuntu fixture")
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
        try {
            store.save(profile); store.save(target)
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
                // The storage section and row have the same title; select its clickable row.
                var storage = requireNotNull(find { it.text?.toString() == context.getString(R.string.profile_storage) && (it.isClickable || it.parent?.isClickable == true) })
                while (!storage.isClickable) storage = requireNotNull(storage.parent)
                assertTrue(storage.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                click(context.getString(R.string.profile_storage_shared, target.name))
                click(context.getString(R.string.save))
                await { find { it.text?.toString() == context.getString(R.string.cancel) } == null }
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
        val prefs = context.getSharedPreferences("session-profiles", Context.MODE_PRIVATE)
        val previous = prefs.getString(original.id, null)
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
            val editor = prefs.edit()
            if (previous == null) editor.remove(original.id) else editor.putString(original.id, previous)
            assertTrue(editor.commit())
        }
    }

    @Test fun defaultProfilesCanBeEditedDeletedAndStayDeletedAfterReopening() {
        val store = SessionProfiles(context)
        val builtIns = store.builtIns()
        assertEquals(5, builtIns.size)
        val prefs = context.getSharedPreferences("session-profiles", Context.MODE_PRIVATE)
        val previous = prefs.all
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
            val editor = prefs.edit().clear()
            previous.forEach { (key, value) -> editor.putString(key, value as String) }
            assertTrue(editor.commit())
        }
    }

    @Test fun avfDefaultIsVisibleOnlyWithBothPrivilegedPermissions() {
        val store = SessionProfiles(context)
        val id = dev.terminal.runtime.session.SessionProfile.builtInId(dev.terminal.runtime.session.SessionRuntime.AVF)
        val prefs = context.getSharedPreferences("session-profiles", Context.MODE_PRIVATE)
        val previous = prefs.getString(id, null)
        try {
            prefs.edit().remove(id).commit()
            assertEquals(store.hasAvfPrivileges(), store.find(id) != null)
            store.save(store.builtIns().first { it.id == id })
            assertEquals(store.hasAvfPrivileges(), SessionProfiles(context).find(id) != null)
        } finally {
            val editor = prefs.edit()
            if (previous == null) editor.remove(id) else editor.putString(id, previous)
            assertTrue(editor.commit())
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
            startupCommand = "export PROFILE_STARTUP=once")
        val preferences = context.getSharedPreferences("session-profiles", Context.MODE_PRIVATE)
        val previous = preferences.getString(profile.id, null)
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
            store.save(profile.copy(name = "Changed", environment = mapOf("PROFILE_TEST" to "changed")))
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
            val editor = preferences.edit()
            if (previous == null) editor.remove(profile.id) else editor.putString(profile.id, previous)
            assertTrue(editor.commit())
        }
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
