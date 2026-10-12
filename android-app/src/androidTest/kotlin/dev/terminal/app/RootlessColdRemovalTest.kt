package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Rect
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run only with an isolated applicationId: this test confirms destructive UI actions. */
@RunWith(AndroidJUnit4::class)
class RootlessColdRemovalTest {
    @Test fun settingsCanRemoveEachImageWithoutStartingAnyShell() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Requires isolated test APK", context.packageName == "dev.terminal.removaltest")
        val distributions = listOf("ROOTLESS" to "debian", "ROOTLESS_UBUNTU" to "ubuntu", "ROOTLESS_ARCH" to "archlinux")
        val layouts = distributions.map { (_, directory) -> File(context.filesDir, "rootless/$directory") }
        assumeTrue("Never replace existing images", layouts.none { File(it, "rootfs").exists() })
        layouts.forEach { root ->
            listOf("bin/sh", "usr/bin/apt", "usr/bin/pacman").forEach { path ->
                File(root, "rootfs/$path").apply { parentFile!!.mkdirs(); writeText("#!/bin/sh\n"); setExecutable(true) }
            }
            File(root, "home/keep").apply { parentFile!!.mkdirs(); writeText("home") }
        }
        val connected = CountDownLatch(1)
        lateinit var service: IRuntimeService
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = IRuntimeService.Stub.asInterface(binder)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(connected.await(10, TimeUnit.SECONDS))
            val initialState = service.state
            assertNotEquals("READY", initialState)
            fun clickRemoval() {
                val until = SystemClock.uptimeMillis() + 10_000
                while (SystemClock.uptimeMillis() < until) {
                    fun findRemoval(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                        if (node == null) return null
                        if (node.text?.toString() == "제거") return node
                        for (index in 0 until node.childCount) findRemoval(node.getChild(index))?.let { return it }
                        return null
                    }
                    val node = findRemoval(instrumentation.uiAutomation.rootInActiveWindow)
                    if (node != null) {
                        val bounds = Rect().also(node::getBoundsInScreen)
                        val now = SystemClock.uptimeMillis()
                        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
                            event.source = InputDevice.SOURCE_TOUCHSCREEN
                            instrumentation.uiAutomation.injectInputEvent(event, true)
                            event.recycle()
                        }
                        instrumentation.uiAutomation.waitForIdle(300, 3_000)
                        return
                    }
                    SystemClock.sleep(100)
                }
                fail("Removal button not found")
            }
            ActivityScenario.launch<SettingsDetailActivity>(Intent(context, SettingsDetailActivity::class.java)
                .putExtra(SettingsDetailActivity.EXTRA_CATEGORY, SettingsDetailActivity.ROOTLESS)).use {
                distributions.forEachIndexed { index, (runtime, _) ->
                    assertEquals("ENVIRONMENT_REQUIRED", JSONObject(service.runtimeStatusJson).getJSONObject(runtime).getString("state"))
                    clickRemoval() // Row action.
                    clickRemoval() // Destructive confirmation.
                    val until = SystemClock.uptimeMillis() + 10_000
                    while (File(layouts[index], "rootfs").exists() && SystemClock.uptimeMillis() < until) SystemClock.sleep(100)
                    assertFalse("Image was not removed: $runtime", File(layouts[index], "rootfs").exists())
                    assertEquals("SETUP_REQUIRED", JSONObject(service.runtimeStatusJson).getJSONObject(runtime).getString("state"))
                    assertEquals("home", File(layouts[index], "home/keep").readText())
                    assertEquals(initialState, service.state)
                }
            }
        } finally {
            context.unbindService(connection)
        }
    }
}
