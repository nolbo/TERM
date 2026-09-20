package dev.terminal.app

import org.junit.Assert.*
import org.junit.Test

class ProfileDraftTest {
    @Test fun argumentsAreLiteralAndPreserveEmptyArguments() {
        assertEquals(listOf("-l", "a b", "", "\$HOME"), parseProfileArguments("[\"-l\",\"a b\",\"\",\"\$HOME\"]"))
        assertTrue(runCatching { parseProfileArguments("[1]") }.isFailure)
        assertTrue(runCatching { parseProfileArguments("-l") }.isFailure)
    }
    @Test fun environmentPreservesEqualsAndRejectsDuplicateKeys() {
        assertEquals(mapOf("A" to "b=c", "EMPTY" to ""), parseProfileEnvironment("A=b=c\nEMPTY=\n"))
        assertTrue(runCatching { parseProfileEnvironment("A=1\nA=2") }.isFailure)
        assertTrue(runCatching { parseProfileEnvironment("A") }.isFailure)
    }
}
