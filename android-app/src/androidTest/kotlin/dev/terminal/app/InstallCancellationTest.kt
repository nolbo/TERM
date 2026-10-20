package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.terminal.app.ui.AvfImageInstallProgress
import dev.terminal.app.ui.DroidTheme
import dev.terminal.app.ui.RootlessSetupDialog
import androidx.compose.runtime.mutableStateOf
import dev.terminal.runtime.image.AvfImageInstallPhase
import dev.terminal.runtime.image.AvfImageInstallSnapshot
import dev.terminal.runtime.image.AvfImageInstallState
import dev.terminal.runtime.image.DebianImageDownloadWorker
import dev.terminal.runtime.image.RuntimeSetupCoordinator
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import dev.terminal.runtime.session.Environment
import dev.terminal.runtime.session.EnvironmentRegistry
import dev.terminal.runtime.session.SessionProfile
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.session.SessionStorage
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Each test owns fresh UUID storage/work only; existing images and sessions are never removed. */
@RunWith(AndroidJUnit4::class)
class InstallCancellationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun await(message: String, timeout: Long = 45_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        fail(message)
    }

    private fun click(labelId: Int) {
        val label = context.getString(labelId)
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && node.text?.toString() == label) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        await("Button not visible: $label", 5000) {
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.clearCache()
            var node = find(instrumentation.uiAutomation.rootInActiveWindow) ?: return@await false
            while (!node.isClickable) node = node.parent ?: return@await false
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    private fun clickCancel() = click(R.string.install_cancel)

    @Test fun rootlessDialogKeepsCloseSeparateAndOffersRetryAfterCancellation() {
        val running = mutableStateOf(true)
        val visible = mutableStateOf(true)
        val cancelling = mutableStateOf(false)
        val cancelled = mutableStateOf(false)
        val cancellations = AtomicInteger()
        val retries = AtomicInteger()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                DroidTheme { if (visible.value) RootlessSetupDialog("ROOTLESS", "Fixture installation", "Downloading",
                    running.value, null, onRetry = { retries.incrementAndGet() },
                    onCancelInstall = { cancellations.incrementAndGet(); cancelling.value = true },
                    cancelling = cancelling.value, cancelled = cancelled.value, onDismiss = { visible.value = false }) }
            } }
            click(R.string.close)
            assertTrue(running.value)
            assertEquals(0, cancellations.get())
            scenario.onActivity { visible.value = true }
            clickCancel()
            await("Cancellation was not delivered") { cancellations.get() == 1 }
            assertTrue(visible.value)
            assertTrue(cancelling.value)
            scenario.onActivity { running.value = false; cancelled.value = true; cancelling.value = false }
            click(R.string.retry)
            await("Retry was not delivered") { retries.get() == 1 }
        }
    }

    @Test fun avfButtonCancelsOnlyObservedWorkAndStaleRequestsLeaveRetryAlone() {
        val manager = WorkManager.getInstance(context)
        fun delayed() = OneTimeWorkRequestBuilder<DebianImageDownloadWorker>()
            .setInitialDelay(1, TimeUnit.DAYS).build()
        val first = delayed()
        val other = delayed()
        val retry = delayed()
        try {
            manager.enqueue(listOf(first, other)).result.get(5, TimeUnit.SECONDS)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    DroidTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                        AvfImageInstallProgress(AvfImageInstallSnapshot(first.id.toString(), UUID.randomUUID().toString(),
                            AvfImageInstallState.WAITING, AvfImageInstallPhase.PREPARING))
                    } }
                } }
                if (InstrumentationRegistry.getArguments().getString("externalCancel") != "true") clickCancel()
                await("AVF work did not cancel") {
                    manager.getWorkInfoById(first.id).get(5, TimeUnit.SECONDS)?.state == WorkInfo.State.CANCELLED
                }
                assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(other.id).get()?.state)
                manager.enqueue(retry).result.get(5, TimeUnit.SECONDS)
                runBlocking { RuntimeSetupCoordinator(context).cancelImageInstallation(first.id.toString()) }
                assertEquals(WorkInfo.State.ENQUEUED, manager.getWorkInfoById(retry.id).get()?.state)
            }
        } finally {
            listOf(first, other, retry).forEach { manager.cancelWorkById(it.id).result.get(5, TimeUnit.SECONDS) }
        }
    }

    @Test fun approvedProfileLaunchStopsDownloadPreservesUserDataAndAllowsRetry() {
        assumeTrue("Opt-in network check", InstrumentationRegistry.getArguments().getString("installCancellation") == "true")
        val connected = CountDownLatch(1)
        lateinit var service: IRuntimeService
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = IRuntimeService.Stub.asInterface(binder)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val registry = EnvironmentRegistry(context.filesDir)
        val executor = Executors.newSingleThreadExecutor()
        assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(connected.await(10, TimeUnit.SECONDS))
            // Clean only abandoned fixtures from this opt-in test, inside the app without
            // exporting registry contents or touching existing images, profiles or sessions.
            val abandoned = registry.transaction {
                all().filter { environment ->
                    environment.name == "Cancellation test" && environment.sessionRuntime == SessionRuntime.ROOTLESS &&
                        environment.createdAt >= System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(15) &&
                        profiles().none { it.environmentId == environment.id }
                }
            }
            for (environment in abandoned) {
                val root = SessionStorage.root(context.filesDir, environment.sessionRuntime, environment.storage.id)
                if (!File(root, "rootfs").exists() &&
                    File(root, "home/keep.txt").let { it.isFile && it.readText() == "home" } &&
                    File(root, "workspace/keep.txt").let { it.isFile && it.readText() == "workspace" }) {
                    assertEquals("", service.operateEnvironment(environment.id.value, "REMOVE_SOFTWARE"))
                    assertEquals("", service.deleteEnvironment(environment.id.value))
                }
            }
            assumeTrue("All distribution fixtures need at least 4 GiB free",
                android.os.StatFs(context.filesDir.path).availableBytes >= 4L * 1024 * 1024 * 1024)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                for (runtime in listOf(SessionRuntime.ROOTLESS, SessionRuntime.ROOTLESS_UBUNTU, SessionRuntime.ROOTLESS_ARCH)) {
                    val environment = Environment.create(runtime, "Cancellation test ${UUID.randomUUID()}")
                    val profile = SessionProfile(UUID.randomUUID().toString(), "Cancellation test", runtime, "/bin/bash")
                    registry.transaction { save(profile, environment) }
                    val root = SessionStorage.root(context.filesDir, runtime, environment.storage.id)
                    val home = File(root, "home/keep.txt")
                    val workspace = File(root, "workspace/keep.txt")
                    home.parentFile!!.mkdirs()
                    home.writeText("home")
                    workspace.parentFile!!.mkdirs()
                    workspace.writeText("workspace")
                    fun status() = JSONObject(service.getEnvironmentSoftwareJson(environment.id.value))
                    try {
                        val install = executor.submit<String> {
                            val store = SessionProfiles(context)
                            service.createSessionFromSpecWithInstallationConsent(store.resolve(checkNotNull(store.find(profile.id))).toJson().toString(),
                                UUID.randomUUID().toString(), 24, 80)
                            ""
                        }
                        await("Download did not start: $runtime") {
                            val current = status()
                            assertNotEquals("Download failed: $runtime ${current.optString("detail")}", "ERROR", current.optString("state"))
                            val progress = current.optJSONObject("progress")
                            progress?.optString("phase") == "DOWNLOAD" && progress.optLong("completedBytes") > 0
                        }
                        val installing = status()
                        scenario.onActivity { activity -> activity.setContent {
                            DroidTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                                RootlessEnvironmentSoftwareSetting(installing, enabled = false, onInstall = {}, onRemove = {},
                                    onCancelInstall = { service.cancelRootlessInstallation(runtime.wireName, environment.storage.id) })
                            } }
                        } }
                        clickCancel()
                        await("Installation did not cancel: $runtime") { status().optString("state") == "CANCELLED" && !status().optBoolean("busy") }
                        assertEquals("", install.get(5, TimeUnit.SECONDS))
                        assertEquals("home", home.readText())
                        assertEquals("workspace", workspace.readText())
                        assertFalse(File(root, "rootfs").exists())
                        assertFalse(root.listFiles().orEmpty().any { it.name.startsWith(".rootfs-install-") })
                        val directory = when (runtime) {
                            SessionRuntime.ROOTLESS -> "debian"
                            SessionRuntime.ROOTLESS_UBUNTU -> "ubuntu"
                            else -> "archlinux"
                        }
                        assertTrue(File(context.cacheDir, "rootless-downloads/environments/$directory/${environment.storage.id}").listFiles().orEmpty().isEmpty())
                        val retry = executor.submit<String> { service.operateEnvironment(environment.id.value, "INSTALL") }
                        await("Retry did not start: $runtime") { status().optBoolean("canCancel") }
                        assertTrue(service.cancelRootlessInstallation(runtime.wireName, environment.storage.id))
                        assertEquals("", retry.get(45, TimeUnit.SECONDS))
                        assertEquals("CANCELLED", status().getString("state"))
                        assertFalse(service.cancelRootlessInstallation(runtime.wireName, environment.storage.id))
                    } finally {
                        service.cancelRootlessInstallation(runtime.wireName, environment.storage.id)
                        await("Installation cleanup did not finish") { !status().optBoolean("busy") }
                        // A failed fixture may retain a Failed owner; stop/remove only its new software
                        // before deleting this test-owned environment, preserving the original failure.
                        assertEquals("", service.operateEnvironment(environment.id.value, "REMOVE_SOFTWARE"))
                        registry.transaction { deleteProfile(profile.id, false) }
                        assertEquals("", service.deleteEnvironment(environment.id.value))
                    }
                }
            }
        } finally {
            executor.shutdownNow()
            context.unbindService(connection)
        }
    }
}
