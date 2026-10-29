package dev.terminal.runtime.avf.system

import android.system.virtualmachine.VirtualMachine
import dev.terminal.runtime.avf.AvfPlatformEvent
import org.junit.Assert.*
import org.junit.Test

class AvfCallbackTest {
    @Test fun anErrorNeverSuppressesTheSubsequentAuthoritativeStop() {
        var current = true
        val events = mutableListOf<AvfPlatformEvent>()
        val callback = avfCallback(AvfGuestBoot(), { current }, "test", { _, _ -> }, events::add)
        val vm = VirtualMachine()
        callback.onError(vm, 1, "test")
        callback.onStopped(vm, 1)
        assertTrue(events[0] is AvfPlatformEvent.Failed)
        assertEquals(AvfPlatformEvent.Stopped, events[1])
        current = false
        callback.onStopped(vm, 1)
        assertEquals(2, events.size)
    }
}
