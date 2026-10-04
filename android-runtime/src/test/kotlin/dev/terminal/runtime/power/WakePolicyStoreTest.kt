package dev.terminal.runtime.power

import dev.terminal.runtime.power.RuntimeWakeController.GuestWorkState
import dev.terminal.runtime.power.RuntimeWakeController.WakePolicy
import org.junit.Assert.*
import org.junit.Test

class WakePolicyStoreTest {
    @Test fun newAndPreviouslyImplicitDefaultsKeepPersistentSessionsAwake() {
        assertEquals(WakePolicy.ALWAYS_AWAKE, initialWakePolicy(null, null))
        assertEquals(WakePolicy.ALWAYS_AWAKE, initialWakePolicy("BATTERY_OPTIMIZED", null))
        assertEquals(WakePolicy.ALWAYS_AWAKE, initialWakePolicy("invalid", "invalid"))
    }

    @Test fun explicitPolicySurvivesTheDefaultUpgrade() {
        WakePolicy.entries.forEach { selected ->
            assertEquals(selected, initialWakePolicy("BATTERY_OPTIMIZED", selected.name))
        }
        assertEquals(WakePolicy.KEEP_ACTIVE_WORK_RUNNING, initialWakePolicy("KEEP_ACTIVE_WORK_RUNNING", null))
        assertEquals(WakePolicy.ALWAYS_AWAKE, initialWakePolicy("ALWAYS_AWAKE", null))
    }

    @Test fun defaultProtectsSilentAndIdleSessionsUntilTheRuntimeStops() {
        assertTrue(RuntimeWakeController.shouldHold(WakePolicyStore.DEFAULT_POLICY, GuestWorkState.ACTIVE))
        assertTrue(RuntimeWakeController.shouldHold(WakePolicyStore.DEFAULT_POLICY, GuestWorkState.IDLE))
        assertFalse(RuntimeWakeController.shouldHold(WakePolicyStore.DEFAULT_POLICY, GuestWorkState.STOPPED))
    }
}
