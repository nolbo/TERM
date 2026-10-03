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
import dev.terminal.runtime.lifecycle.RuntimeBackendProvider
import dev.terminal.runtime.lifecycle.VmRuntimeService
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AvfDriverIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun missingDeviceApiIsRejectedWithoutLoadingPlatformClasses() {
        assumeFalse(context.packageManager.hasSystemFeature("android.software.virtualization_framework"))
        val provider = context.applicationContext as RuntimeBackendProvider
        val failure = runCatching { provider.createRuntimeBackend(context) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("does not support"))
    }

    @Test fun packagedProviderKeepsNativeServiceAndShellWorkingWithoutAvf() {
        assertTrue(context.applicationContext is RuntimeBackendProvider)
        val queue = LinkedBlockingQueue<IRuntimeService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                queue.offer(IRuntimeService.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val marker = File(context.cacheDir, "avf-driver-native-${System.nanoTime()}")
        var service: IRuntimeService? = null
        var owned: String? = null
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
            try {
                service = requireNotNull(queue.poll(10, TimeUnit.SECONDS))
                val runtime = requireNotNull(service)
                assertTrue(JSONObject(runtime.runtimeStatusJson).getJSONObject("NATIVE").getBoolean("available"))
                fun sessions(): List<String> {
                    val values = JSONArray(runtime.sessionsJson)
                    return List(values.length()) { values.getJSONObject(it).getString("id") }
                }
                val before = sessions().toSet()
                runtime.createSession("NATIVE", "/system/bin/sh", "", 24, 80)
                await { sessions().firstOrNull { id -> id !in before }?.also { id -> owned = id } != null }
                runtime.writeSessionInput(requireNotNull(owned), "printf native-ok > '${marker.absolutePath}'\n".toByteArray())
                await { marker.exists() }
                assertEquals("native-ok", marker.readText())
            } finally {
                owned?.let { id -> service?.terminateSession(id) }
                context.unbindService(connection)
                marker.delete()
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue("Runtime did not reach the expected state", condition())
    }
}
