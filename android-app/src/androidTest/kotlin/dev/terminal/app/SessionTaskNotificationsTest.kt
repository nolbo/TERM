package dev.terminal.app

import android.app.NotificationManager
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
import dev.terminal.runtime.notifications.SessionNotificationSettings
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SessionTaskNotificationsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test fun nativeCommandCompletesWithoutUiAndAttentionIsOptionalReplaySafeAndNavigable() {
        assertTrue("Grant Android notification permission", manager.areNotificationsEnabled())
        val oldSetting = context.getSharedPreferences("runtime-settings", Context.MODE_PRIVATE)
            .getBoolean(SessionNotificationSettings.KEY_ENABLED, false)
        val queue = LinkedBlockingQueue<IRuntimeService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { queue.offer(IRuntimeService.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        var bound = false
        val owned = mutableListOf<String>()
        val marker = File(context.cacheDir, "notification-test-${System.nanoTime()}")
        var connected = false
        lateinit var service: IRuntimeService
        fun alert() = manager.activeNotifications.firstOrNull { it.id == 1002 && it.tag == owned.firstOrNull() }
        fun command(value: String) = service.writeSessionInput(owned.first(), "$value\n".toByteArray())
        fun runningIds(): List<String> {
            val values = JSONArray(service.sessionsJson)
            return List(values.length()) { values.getJSONObject(it).getString("id") }
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
            bound = true
            service = requireNotNull(queue.poll(10, TimeUnit.SECONDS))
            connected = true
            await("ready") { service.state == "READY" }
            service.setSessionTaskNotifications(false)
            val before = runningIds()
            service.createSession("NATIVE", "/system/bin/sh", "", 24, 80)
            await("created") { runningIds().any { it !in before } }
            owned += runningIds().filter { it !in before }
            command("printf '\\007'; printf ready > '${marker.absolutePath}'")
            await("disabled command ran") { marker.exists() }
            SystemClock.sleep(300)
            assertNull(alert())
            marker.delete()
            service.setSessionTaskNotifications(true)
            // Destroy the UI and remove the test binding. The runtime owns the job and alert.
            scenario.close()
            context.unbindService(connection); bound = false
            command("sleep 11; false")
            await("long Native command alert", 20000) { alert() != null }
            assertTrue(alert()!!.notification.extras.getCharSequence("android.text").toString().contains("1"))
            manager.cancel(owned.first(), 1002)
            service.attachSession(owned.first())
            service.detachSession(owned.first())
            SystemClock.sleep(350)
            assertNull("Replay must not notify", alert())
            // Toggle clears the limiter and existing alerts, without terminating sessions.
            service.setSessionTaskNotifications(false)
            service.setSessionTaskNotifications(true)
            command("printf '\\033]777;notify;private-title;private-output\\007'")
            await("attention alert") { alert() != null }
            service.renameSession(owned.first(), "Notification target")
            val notification = alert()!!.notification
            assertFalse(notification.extras.toString().contains("private-output"))
            assertTrue(notification.contentIntent.isImmutable)
            service.createSession("NATIVE", "/system/bin/sh", "", 24, 80)
            await("second session") { runningIds().size > before.size + owned.size }
            owned += runningIds().filter { it !in before && it !in owned }
            notification.contentIntent.send()
            await("notification opens the exact session") {
                val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@await false
                fun selected(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
                    if (node == null) return false
                    fun hasTarget(current: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
                        if (current == null) return false
                        if (current.text?.toString()?.contains("Notification target") == true) return true
                        return (0 until current.childCount).any { hasTarget(current.getChild(it)) }
                    }
                    if (node.isSelected && hasTarget(node)) return true
                    return (0 until node.childCount).any { selected(node.getChild(it)) }
                }
                selected(root)
            }
            service.setSessionTaskNotifications(false)
            await("option clears only task alerts") { alert() == null }
            assertTrue(manager.activeNotifications.any { it.id == 1001 })
            assertTrue(runningIds().containsAll(owned))
        } finally {
            if (connected) {
                service.setSessionTaskNotifications(oldSetting)
                owned.forEach { service.terminateSession(it) }
            }
            scenario.close()
            if (bound) context.unbindService(connection)
            marker.delete()
        }
    }
    private fun await(label: String, timeout: Long = 10000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100)
        assertTrue(label, condition())
    }
}
