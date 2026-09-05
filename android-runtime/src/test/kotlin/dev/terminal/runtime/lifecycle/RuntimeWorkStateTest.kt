package dev.terminal.runtime.lifecycle

import dev.terminal.runtime.api.RuntimeState
import dev.terminal.runtime.power.RuntimeWakeController
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeWorkStateTest {
    @Test fun runningSessionKeepsWorkActiveWithoutAUiAttachment() {
        assertEquals(
            RuntimeWakeController.GuestWorkState.ACTIVE,
            runtimeWorkState(RuntimeState.Ready, runningSessionCount = 1, guestReportedWorkActive = false, hostBackgroundWorkActive = false),
        )
    }

    @Test fun guestReportedWorkKeepsWorkActiveWithoutATerminalSession() {
        assertEquals(
            RuntimeWakeController.GuestWorkState.ACTIVE,
            runtimeWorkState(RuntimeState.Ready, runningSessionCount = 0, guestReportedWorkActive = true, hostBackgroundWorkActive = false),
        )
    }

    @Test fun hostInstallationIsActiveBackgroundWork() {
        assertEquals(
            RuntimeWakeController.GuestWorkState.ACTIVE,
            runtimeWorkState(RuntimeState.Stopped, runningSessionCount = 0, guestReportedWorkActive = false, hostBackgroundWorkActive = true),
        )
    }

    @Test fun runtimeLifecycleDistinguishesIdleFromStopped() {
        assertEquals(
            RuntimeWakeController.GuestWorkState.IDLE,
            runtimeWorkState(RuntimeState.Ready, runningSessionCount = 0, guestReportedWorkActive = false, hostBackgroundWorkActive = false),
        )
        assertEquals(
            RuntimeWakeController.GuestWorkState.STOPPED,
            runtimeWorkState(RuntimeState.Stopped, runningSessionCount = 0, guestReportedWorkActive = false, hostBackgroundWorkActive = false),
        )
    }

    @Test fun stoppedRuntimeWinsOverStaleSessionMetadata() {
        assertEquals(
            RuntimeWakeController.GuestWorkState.STOPPED,
            runtimeWorkState(RuntimeState.Stopped, runningSessionCount = 1, guestReportedWorkActive = true, hostBackgroundWorkActive = false),
        )
    }
}
