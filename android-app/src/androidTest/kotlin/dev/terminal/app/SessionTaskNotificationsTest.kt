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
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
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
            await("notification opens the exact session") { selectedSession(owned.first()) }
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

    @Test fun focusedCompletionIsSuppressedAcrossRecreationAndLeavingTerminalRestoresNotifications() {
        assertTrue("Grant Android notification permission", manager.areNotificationsEnabled())
        val oldSetting = context.getSharedPreferences("runtime-settings", Context.MODE_PRIVATE)
            .getBoolean(SessionNotificationSettings.KEY_ENABLED, false)
        val queue = LinkedBlockingQueue<IRuntimeService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { queue.offer(IRuntimeService.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val owned = mutableListOf<String>()
        val markers = List(2) { File(context.cacheDir, "focused-notification-test-${System.nanoTime()}-$it") }
        var bound = false
        var service: IRuntimeService? = null
        var settings: SettingsActivity? = null
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
            bound = true
            val connected = requireNotNull(queue.poll(10, TimeUnit.SECONDS))
            service = connected
            await("ready") { connected.state == "READY" }
            fun ids(): Set<String> {
                val values = JSONArray(connected.sessionsJson)
                return (0 until values.length()).mapTo(mutableSetOf()) { values.getJSONObject(it).getString("id") }
            }
            fun alert(id: String) = manager.activeNotifications.firstOrNull { it.id == 1002 && it.tag == id }
            connected.setSessionTaskNotifications(false)
            repeat(2) {
                val before = ids()
                connected.createSession("NATIVE", "/system/bin/sh", "", 24, 80)
                await("new session") { (ids() - before).isNotEmpty() }
                owned += ids() - before
            }
            val focused = owned.first()
            val background = owned.last()
            connected.renameSession(focused, "Focused completion test")
            scenario.onActivity { activity ->
                activity.startActivity(Intent(activity.intent)
                    .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(SessionNotificationSettings.EXTRA_SESSION_ID, focused))
            }
            fun awaitFocus() = await("selected terminal has window focus") { selectedSession(focused) }
            awaitFocus()
            connected.setSessionTaskNotifications(true)
            owned.forEachIndexed { index, id ->
                connected.writeSessionInput(id, "sleep 11; printf done > '${markers[index].absolutePath}'\n".toByteArray())
            }
            scenario.recreate()
            awaitFocus()
            await("other session completes and notifies", 20000) { markers.all { it.exists() } && alert(background) != null }
            SystemClock.sleep(350)
            assertNull("The focused session must not send completion notifications", alert(focused))
            connected.setSessionTaskNotifications(false)
            connected.setSessionTaskNotifications(true)
            connected.writeSessionInput(focused, "sleep 11; false\n".toByteArray())
            scenario.onActivity { it.startActivity(Intent(context, SettingsActivity::class.java)) }
            await("settings opened") {
                instrumentation.runOnMainSync {
                    settings = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<SettingsActivity>().firstOrNull()
                }
                settings != null
            }
            await("leaving the terminal restores completion alerts", 20000) { alert(focused) != null }
            instrumentation.runOnMainSync { settings?.finish() }
            settings = null
            awaitFocus()
            assertTrue(ids().containsAll(owned))
        } finally {
            instrumentation.runOnMainSync { settings?.finish() }
            service?.let { connected ->
                connected.setSessionTaskNotifications(oldSetting)
                owned.forEach { connected.terminateSession(it) }
            }
            scenario.close()
            if (bound) context.unbindService(connection)
            markers.forEach { it.delete() }
        }
    }

    private fun selectedSession(id: String): Boolean {
        fun terminal(view: android.view.View): dev.terminal.view.TerminalView? {
            if (view is dev.terminal.view.TerminalView) return view
            val group = view as? android.view.ViewGroup ?: return null
            return (0 until group.childCount).firstNotNullOfOrNull { terminal(group.getChildAt(it)) }
        }
        var selected = false
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().firstOrNull()
            val expected = (context.applicationContext as TerminalApplication).terminalSessions.sessions[id]
            selected = expected != null && activity?.window?.decorView?.let {
                it.hasWindowFocus() && terminal(it)?.currentSession === expected
            } == true
        }
        return selected
    }

    private fun await(label: String, timeout: Long = 10000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100)
        assertTrue(label, condition())
    }
}
