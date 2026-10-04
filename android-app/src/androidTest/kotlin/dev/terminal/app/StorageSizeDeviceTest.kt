package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.text.format.Formatter
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import dev.terminal.runtime.session.SessionProfile
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.session.SessionStorage
import dev.terminal.runtime.session.StorageRegistry
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Locale
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses Android NIO and the real service/UI with isolated fixtures, without installing a distribution. */
@RunWith(AndroidJUnit4::class)
class StorageSizeDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun withStorage(runtime: SessionRuntime, block: (IRuntimeService, SessionProfile, File) -> Unit) {
        val store = SessionProfiles(context)
        val profile = SessionProfile(UUID.randomUUID().toString(), "Capacity fixture", runtime, "/bin/sh",
            storageId = UUID.randomUUID().toString())
        store.save(profile)
        val root = SessionStorage.root(context.filesDir, runtime, profile.storageId)
        val queue = LinkedBlockingQueue<IRuntimeService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { queue.offer(IRuntimeService.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        val service = requireNotNull(queue.poll(15, TimeUnit.SECONDS))
        try {
            block(service, profile, root)
        } finally {
            store.delete(profile.id)
            assertEquals("", service.deleteStorage(runtime.wireName, profile.storageId))
            context.unbindService(connection)
        }
    }

    @Test fun rootlessPermissionLimitedTreesMeasureNonzeroAndShowPartialSize() {
        for (runtime in listOf(SessionRuntime.ROOTLESS, SessionRuntime.ROOTLESS_UBUNTU, SessionRuntime.ROOTLESS_ARCH)) {
            withStorage(runtime) { service, profile, root ->
                fun measure() = StorageRegistry.Usage.parse(service.measureStorageUsageJson(runtime.wireName, profile.storageId))
                assertEquals(StorageRegistry.Usage(0), measure())
                val shell = File(root, "rootfs/usr/bin/shell").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(65_536)) }
                val denied = File(root, "rootfs/workspace").apply { mkdirs() }
                File(denied, "hidden").writeBytes(ByteArray(8192))
                val emptyDenied = File(root, "rootfs/run/nscd").apply { mkdirs() }
                val outside = File(context.cacheDir, "capacity-outside-${UUID.randomUUID()}").apply { writeBytes(ByteArray(131_072)) }
                val link = File(root, "external-link").toPath()
                val modes = Files.getPosixFilePermissions(denied.toPath())
                try {
                    Files.createSymbolicLink(link, outside.toPath())
                    Files.setPosixFilePermissions(denied.toPath(), emptySet())
                    Files.setPosixFilePermissions(emptyDenied.toPath(), emptySet())
                    val expected = shell.length() + Files.readAttributes(link,
                        java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS).size()
                    assertEquals(StorageRegistry.Usage(expected, incomplete = true), measure())
                    assertEquals(expected, service.measureStorage(runtime.wireName, profile.storageId))
                    assertEquals(emptySet<PosixFilePermission>(), Files.getPosixFilePermissions(denied.toPath()))
                    val row = EnvironmentItem.parse(service.environmentsJson).single { it.storageId == profile.storageId }
                    assertEquals(expected, row.bytes)
                    assertTrue(row.incomplete)
                    val size = Formatter.formatShortFileSize(context, expected).uppercase(Locale.ROOT)
                    ActivityScenario.launch<StorageDetailActivity>(StorageDetailActivity.intent(context, runtime.wireName, profile.storageId)).use {
                        awaitText(context.getString(R.string.storage_size_partial, size))
                    }
                } finally {
                    Files.setPosixFilePermissions(denied.toPath(), modes)
                    Files.setPosixFilePermissions(emptyDenied.toPath(), modes)
                    outside.delete()
                }
            }
        }
    }

    @Test fun inaccessibleStorageRootShowsUnavailableInsteadOfPreviousZero() {
        withStorage(SessionRuntime.ROOTLESS) { service, profile, root ->
            assertEquals(0L, service.measureStorage(profile.runtime.wireName, profile.storageId))
            root.mkdirs()
            val modes = Files.getPosixFilePermissions(root.toPath())
            try {
                Files.setPosixFilePermissions(root.toPath(), emptySet())
                assertTrue("The JSON RPC must deliver a failure instead of an empty reply",
                    runCatching { service.measureStorageUsageJson(profile.runtime.wireName, profile.storageId) }.exceptionOrNull() is IllegalStateException)
                assertTrue("The compatibility RPC must deliver a failure instead of zero",
                    runCatching { service.measureStorage(profile.runtime.wireName, profile.storageId) }.exceptionOrNull() is IllegalStateException)
                ActivityScenario.launch<StorageDetailActivity>(StorageDetailActivity.intent(context, profile.runtime.wireName, profile.storageId)).use {
                    awaitText(context.getString(R.string.storage_size_unknown))
                }
            } finally {
                Files.setPosixFilePermissions(root.toPath(), modes)
            }
        }
    }

    private fun awaitText(expected: String) {
        fun contains(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.isVisibleToUser && node.text?.toString() == expected) return true
            return (0 until node.childCount).any { contains(node.getChild(it)) }
        }
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (true) {
            instrumentation.uiAutomation.clearCache()
            if (contains(instrumentation.uiAutomation.rootInActiveWindow)) return
            check(SystemClock.elapsedRealtime() < deadline) { "Missing storage size: $expected" }
            SystemClock.sleep(100)
        }
    }
}
