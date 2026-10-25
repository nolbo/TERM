package dev.terminal.runtime.avf

import org.junit.Assert.assertEquals
import org.junit.Test

class AvfResourcePolicyTest {
    @Test fun supportedTopologyPreservesLegacyHostChoices() {
        assertEquals(1, AvfResourcePolicy.cpuTopology(1))
        (2..16).forEach { assertEquals(2, AvfResourcePolicy.cpuTopology(it)) }
    }
    @Test fun automaticBudgetReservesHostMemoryAndManualBudgetsSurvive() {
        fun gib(n: Long) = n * 1024 * 1024 * 1024
        assertEquals(1024, AvfResourcePolicy.memoryMib(0, gib(2)))
        assertEquals(2560, AvfResourcePolicy.memoryMib(0, gib(8)))
        assertEquals(4096, AvfResourcePolicy.memoryMib(0, gib(24)))
        assertEquals(3072, AvfResourcePolicy.memoryMib(3072, gib(8)))
        assertEquals(2048, AvfResourcePolicy.memoryMib(0, 0))
        assertEquals(1536, AvfResourcePolicy.memoryMib(0, gib(8), gib(3)))
        assertEquals(3072, AvfResourcePolicy.memoryMib(3072, gib(8), gib(1)))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            AvfResourcePolicy.memoryMib(0, gib(8), gib(1))
        }
    }
}
