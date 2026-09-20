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

    @Test fun persistentProfilesCannotMutateBuiltIns() {
        val store = SessionProfiles(context)
        val builtIns = store.builtIns()
        assertEquals(5, builtIns.size)
        builtIns.forEach {
            assertTrue(runCatching { store.save(it.copy(name = "overwrite")) }.isFailure)
            assertTrue(runCatching { store.delete(it.id) }.isFailure)
        }
        val copy = store.duplicate(builtIns.first()).copy(name = "instrumented copy")
        try {
            store.save(copy)
            assertEquals(copy, SessionProfiles(context).find(copy.id))
            assertEquals(builtIns.first(), store.builtIns().first())
        } finally { store.delete(copy.id) }
    }

    @Test fun profileEditorKeepsUnsavedDraftAcrossRecreation() {
        val profile = SessionProfiles(context).duplicate(SessionProfiles(context).builtIns().first()).copy(name = "Editor original")
        val store = SessionProfiles(context)
        store.save(profile)
        try {
            val intent = Intent(context, ProfileEditActivity::class.java).putExtra("profileId", profile.id)
            ActivityScenario.launch<ProfileEditActivity>(intent).use { activity ->
                fun node(text: String): android.view.accessibility.AccessibilityNodeInfo? {
                    fun search(current: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                        if (current == null) return null
                        if (current.isEditable && current.text?.toString() == text) return current
                        for (index in 0 until current.childCount) search(current.getChild(index))?.let { return it }
                        return null
                    }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    return search(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
                }
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
        val profile = store.duplicate(store.builtIns().first()).copy(name = "Profile lifecycle test",
            workingDirectory = "/", environment = mapOf("PROFILE_TEST" to "original"),
            startupCommand = "export PROFILE_STARTUP=once")
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
