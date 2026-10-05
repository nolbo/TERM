package dev.terminal.app

import android.content.*
import android.os.IBinder
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.MutableState
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dev.terminal.runtime.image.DebianImageDownloadWorker
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import dev.terminal.runtime.session.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Removal uses the real runtime owner; AVF dispatch fixtures never boot or download a VM. */
@RunWith(AndroidJUnit4::class)
class EnvironmentSoftwareTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (!condition()) { check(SystemClock.elapsedRealtime() < deadline) { "Environment software UI timed out" }; SystemClock.sleep(100) }
    }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        instrumentation.waitForIdleSync(); instrumentation.uiAutomation.clearCache()
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && predicate(node)) return node
            for (index in 0 until node.childCount) walk(node.getChild(index))?.let { return it }
            return null
        }
        return walk(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun clickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        while (current != null && !current.isClickable) current = current.parent
        return current
    }
    private fun scrollTo(label: Int) = scrollToText(context.getString(label))
    private fun scrollToText(text: String) {
        await {
            if (find { it.text?.toString() == text } != null) true else {
                val bounds = android.graphics.Rect().also { find { it.isScrollable }?.getBoundsInScreen(it) }
                if (!bounds.isEmpty) {
                    val start = bounds.top + bounds.height() * .7f
                    val end = bounds.top + bounds.height() * .35f
                    val down = SystemClock.uptimeMillis()
                    fun touch(action: Int, y: Float) {
                        MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), y, 0).also {
                            it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                            assertTrue(instrumentation.uiAutomation.injectInputEvent(it, true)); it.recycle()
                        }
                    }
                    touch(MotionEvent.ACTION_DOWN, start)
                    repeat(8) { step -> SystemClock.sleep(25); touch(MotionEvent.ACTION_MOVE, start + (end - start) * (step + 1) / 8) }
                    touch(MotionEvent.ACTION_UP, end)
                }
                false
            }
        }
    }
    private fun click(label: Int) {
        await {
            clickable(find { it.text?.toString() == context.getString(label) && clickable(it)?.isEnabled == true })
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
    }
    private fun withRuntime(block: (IRuntimeService) -> Unit) {
        val queue = LinkedBlockingQueue<IRuntimeService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { queue.offer(IRuntimeService.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        try { block(requireNotNull(queue.poll(15, TimeUnit.SECONDS))) } finally { context.unbindService(connection) }
    }
    private fun fixture(runtime: SessionRuntime): Pair<Environment, SessionProfile> {
        val environment = Environment.create(runtime, "Software fixture ${UUID.randomUUID()}")
        return environment to SessionProfiles(context).save(SessionProfile(UUID.randomUUID().toString(), environment.name,
            runtime, "/bin/bash"), environment)
    }
    private fun file(environment: Environment, path: String) = File(SessionStorage.root(context.filesDir,
        environment.sessionRuntime, environment.storage.id), path).apply { parentFile!!.mkdirs(); writeText("fixture") }

    @Suppress("UNCHECKED_CAST")
    private fun activityRuntime(activity: EnvironmentScreenActivity) = EnvironmentScreenActivity::class.java.getDeclaredField("runtime\$delegate")
        .apply { isAccessible = true }.get(activity) as MutableState<IRuntimeService?>

    @Test fun rootlessInstallationUsesTheEnvironmentIdAndShowsOwnerProgressWithoutCreatingASession() {
        val (environment, profile) = fixture(SessionRuntime.ROOTLESS_UBUNTU)
        val requested = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        val started = SystemClock.elapsedRealtime()
        val row = environment.toJson().put("profiles", JSONArray().put(JSONObject().put("id", profile.id).put("name", profile.name)))
            .put("sessions", JSONArray()).put("bytes", 0).put("canDelete", false).put("runtimeState", "STOPPED")
        val proxy = Proxy.newProxyInstance(IRuntimeService::class.java.classLoader, arrayOf(IRuntimeService::class.java)) { instance, method, args ->
            when (method.name) {
                "equals" -> instance === args?.get(0)
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> "Rootless preparation owner fixture"
                "asBinder" -> null
                "getEnvironmentsJson" -> JSONArray().put(row).toString()
                "measureEnvironmentUsageJson" -> "{\"bytes\":0,\"incomplete\":false}"
                "getEnvironmentSoftwareJson" -> JSONObject().put("installed", false).put("canInstall", requested.isEmpty())
                    .put("canRemove", false).put("busy", requested.isNotEmpty()).put("state", if (requested.isEmpty()) "SETUP_REQUIRED" else "CHECKING")
                    .put("detail", "fixture").apply {
                        if (requested.isNotEmpty()) put("progress", JSONObject().put("phase", "DOWNLOAD")
                            .put("label", "Scoped installation progress").put("detail", "fixture")
                            .put("startedAtElapsedMs", started).put("updatedAtElapsedMs", started))
                    }.toString()
                "operateEnvironment" -> { requested += args!![0] as String to args[1] as String; "" }
                else -> error("Installation must not create sessions: ${method.name}")
            }
        } as IRuntimeService
        try {
            ActivityScenario.launch<EnvironmentDetailActivity>(EnvironmentDetailActivity.intent(context, environment.id.value)).use { scenario ->
                await { var bound = false; scenario.onActivity { bound = activityRuntime(it).value != null }; bound }
                scenario.onActivity { activityRuntime(it).value = proxy }
                scrollTo(R.string.environment_distribution_install)
                click(R.string.environment_distribution_install)
                await { find { it.text?.toString() == context.getString(R.string.rootless_install_note) } != null }
                click(R.string.environment_distribution_install)
                await { requested.size == 1 }
                scrollToText("Scoped installation progress")
                assertNotNull(find { it.text?.toString() == "Scoped installation progress" })
                assertEquals(environment.id.value to "INSTALL", requested.single())
                scenario.recreate()
                await { var bound = false; scenario.onActivity { bound = activityRuntime(it).value != null }; bound }
                scenario.onActivity { activityRuntime(it).value = proxy }
                scrollToText("Scoped installation progress")
                await { find { it.text?.toString() == "Scoped installation progress" } != null }
                assertEquals(1, requested.size)
            }
        } finally {
            SessionProfiles(context).delete(profile.id)
            EnvironmentRegistry(context.filesDir).transaction { delete(environment.id) }
        }
    }

    @Test fun rootlessRemovalKeepsProfilesHomeAndSiblingAndOffersReinstallation() {
        val store = SessionProfiles(context)
        val (environment, profile) = fixture(SessionRuntime.ROOTLESS)
        val (sibling, otherProfile) = fixture(SessionRuntime.ROOTLESS)
        val image = file(environment, "rootfs/fixture")
        val home = file(environment, "home/keep")
        val workspace = file(environment, "workspace/keep")
        val other = file(sibling, "rootfs/keep")
        withRuntime { service ->
            try {
                ActivityScenario.launch<EnvironmentDetailActivity>(EnvironmentDetailActivity.intent(context, environment.id.value)).use {
                    scrollTo(R.string.environment_distribution_remove)
                    click(R.string.environment_distribution_remove)
                    click(R.string.cancel)
                    assertTrue(image.exists())
                    click(R.string.environment_distribution_remove)
                    click(R.string.environment_distribution_remove)
                    await { !image.exists() }
                    assertTrue(home.exists()); assertTrue(workspace.exists()); assertTrue(other.exists())
                    assertEquals(environment.id, SessionProfiles(context).find(profile.id)?.environmentId)
                    scrollTo(R.string.environment_distribution_install)
                    if (JSONObject(service.getEnvironmentSoftwareJson(environment.id.value)).getBoolean("installable")) {
                        click(R.string.environment_distribution_install)
                        await { find { it.text?.toString() == context.getString(R.string.rootless_install_note) } != null }
                        click(R.string.cancel)
                    }
                    assertFalse(image.exists())
                    file(environment, "rootfs/reinstalled-fixture")
                    scrollTo(R.string.environment_distribution_remove)
                    click(R.string.environment_distribution_remove)
                    await { find { it.isCheckable } != null }
                    assertTrue(requireNotNull(find { it.isCheckable }).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    click(R.string.environment_distribution_remove)
                    await { !home.exists() }
                    assertTrue(workspace.exists()); assertTrue(other.exists())
                    assertEquals(sibling.id, SessionProfiles(context).find(otherProfile.id)?.environmentId)
                }
            } finally {
                listOf(profile.id, otherProfile.id).forEach(store::delete)
                assertEquals("", service.deleteEnvironment(environment.id.value))
                assertEquals("", service.deleteEnvironment(sibling.id.value))
            }
        }
    }

    @Test fun environmentDeletionRemovesDistributionCacheAndAllOwnedData() {
        val store = SessionProfiles(context)
        for ((runtime, distribution) in listOf(SessionRuntime.ROOTLESS to "debian", SessionRuntime.ROOTLESS_UBUNTU to "ubuntu", SessionRuntime.ROOTLESS_ARCH to "archlinux")) {
            val (environment, profile) = fixture(runtime)
            val (sibling, otherProfile) = fixture(runtime)
            val image = file(environment, "rootfs/readonly/package")
            file(environment, "home/keep")
            file(environment, "workspace/keep")
            val other = file(sibling, "rootfs/keep")
            val downloads = File(context.cacheDir, "rootless-downloads/environments/$distribution/${environment.storage.id}")
            File(downloads, "archive.tar.xz").apply { parentFile!!.mkdirs(); writeText("cache") }
            val otherCache = File(context.cacheDir, "rootless-downloads/environments/$distribution/${sibling.storage.id}/keep")
                .apply { parentFile!!.mkdirs(); writeText("retained") }
            val shared = File(context.filesDir, "workspace/deletion-fixture-${UUID.randomUUID()}")
                .apply { parentFile!!.mkdirs(); writeText("retained") }
            Files.createSymbolicLink(File(image.parentFile!!.parentFile, "shared").toPath(), shared.parentFile!!.toPath())
            Files.setPosixFilePermissions(image.parentFile!!.toPath(), PosixFilePermissions.fromString("r-xr-xr-x"))
            withRuntime { service ->
                try {
                    assertTrue("Linked profiles must prevent all removal", service.deleteEnvironment(environment.id.value).isNotEmpty())
                    assertTrue(image.exists()); assertTrue(downloads.exists())
                    store.delete(profile.id)
                    ActivityScenario.launch<EnvironmentDetailActivity>(EnvironmentDetailActivity.intent(context, environment.id.value)).use {
                        fun deleteAction() = await {
                            clickable(find { it.contentDescription?.toString() == context.getString(R.string.storage_delete) })
                                ?.takeIf { it.isEnabled }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                        }
                        deleteAction()
                        val warning = context.getString(R.string.environment_delete_rootless_confirm, environment.name)
                        await { find { it.text?.toString() == warning } != null }
                        click(R.string.cancel)
                        assertTrue(image.exists()); assertTrue(downloads.exists())
                        deleteAction()
                        click(R.string.storage_delete)
                        await { !SessionStorage.root(context.filesDir, runtime, environment.storage.id).exists() && !downloads.exists() }
                    }
                    assertEquals("fixture", other.readText())
                    assertEquals("retained", otherCache.readText())
                    assertEquals("retained", shared.readText())
                    val environments = JSONArray(service.environmentsJson)
                    assertTrue((0 until environments.length()).none { environments.getJSONObject(it).getString("id") == environment.id.value })
                } finally {
                    listOf(profile.id, otherProfile.id).forEach(store::delete)
                    service.deleteEnvironment(environment.id.value)
                    assertEquals("", service.deleteEnvironment(sibling.id.value))
                    shared.delete()
                }
            }
        }
    }

    @Test fun avfRemovalIsBlockedDuringAnApprovedInstallWithoutTouchingEitherEnvironment() {
        val (environment, profile) = fixture(SessionRuntime.AVF)
        val (sibling, otherProfile) = fixture(SessionRuntime.AVF)
        val disk = file(environment, "home.img")
        val other = file(sibling, "home.img")
        val manager = WorkManager.getInstance(context)
        val workName = "debian-image-download-${environment.storage.id}"
        val work = OneTimeWorkRequestBuilder<DebianImageDownloadWorker>().setInitialDelay(1, TimeUnit.DAYS).build()
        manager.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, work).result.get(5, TimeUnit.SECONDS)
        try {
            withRuntime { service ->
                assertTrue(service.operateEnvironment(environment.id.value, "REMOVE_SOFTWARE").contains("이미지 설치 중"))
                assertFalse(JSONObject(service.getEnvironmentSoftwareJson(environment.id.value)).getBoolean("canRemove"))
                assertTrue(service.deleteEnvironment(environment.id.value).contains("이미지 설치 중"))
                val environments = JSONArray(service.environmentsJson)
                assertFalse((0 until environments.length()).map { environments.getJSONObject(it) }
                    .single { it.getString("id") == environment.id.value }.getBoolean("canDelete"))
                assertTrue(disk.exists()); assertTrue(other.exists())
                assertEquals(environment.id, SessionProfiles(context).find(profile.id)?.environmentId)
            }
        } finally {
            manager.cancelUniqueWork(workName).result.get(5, TimeUnit.SECONDS)
            val store = SessionProfiles(context)
            listOf(profile.id, otherProfile.id).forEach(store::delete)
            EnvironmentRegistry(context.filesDir).transaction { delete(environment.id); delete(sibling.id) }
        }
    }

    @Test fun avfEnvironmentDeletionConfirmsImageAndVmDataBeforeDispatch() {
        val (environment, profile) = fixture(SessionRuntime.AVF)
        val disk = file(environment, "home.img")
        val requested = java.util.concurrent.CopyOnWriteArrayList<String>()
        val row = environment.toJson().put("profiles", JSONArray()).put("sessions", JSONArray())
            .put("bytes", 7).put("canDelete", true).put("runtimeState", "UNKNOWN")
        val proxy = Proxy.newProxyInstance(IRuntimeService::class.java.classLoader, arrayOf(IRuntimeService::class.java)) { instance, method, args ->
            when (method.name) {
                "equals" -> instance === args?.get(0)
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> "AVF deletion UI fixture"
                "asBinder" -> null
                "getEnvironmentsJson" -> JSONArray().put(row).toString()
                "measureEnvironmentUsageJson" -> "{\"bytes\":7,\"incomplete\":false}"
                "getEnvironmentSoftwareJson" -> "{\"installed\":false,\"hasData\":true,\"busy\":false,\"canRemove\":true}"
                "deleteEnvironment" -> { requested += args!![0] as String; "" }
                else -> error("Unexpected AVF deletion call: ${method.name}")
            }
        } as IRuntimeService
        try {
            ActivityScenario.launch<EnvironmentDetailActivity>(EnvironmentDetailActivity.intent(context, environment.id.value)).use { scenario ->
                await { var bound = false; scenario.onActivity { bound = activityRuntime(it).value != null }; bound }
                scenario.onActivity { activityRuntime(it).value = proxy }
                fun deleteAction() = await {
                    clickable(find { it.contentDescription?.toString() == context.getString(R.string.storage_delete) })
                        ?.takeIf { it.isEnabled }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                }
                deleteAction()
                val warning = context.getString(R.string.environment_delete_avf_confirm, environment.name)
                await { find { it.text?.toString() == warning } != null }
                click(R.string.cancel)
                assertTrue(requested.isEmpty()); assertTrue(disk.exists())
                deleteAction()
                click(R.string.storage_delete)
                await { requested.size == 1 }
                assertEquals(environment.id.value, requested.single())
            }
        } finally {
            SessionProfiles(context).delete(profile.id)
            EnvironmentRegistry(context.filesDir).transaction { delete(environment.id) }
        }
    }

    @Test fun avfDetailDispatchesDebianRemovalToItsEnvironmentAndKeepsProfileIdentity() {
        val (environment, profile) = fixture(SessionRuntime.AVF)
        val disk = file(environment, "home.img") // Deliberately partial: UI must offer cleanup of a failed image too.
        val requested = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        val row = environment.toJson().put("profiles", JSONArray().put(JSONObject().put("id", profile.id).put("name", profile.name)))
            .put("sessions", JSONArray()).put("bytes", 7).put("canDelete", false).put("runtimeState", "STOPPED")
        val proxy = Proxy.newProxyInstance(IRuntimeService::class.java.classLoader, arrayOf(IRuntimeService::class.java)) { instance, method, args ->
            when (method.name) {
                "equals" -> instance === args?.get(0)
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> "Environment software UI fixture"
                "asBinder" -> null
                "getEnvironmentsJson" -> JSONArray().put(row).toString()
                "measureEnvironmentUsageJson" -> "{\"bytes\":7,\"incomplete\":false}"
                "getEnvironmentSoftwareJson" -> JSONObject().put("installed", false).put("hasData", disk.exists())
                    .put("canRemove", disk.exists()).put("busy", false).toString()
                "operateEnvironment" -> {
                    requested += args!![0] as String to args[1] as String
                    EnvironmentRegistry(context.filesDir).transaction { removeSoftware(environment.id) }
                    ""
                }
                else -> error("Unexpected environment software call: ${method.name}")
            }
        } as IRuntimeService
        try {
            ActivityScenario.launch<EnvironmentDetailActivity>(EnvironmentDetailActivity.intent(context, environment.id.value)).use { scenario ->
                await { var bound = false; scenario.onActivity { bound = activityRuntime(it).value != null }; bound }
                scenario.onActivity { activityRuntime(it).value = proxy }
                scrollTo(R.string.environment_debian_remove)
                click(R.string.environment_debian_remove)
                await { find { it.text?.toString() == context.getString(R.string.environment_debian_remove_message) } != null }
                click(R.string.cancel)
                assertTrue(requested.isEmpty()); assertTrue(disk.exists())
                click(R.string.environment_debian_remove)
                click(R.string.environment_debian_remove)
                await { requested.size == 1 && !disk.exists() }
                assertEquals(environment.id.value to "REMOVE_SOFTWARE", requested.single())
                assertEquals(environment.id, SessionProfiles(context).find(profile.id)?.environmentId)
                scrollTo(R.string.avf_image_install)
                assertNotNull(find { it.text?.toString() == context.getString(R.string.avf_image_install) })
            }
        } finally {
            SessionProfiles(context).delete(profile.id)
            EnvironmentRegistry(context.filesDir).transaction { delete(environment.id) }
        }
    }
}
