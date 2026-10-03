package dev.terminal.runtime.lifecycle

import dev.terminal.runtime.api.RuntimeState
import dev.terminal.runtime.power.RuntimeWakeController
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeWorkStateTest {
    @Test fun commandKeepsWorkActiveWithoutAUiAttachment() {
        assertEquals(
            RuntimeWakeController.GuestWorkState.ACTIVE,
            runtimeWorkState(RuntimeState.Ready, sessionWorkActive = true, guestReportedWorkActive = false, hostBackgroundWorkActive = false),
        )
    }

    @Test fun idleShellDoesNotRequestKeepActiveWakeLock() {
        val state = runtimeWorkState(RuntimeState.Ready, false, false, false)
        assertEquals(RuntimeWakeController.GuestWorkState.IDLE, state)
        org.junit.Assert.assertFalse(RuntimeWakeController.shouldHold(
            RuntimeWakeController.WakePolicy.KEEP_ACTIVE_WORK_RUNNING, state))
        org.junit.Assert.assertTrue(RuntimeWakeController.shouldHold(
            RuntimeWakeController.WakePolicy.ALWAYS_AWAKE, state))
    }

    @Test fun guestReportedWorkKeepsWorkActiveWithoutATerminalSession() {
        assertEquals(
            RuntimeWakeController.GuestWorkState.ACTIVE,
            runtimeWorkState(RuntimeState.Ready, sessionWorkActive = false, guestReportedWorkActive = true, hostBackgroundWorkActive = false),
        )
    }

    @Test fun hostInstallationIsActiveBackgroundWork() {
        assertEquals(
            RuntimeWakeController.GuestWorkState.ACTIVE,
            runtimeWorkState(RuntimeState.Stopped, sessionWorkActive = false, guestReportedWorkActive = false, hostBackgroundWorkActive = true),
        )
    }

    @Test fun runtimeLifecycleDistinguishesIdleFromStopped() {
        assertEquals(
            RuntimeWakeController.GuestWorkState.IDLE,
            runtimeWorkState(RuntimeState.Ready, sessionWorkActive = false, guestReportedWorkActive = false, hostBackgroundWorkActive = false),
        )
        assertEquals(
            RuntimeWakeController.GuestWorkState.STOPPED,
            runtimeWorkState(RuntimeState.Stopped, sessionWorkActive = false, guestReportedWorkActive = false, hostBackgroundWorkActive = false),
        )
    }

    @Test fun stoppedRuntimeWinsOverStaleSessionMetadata() {
        assertEquals(
            RuntimeWakeController.GuestWorkState.STOPPED,
            runtimeWorkState(RuntimeState.Stopped, sessionWorkActive = true, guestReportedWorkActive = true, hostBackgroundWorkActive = false),
        )
    }
}
