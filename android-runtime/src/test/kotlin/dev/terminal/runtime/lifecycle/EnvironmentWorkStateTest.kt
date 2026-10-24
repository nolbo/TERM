package dev.terminal.runtime.lifecycle

import dev.terminal.runtime.power.RuntimeWakeController
import org.junit.Assert.*
import org.junit.Test

class EnvironmentWorkStateTest {
    @Test fun silentSessionKeepsAlwaysAwakeWithoutAnUnrelatedBackend() {
        val state = environmentWorkState(hasWork = true, false, false, false)
        assertEquals(RuntimeWakeController.GuestWorkState.IDLE, state)
        assertTrue(RuntimeWakeController.shouldHold(RuntimeWakeController.WakePolicy.ALWAYS_AWAKE, state))
        assertFalse(RuntimeWakeController.shouldHold(RuntimeWakeController.WakePolicy.KEEP_ACTIVE_WORK_RUNNING, state))
    }

    @Test fun explicitlyStartedEnvironmentKeepsProtectionWithoutASession() {
        val demand = reconnectNeeded(0, 0, false, startedEnvironments = 1)
        val state = environmentWorkState(demand, false, false, false)
        assertTrue(RuntimeWakeController.shouldHold(RuntimeWakeController.WakePolicy.ALWAYS_AWAKE, state))
    }

    @Test fun commandGuestAndHostWorkRemainActiveWithoutAUi() {
        for (signals in listOf(listOf(true, false, false), listOf(false, true, false), listOf(false, false, true))) {
            assertEquals(RuntimeWakeController.GuestWorkState.ACTIVE,
                environmentWorkState(true, signals[0], signals[1], signals[2]))
        }
    }

    @Test fun noRemainingEnvironmentWorkReleasesProtection() {
        val state = environmentWorkState(false, false, false, false)
        assertEquals(RuntimeWakeController.GuestWorkState.STOPPED, state)
        assertFalse(RuntimeWakeController.shouldHold(RuntimeWakeController.WakePolicy.ALWAYS_AWAKE, state))
    }
}
