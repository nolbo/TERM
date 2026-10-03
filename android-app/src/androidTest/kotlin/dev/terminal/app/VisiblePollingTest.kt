package dev.terminal.app

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.terminal.app.ui.VisiblePolling
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisiblePollingTest {
    @Test fun stopCancelsPollingWithoutBackgroundRecompositionAndStartResumesIt() {
        val samples = AtomicInteger()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    VisiblePolling {
                        while (isActive) { samples.incrementAndGet(); delay(20) }
                    }
                }
            }
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (samples.get() == 0 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
            assertTrue(samples.get() > 0)
            scenario.moveToState(Lifecycle.State.CREATED)
            val stopped = samples.get()
            SystemClock.sleep(150)
            assertEquals(stopped, samples.get())
            scenario.moveToState(Lifecycle.State.RESUMED)
            val resumedDeadline = SystemClock.uptimeMillis() + 5_000
            while (samples.get() == stopped && SystemClock.uptimeMillis() < resumedDeadline) SystemClock.sleep(20)
            assertTrue(samples.get() > stopped)
        }
    }
}
