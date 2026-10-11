package dev.terminal.app

import android.content.*
import android.os.*
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.lifecycle.VmRuntimeService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MemoryDiagnosticsUiTest {
    @Test fun samplesBothProcessesRefreshesAndStopsWhenInactiveWithoutChangingSessions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connected = CountDownLatch(1)
        lateinit var service: IRuntimeService
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = IRuntimeService.Stub.asInterface(binder); connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(connected.await(10, TimeUnit.SECONDS))
            val sessions = service.sessionsJson
            val state = service.state
            ActivityScenario.launch(MemoryDiagnosticsActivity::class.java).use { scenario ->
                fun sample(condition: (MemorySnapshot) -> Boolean = { true }): MemorySnapshot {
                    val deadline = SystemClock.elapsedRealtime() + 8000
                    while (SystemClock.elapsedRealtime() < deadline) {
                        var snapshot: MemorySnapshot? = null
                        scenario.onActivity { snapshot = it.snapshot }
                        snapshot?.takeIf(condition)?.let { return it }
                        SystemClock.sleep(50)
                    }
                    throw AssertionError("Memory sample timed out")
                }
                val first = sample { it.runtime?.memoryBytes("total") != null }
                assertTrue(requireNotNull(first.device).total > 0)
                assertEquals(first.ui!!.getLong("total") + first.runtime!!.getLong("total"), first.app!!.getLong("total"))
                scenario.onActivity { it.refresh() }
                val refreshed = sample { it.sampledAt > first.sampledAt }
                scenario.moveToState(Lifecycle.State.CREATED)
                var stopped: MemoryDiagnosticsActivity? = null
                scenario.onActivity { stopped = it; assertFalse(it.measuring) }
                val pausedSample = stopped!!.snapshot!!.sampledAt
                SystemClock.sleep(5500)
                scenario.onActivity { assertEquals(pausedSample, it.snapshot!!.sampledAt) }
                scenario.moveToState(Lifecycle.State.RESUMED)
                sample { it.sampledAt > refreshed.sampledAt }
                scenario.recreate()
                sample { it.runtime?.memoryBytes("total") != null }
            }
            assertEquals(sessions, service.sessionsJson)
            assertEquals(state, service.state)
        } finally { context.unbindService(connection) }
    }
}
