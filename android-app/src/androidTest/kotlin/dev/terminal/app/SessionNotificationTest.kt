package dev.terminal.app

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Run on an idle test installation with notification permission granted. */
@RunWith(AndroidJUnit4::class)
class SessionNotificationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var bound = false
    private val connections = LinkedBlockingQueue<IRuntimeService>()
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            connections.offer(IRuntimeService.Stub.asInterface(binder))
        }
        override fun onServiceDisconnected(name: ComponentName) = Unit
    }

    @Test fun notificationTitleOmitsAppNameAsSessionCountChanges() {
        assertTrue("Grant notification permission before running", manager.areNotificationsEnabled())
        ActivityScenario.launch(MainActivity::class.java).use {
            val service = bind()
            val owned = mutableSetOf<String>()
            try {
                await("ready and idle") { service.state == "READY" && !hasNotification() }
                assertTrue("Use an idle test installation", running(service).isEmpty())
                repeat(2) {
                    val before = running(service)
                    service.createSession("NATIVE", "/system/bin/sh", "", 24, 80)
                    await("new session") { running(service).size == before.size + 1 }
                    owned += running(service) - before
                }
                await("two-session notification without app name") { notificationTitle() == "실행 중인 세션 2개" }
                val first = owned.first()
                service.terminateSession(first)
                await("one-session notification without app name") { notificationTitle() == "실행 중인 세션 1개" }
                owned.remove(first)
                service.terminateSession(owned.single())
                await("last exit removes notification") { running(service).isEmpty() && !hasNotification() }
                owned.clear()
            } finally {
                owned.forEach { runCatching { service.terminateSession(it) } }
                unbind()
            }
        }
    }

    @Test fun sessionsRetainServiceUntilLastExit() {
        assertTrue("Grant notification permission before running", manager.areNotificationsEnabled())
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            var service = bind()
            val owned = mutableSetOf<String>()
            try {
                await("ready and idle") { service.state == "READY" && !hasNotification() }
                assertTrue("Use an idle test installation", running(service).isEmpty())
                repeat(2) {
                    val before = running(service)
                    service.createSession("NATIVE", "/system/bin/sh", "", 24, 80)
                    await("new session") { running(service).size == before.size + 1 }
                    owned += running(service) - before
                }
                await("two-session notification") { notificationTitle().contains("2개") }
                val notification = manager.activeNotifications.single { it.id == 1001 }
                assertTrue(notification.isOngoing)
                assertTrue(notification.notification.flags and Notification.FLAG_AUTO_CANCEL == 0)
                val dismissed = checkNotNull(notification.notification.deleteIntent)
                assertTrue(dismissed.isImmutable)
                // onStop intentionally keeps the renderer transport bound; destruction detaches it.
                activity.moveToState(Lifecycle.State.DESTROYED)
                unbind()
                await("foreground after UI detach") { serviceDump().contains("isForeground=true") }
                // Exercise the system dismissal callback with unchanged content and no UI.
                dismissed.send()
                await("dismissed notification reposted") {
                    manager.activeNotifications.any { it.id == 1001 && it.postTime > notification.postTime }
                }
                service = bind()
                assertTrue(running(service).containsAll(owned))
                val first = owned.first()
                service.terminateSession(first)
                await("one-session notification") { notificationTitle().contains("1개") }
                owned.remove(first)
                // Natural shell exit must release foreground ownership too.
                service.writeSessionInput(owned.single(), "exit\n".toByteArray())
                await("last exit removes notification") { running(service).isEmpty() && !hasNotification() }
                // A delayed dismissal must not resurrect a notification for an empty runtime.
                dismissed.send()
                SystemClock.sleep(750)
                assertTrue("idle dismissal leaves notification absent", !hasNotification())
                owned.clear()
                await("started lifetime released") { serviceDump().contains("startRequested=false") }
                unbind()
                await("idle service destroyed") { !serviceDump().contains("ServiceRecord{") }
                ActivityScenario.launch(MainActivity::class.java).use {
                    service = bind()
                    await("reopened idle service") { service.state == "READY" && !hasNotification() }
                    service.createSession("NATIVE", "/system/bin/sh", "", 24, 80)
                    await("new session after idle stop") { running(service).size == 1 && hasNotification() }
                    owned += running(service)
                    service.terminateSession(owned.single())
                    await("explicit final termination") { running(service).isEmpty() && !hasNotification() }
                    owned.clear()
                    service.createSession("INVALID", "/system/bin/sh", "", 24, 80)
                    // Binder operation is asynchronous; wait for it to settle before checking release.
                    SystemClock.sleep(500)
                    await("failed creation releases service") { !hasNotification() && serviceDump().contains("startRequested=false") }
                }
            } finally {
                owned.forEach { runCatching { service.terminateSession(it) } }
                unbind()
            }
        }
        await("empty app close destroys service") { !serviceDump().contains("ServiceRecord{") }
    }

    @Test fun notificationActionTerminatesAllSessionsWithAndWithoutUi() {
        assertTrue("Grant notification permission before running", manager.areNotificationsEnabled())
        for (background in listOf(false, true)) {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                val service = bind()
                val owned = mutableSetOf<String>()
                try {
                    await("ready and idle") { service.state == "READY" && !hasNotification() }
                    assertTrue("Use an idle test installation", running(service).isEmpty())
                    repeat(2) {
                        service.createSession("NATIVE", "/system/bin/sh", "", 24, 80)
                        await("session ${it + 1}") { running(service).size == it + 1 }
                        owned += running(service)
                    }
                    await("two-session action") { notificationTitle().contains("2개") }
                    val action = manager.activeNotifications.single { it.id == 1001 }
                        .notification.actions.single { it.title.toString() == "모든 세션 종료" }
                    assertTrue(action.actionIntent.isImmutable)
                    if (background) {
                        activity.moveToState(Lifecycle.State.DESTROYED)
                        unbind()
                    }
                    action.actionIntent.send()
                    // Rapid repeat taps must be harmless and must not retain an empty service.
                    action.actionIntent.send()
                    if (background) {
                        await("all sessions stopped without UI") {
                            !hasNotification() && !serviceDump().contains("ServiceRecord{")
                        }
                    } else {
                        await("all sessions stopped with UI") {
                            running(service).isEmpty() && !hasNotification() &&
                                serviceDump().contains("startRequested=false")
                        }
                    }
                    owned.clear()
                } finally {
                    owned.forEach { runCatching { service.terminateSession(it) } }
                    unbind()
                }
            }
            await("no idle service") { !serviceDump().contains("ServiceRecord{") }
        }
    }

    private fun bind(): IRuntimeService {
        connections.clear()
        assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        bound = true
        return checkNotNull(connections.poll(10, TimeUnit.SECONDS))
    }
    private fun unbind() { if (bound) { context.unbindService(connection); bound = false } }
    private fun running(service: IRuntimeService): Set<String> {
        val sessions = JSONArray(service.sessionsJson)
        return (0 until sessions.length()).map { sessions.getJSONObject(it) }
            .filter { it.getString("state") == "RUNNING" }.map { it.getString("id") }.toSet()
    }
    private fun hasNotification() = manager.activeNotifications.any { it.id == 1001 }
    private fun notificationTitle() = manager.activeNotifications.firstOrNull { it.id == 1001 }
        ?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
    private fun serviceDump(): String = instrumentation.uiAutomation
        .executeShellCommand("dumpsys activity services ${context.packageName}").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
        }
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertTrue(label, condition())
    }
}
