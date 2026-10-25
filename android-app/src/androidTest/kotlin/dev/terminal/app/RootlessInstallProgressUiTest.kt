package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.ipc.IRuntimeStateListener
import dev.terminal.runtime.lifecycle.VmRuntimeService
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Fresh service-owned installs plus Activity recreation. Only an isolated application ID is allowed. */
@RunWith(AndroidJUnit4::class)
class RootlessInstallProgressUiTest {
    @Test fun everyDistributionReportsRealProgressAndRetainsItAcrossSettingsRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Requires isolated test APK", context.packageName == "dev.terminal.progresstest")
        val selected = InstrumentationRegistry.getArguments().getString("distribution")
        val distributions = listOf("ROOTLESS" to "debian", "ROOTLESS_UBUNTU" to "ubuntu", "ROOTLESS_ARCH" to "archlinux")
            .filter { selected == null || it.second == selected }
        require(distributions.isNotEmpty()) { "Unknown distribution" }
        val resume = InstrumentationRegistry.getArguments().getString("resume") == "true"
        assumeTrue("Never replace existing images", resume || distributions.none { File(context.filesDir, "rootless/${it.second}/rootfs").exists() })
        lateinit var service: IRuntimeService
        val connected = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = IRuntimeService.Stub.asInterface(binder)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val updates = mutableMapOf<String, MutableList<JSONObject>>()
        val listener = object : IRuntimeStateListener.Stub() {
            override fun onSessionTaskCompletionsChanged(completionsJson: String) = Unit
            override fun onRootlessProgress(runtimeType: String, phase: String, detail: String) {
                val snapshot = JSONObject(service.runtimeStatusJson).getJSONObject(runtimeType).optJSONObject("progress") ?: return
                synchronized(updates) { updates.getOrPut(runtimeType) { mutableListOf() }.add(snapshot) }
            }
            override fun onRuntimeChoicesChanged() = Unit
            override fun onSessionsChanged(revision: Long, sessionsJson: String) = Unit
            override fun onTerminalOutput(sessionId: String, bytes: ByteArray, replay: Boolean) = Unit
            override fun onSessionExited(sessionId: String, exitCode: Int) = Unit
            override fun onSessionTerminationFailed(sessionId: String, message: String) = Unit
            override fun onOperationError(operation: String, message: String) = Unit
            override fun onSessionFocusRequested(sessionId: String) = Unit
        }
        assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(connected.await(10, TimeUnit.SECONDS))
            service.registerListener(listener)
            val intent = Intent(context, SettingsDetailActivity::class.java)
                .putExtra(SettingsDetailActivity.EXTRA_CATEGORY, SettingsDetailActivity.ROOTLESS)
            ActivityScenario.launch<SettingsDetailActivity>(intent).use { activity ->
                for ((runtime, directory) in distributions) {
                    val fresh = !File(context.filesDir, "rootless/$directory/rootfs").exists()
                    android.util.Log.i("RootlessProgressUiTest", "$directory START")
                    service.prepareRootless(runtime)
                    var recreated = false
                    var sawVisibleProgress = false
                    var startedAt = -1L
                    val deadline = SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(20)
                    var ready = false
                    while (SystemClock.elapsedRealtime() < deadline) {
                        val status = JSONObject(service.runtimeStatusJson).getJSONObject(runtime)
                        assertNotEquals(status.optString("detail"), "ERROR", status.getString("state"))
                        val progress = status.optJSONObject("progress")
                        if (progress != null && status.getString("state") == "CHECKING") {
                            if (startedAt < 0) startedAt = progress.getLong("startedAtElapsedMs")
                            assertEquals(startedAt, progress.getLong("startedAtElapsedMs"))
                            assertEquals(progress.getString("detail"), status.getString("detail"))
                            if (!recreated && progress.getString("phase") in setOf("DOWNLOAD", "EXTRACT", "PACKAGE_INDEX", "PACKAGE_DOWNLOAD", "PACKAGE_INSTALL")) {
                                activity.recreate()
                                recreated = true
                                SystemClock.sleep(500)
                            }
                            if (recreated) {
                                val texts = mutableListOf<String>()
                                fun collect(node: AccessibilityNodeInfo?) {
                                    if (node == null) return
                                    node.text?.let { texts += it.toString() }
                                    for (index in 0 until node.childCount) collect(node.getChild(index))
                                }
                                collect(instrumentation.uiAutomation.rootInActiveWindow)
                                if (texts.any { it.startsWith("경과 ") }) sawVisibleProgress = true
                            }
                        }
                        if (status.optBoolean("available") && progress?.optString("phase") == "READY") {
                            ready = true
                            break
                        }
                        SystemClock.sleep(200)
                    }
                    assertTrue("Installation timed out: $directory", ready)
                    assertTrue("Activity was not recreated: $directory", recreated)
                    assertTrue("Elapsed/progress UI not visible after recreation: $directory", sawVisibleProgress)
                    val events = synchronized(updates) { updates.getValue(runtime).toList() }
                    val phases = events.map { it.getString("phase") }.toSet()
                    val expected = listOf("PACKAGE_INDEX", "LOCALE", "TIMEZONE", "HOME", "VERIFY_PACKAGES", "VERIFY_COMMANDS", "VERIFY_LOCALE", "READY") +
                        if (fresh) listOf("DOWNLOAD", "CHECKSUM", "EXTRACT", "ACTIVATE") else emptyList()
                    for (phase in expected) {
                        assertTrue("Missing $directory $phase", phase in phases)
                    }
                    if (fresh) assertTrue(events.any { it.getString("phase") == "CHECKSUM" && it.optDouble("fraction") in 0.0..1.0 })
                    if (fresh && runtime != "ROOTLESS_ARCH") {
                        assertTrue(events.any { it.getString("phase") == "PACKAGE_INSTALL" && it.optDouble("fraction") in 0.0..1.0 })
                    } else if (fresh) {
                        assertTrue("Arch package stages absent", "PACKAGE_INSTALL" in phases && "PACKAGE_HOOKS" in phases)
                    }
                    android.util.Log.i("RootlessProgressUiTest", "$directory PASSED phases=${phases.joinToString()}")
                    // Only this isolated app's newly installed test image is removed.
                    service.removeRootless(runtime, false)
                    val removeDeadline = SystemClock.elapsedRealtime() + 30_000
                    while (JSONObject(service.runtimeStatusJson).getJSONObject(runtime).getString("state") != "SETUP_REQUIRED" && SystemClock.elapsedRealtime() < removeDeadline) {
                        SystemClock.sleep(100)
                    }
                    assertFalse(File(context.filesDir, "rootless/$directory/rootfs").exists())
                }
            }
        } finally {
            if (connected.count == 0L) service.unregisterListener(listener)
            context.unbindService(connection)
        }
    }
}
