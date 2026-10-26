package dev.terminal.runtime.power

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.runtime.power.RuntimeWakeController.GuestWorkState
import dev.terminal.runtime.power.RuntimeWakeController.WakePolicy
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class RuntimeWakeControllerDeviceTest {
    @Test fun screenOffRenewalNeverReleasesProtectionAndStopCancelsRenewal() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val power = instrumentation.targetContext.getSystemService(PowerManager::class.java)
        val wasInteractive = power.isInteractive
        val states = LinkedBlockingQueue<Boolean>()
        val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidTerminal:renewal-test")
        wakeLock.setStateListener({ it.run() }) { states.offer(it) }
        // Accelerate the production renewal path, using real Android timeout and state callbacks.
        val controller = RuntimeWakeController(wakeLock, Handler(Looper.getMainLooper()),
            maxHoldMillis = 2_000, renewAfterMillis = 500)
        fun shell(command: String) {
            instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
                ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.copyTo(java.io.OutputStream.nullOutputStream()) }
            }
        }
        try {
            controller.update(WakePolicy.ALWAYS_AWAKE, GuestWorkState.IDLE)
            assertEquals(true, states.poll(5, TimeUnit.SECONDS))
            shell("input keyevent KEYCODE_SLEEP")
            val screenDeadline = SystemClock.uptimeMillis() + 5_000
            while (power.isInteractive && SystemClock.uptimeMillis() < screenDeadline) SystemClock.sleep(50)
            assertFalse("Exercise renewal while the screen is off", power.isInteractive)

            // Stay beyond the original timeout. Frequent output/work updates must not postpone renewal.
            val deadline = SystemClock.uptimeMillis() + 2_750
            while (SystemClock.uptimeMillis() < deadline) {
                controller.update(WakePolicy.ALWAYS_AWAKE, GuestWorkState.IDLE)
                assertTrue("Runtime protection must outlive its initial timeout", wakeLock.isHeld)
                assertNull("Renewal must not release the wake lock, even momentarily", states.poll(50, TimeUnit.MILLISECONDS))
            }

            controller.update(WakePolicy.ALWAYS_AWAKE, GuestWorkState.STOPPED)
            assertFalse(wakeLock.isHeld)
            assertEquals(false, states.poll(5, TimeUnit.SECONDS))
            assertNull("Stopped work must not be reacquired by a queued renewal", states.poll(750, TimeUnit.MILLISECONDS))
            assertFalse(wakeLock.isHeld)

            controller.update(WakePolicy.ALWAYS_AWAKE, GuestWorkState.ACTIVE)
            assertEquals(true, states.poll(5, TimeUnit.SECONDS))
            controller.release()
            assertEquals(false, states.poll(5, TimeUnit.SECONDS))
            assertNull("Service teardown must cancel renewal", states.poll(750, TimeUnit.MILLISECONDS))
            assertFalse(wakeLock.isHeld)
        } finally {
            controller.release()
            wakeLock.setStateListener({ it.run() }, null)
            if (wasInteractive) shell("input keyevent KEYCODE_WAKEUP")
        }
    }
}
