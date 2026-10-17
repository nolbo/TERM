package dev.terminal.app

import dev.terminal.runtime.session.EnvironmentDistribution
import dev.terminal.runtime.session.SessionRuntime
import org.junit.Assert.*
import org.junit.Test

class ProfileDraftTest {
    @Test fun defaultNamesUseTheSelectedRuntimeOrDistribution() {
        val expected = mapOf(SessionRuntime.NATIVE to "Native", SessionRuntime.ROOTLESS to "Debian",
            SessionRuntime.ROOTLESS_UBUNTU to "Ubuntu 24.04 LTS", SessionRuntime.ROOTLESS_ARCH to "Arch Linux ARM")
        expected.forEach { (runtime, name) ->
            assertEquals(name, defaultProfileName(runtime, EnvironmentDistribution.DEBIAN, emptyList()))
        }
        EnvironmentDistribution.entries.forEach { distribution ->
            assertEquals(distribution.avfDistribution.displayName,
                defaultProfileName(SessionRuntime.AVF, distribution, emptyList()))
        }
    }

    @Test fun defaultNamesUseExistingEnvironmentNamesAndSkipOccupiedSuffixes() {
        assertEquals("Native (4)", defaultProfileName(SessionRuntime.NATIVE, EnvironmentDistribution.DEBIAN,
            listOf("Native", "Native (2)", "Native (3)")))
        assertEquals("My Ubuntu (3)", defaultProfileName(SessionRuntime.ROOTLESS_UBUNTU, EnvironmentDistribution.DEBIAN,
            listOf("My Ubuntu", "My Ubuntu (2)"), "My Ubuntu"))
    }

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
