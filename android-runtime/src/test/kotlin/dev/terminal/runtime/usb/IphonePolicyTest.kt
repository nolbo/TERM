package dev.terminal.runtime.usb

import org.junit.Assert.*
import org.junit.Test

class IphonePolicyTest {
    @Test fun onlyNormalModeAppleMobileDevicesAreCandidates() {
        assertTrue(IphonePolicy.candidate(0x05ac, 0x12a8))
        assertFalse(IphonePolicy.candidate(0x1234, 0x12a8))
        assertFalse(IphonePolicy.candidate(0x05ac, 0x1227)) // DFU
        assertFalse(IphonePolicy.candidate(0x05ac, 0x8600)) // T2
    }

    @Test fun commandsCannotBecomeAnArbitraryToolServiceOrPairRecordRequest() {
        for (operation in listOf("pair", "info", "unpair")) assertEquals(operation, IphonePolicy.operation(operation))
        for (operation in listOf("ReadPairRecord", "Connect", "backup", "shell", "info; id", ""))
            assertTrue(operation, runCatching { IphonePolicy.operation(operation) }.isFailure)
        assertFalse(IphonePolicy.infoKeys.contains("HostPrivateKey"))
        assertFalse(IphonePolicy.infoKeys.contains("EscrowBag"))
        assertEquals(5, IphonePolicy.infoKeys.size)
    }
}
