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
    private var uiContext: Context = context
    private fun label(id: Int, vararg values: Any): String = uiContext.getString(id, *values)
    private fun launchScenario() = ActivityScenario.launch(MainActivity::class.java).also { scenario ->
        scenario.onActivity { uiContext = it }
    }

    @Test fun nativeCommandCompletionIsSilentAndAttentionIsOptionalReplaySafeAndNavigable() {
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
        var failure: Throwable? = null
        lateinit var service: IRuntimeService
        fun alert() = manager.activeNotifications.firstOrNull { it.id == 1002 && it.tag == owned.firstOrNull() }
        fun command(value: String) = service.writeSessionInput(owned.first(), "$value\n".toByteArray())
        fun runningIds(): List<String> {
            val values = JSONArray(service.sessionsJson)
            return List(values.length()) { values.getJSONObject(it).getString("id") }
        }
        val scenario = launchScenario()
        try {
            assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
            bound = true
            service = requireNotNull(queue.poll(10, TimeUnit.SECONDS))
            connected = true

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
            service.renameSession(owned.first(), "Handle attention test")
            // Destroy the UI and remove the test binding. The runtime continues tracking work without a UI.
            scenario.close()
            context.unbindService(connection); bound = false
            command("sleep 11; printf done > '${marker.absolutePath}'; (exit 1)")
            await("long Native command completed without UI", 20000) { marker.exists() }
            SystemClock.sleep(350)
            assertNull("Completion must never post an Android notification", alert())
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
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            // Detach the UI before removing the last runtime-owned sessions.
            val close = runCatching { scenario.close() }
            if (connected) {
                service.setSessionTaskNotifications(oldSetting)
                owned.forEach { service.terminateSession(it) }
            }
            if (bound) context.unbindService(connection)
            marker.delete()
            if (failure == null) close.getOrThrow()
            else close.exceptionOrNull()?.let { failure.addSuppressed(it) }
        }
    }

    @Test fun bellsAndAttentionAnimateHiddenHandleAndSurviveRecreationAndBackground() {
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
        val scenario = launchScenario()
        try {
            assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
            bound = true
            val connected = requireNotNull(queue.poll(10, TimeUnit.SECONDS))
            service = connected

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
            connected.renameSession(focused, "Focused attention test")
            connected.renameSession(background, "Background attention test")
            scenario.onActivity { activity ->
                activity.startActivity(Intent(activity.intent)
                    .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(SessionNotificationSettings.EXTRA_SESSION_ID, focused))
            }
            fun awaitFocus() = await("selected terminal has window focus") { selectedSession(focused) }
            awaitFocus()
            scenario.onActivity { activity -> toolbarMode(activity).value = ToolbarSheetMode.Hidden }
            SystemClock.sleep(1000)
            await("hidden toolbar handle composed") { handleHeight() > 0 }
            val normalHandleHeight = handleHeight()
            connected.setSessionTaskNotifications(true)
            owned.forEachIndexed { index, id ->
                connected.writeSessionInput(id, "sleep 11; printf done > '${markers[index].absolutePath}'\n".toByteArray())
            }
            scenario.recreate()
            await("recreated terminal has window focus") { terminalWindowFocused() }
            val backgroundText = label(R.string.session_task_handle_attention, "Background attention test")
            val focusedText = label(R.string.session_task_handle_attention, "Focused attention test")
            await("long commands finish without a notice", 20000) { markers.all { it.exists() } }
            SystemClock.sleep(500)
            assertFalse("Completion must not appear in the handle", visibleText(backgroundText))
            assertFalse("Focused completion must not appear in the handle", visibleText(focusedText))
            assertEquals(normalHandleHeight, handleHeight())
            assertNull("Completion must not post an Android notification", alert(background))
            assertNull("Focused completion must not post an Android notification", alert(focused))
            connected.writeSessionInput(background, "printf '\\007'\n".toByteArray())
            await("bell appears in the hidden toolbar handle") { visibleText(backgroundText) }
            await("bell also posts an Android notification") { alert(background) != null }
            SystemClock.sleep(500)
            assertTrue("Handle expands for the message", handleHeight() > normalHandleHeight)
            scenario.onActivity { assertEquals(ToolbarSheetMode.Hidden, toolbarMode(it).value) }
            assertFalse(visibleText(focusedText))
            scenario.recreate()
            await("recreated terminal has window focus") { terminalWindowFocused() }
            await("unread message survives recreation") { visibleText(backgroundText) }
            await("message dismisses and handle restores") { !visibleText(backgroundText) && handleHeight() == normalHandleHeight }
            connected.setSessionTaskNotifications(false)
            connected.setSessionTaskNotifications(true)
            connected.writeSessionInput(focused, "printf '\\033]777;notify;private-title;private-output\\007'\n".toByteArray())
            await("focused attention appears in both surfaces") { visibleText(focusedText) && alert(focused) != null }
            connected.setSessionTaskNotifications(false)
            await("disabling clears focused attention") { !visibleText(focusedText) && alert(focused) == null }
            connected.setSessionTaskNotifications(true)
            markers.first().delete()
            connected.writeSessionInput(focused, "sleep 1; printf '\\033]9;attention\\007'; printf done > '${markers.first().absolutePath}'\n".toByteArray())
            scenario.onActivity { it.startActivity(Intent(context, SettingsActivity::class.java)) }
            await("settings opened") {
                instrumentation.runOnMainSync {
                    settings = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<SettingsActivity>().firstOrNull()
                }
                settings != null
            }
            await("attention arrives behind settings") { markers.first().exists() && alert(focused) != null }
            instrumentation.runOnMainSync { settings?.finish() }
            settings = null
            await("terminal has window focus on return") { terminalWindowFocused() }
            await("attention is shown when returning from settings") {
                visibleText(focusedText)
            }
            connected.setSessionTaskNotifications(false)
            await("disabling clears the current handle message") {
                !visibleText(focusedText)
            }
            assertTrue(ids().containsAll(owned))
        } finally {
            instrumentation.runOnMainSync { settings?.finish() }
            scenario.close()
            service?.let { connected ->
                connected.setSessionTaskNotifications(oldSetting)
                owned.forEach { connected.terminateSession(it) }
            }
            if (bound) context.unbindService(connection)
            markers.forEach { it.delete() }
        }
    }

    private fun node(predicate: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean): android.view.accessibility.AccessibilityNodeInfo? {
        instrumentation.uiAutomation.clearCache()
        fun visit(value: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
            if (value == null) return null
            if (value.isVisibleToUser && predicate(value)) return value
            for (index in 0 until value.childCount) visit(value.getChild(index))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun visibleText(text: String) = node { it.text?.toString()?.contains(text) == true } != null
    private fun handle() = node { it.contentDescription?.toString() == label(R.string.toolbar_handle) }
    private fun handleHeight(): Int = android.graphics.Rect().also { handle()?.getBoundsInScreen(it) }.height()
    // Seed the persisted hidden-mode fixture; actual handle taps are covered by ToolbarUiTest.
    @Suppress("UNCHECKED_CAST")
    private fun toolbarMode(activity: MainActivity) = activity.javaClass
        .getDeclaredField("toolbarSheetMode\$delegate").apply { isAccessible = true }
        .get(activity) as androidx.compose.runtime.MutableState<ToolbarSheetMode>

    private fun terminalWindowFocused(): Boolean {
        var focused = false
        instrumentation.runOnMainSync {
            focused = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().any { it.window.decorView.hasWindowFocus() }
        }
        return focused
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
