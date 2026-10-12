package dev.terminal.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class LifecyclePayloadCodecTest {
    @Test fun roundTripsWorkState() {
        assertEquals(GuestWorkEvent.WORK_ACTIVE, LifecyclePayloadCodec.decode(LifecyclePayloadCodec.encode(GuestWorkEvent.WORK_ACTIVE)))
    }
}
