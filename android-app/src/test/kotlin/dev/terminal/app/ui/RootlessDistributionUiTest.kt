package dev.terminal.app.ui

import dev.terminal.app.sessionShell
import org.junit.Assert.*
import org.junit.Test

class RootlessDistributionUiTest {
    @Test fun newDistributionsOfferInstallationAndNeverUseNativeShell() {
        val choices = parseRuntimeChoices("{}")
        for (id in rootlessRuntimeIds) {
            assertEquals(RuntimeSelectionAction.INSTALL, runtimeSelectionAction(choices.single { it.id == id }))
            assertEquals("/bin/bash", sessionShell(id))
        }
    }

    @Test fun installedButIncompleteEnvironmentCanBeCompletedOrRemoved() {
        val json = """{"ROOTLESS":{"state":"ENVIRONMENT_REQUIRED","installed":true,"available":false,"installable":true,"detail":"complete defaults"}}"""
        val state = parseRootlessSettingsState(json)
        assertEquals(RootlessSettingsStatus.ENVIRONMENT_REQUIRED, state.status)
        assertTrue(state.installed)
        assertEquals(RuntimeSelectionAction.INSTALL, runtimeSelectionAction(parseRuntimeChoices(json).first { it.id == "ROOTLESS" }))
        assertTrue(parseRootlessSettingsState("""{"ROOTLESS":{"state":"ERROR","installed":true,"detail":"package failure"}}""").installed)
    }

    @Test fun readinessAndSettingsBelongToTheSelectedDistribution() {
        val json = """{"ROOTLESS":{"state":"READY","available":true,"detail":"Debian"},"ROOTLESS_UBUNTU":{"state":"CHECKING","available":false,"detail":"Ubuntu"},"ROOTLESS_ARCH":{"state":"ERROR","available":false,"detail":"Arch"}}"""
        assertEquals(RootlessSettingsStatus.CHECKING, parseRootlessSettingsState(json, "ROOTLESS_UBUNTU").status)
        assertEquals("Arch", parseRootlessSettingsState(json, "ROOTLESS_ARCH").detail)
        val choices = parseRuntimeChoices(json)
        assertEquals(RuntimeSelectionAction.CREATE, runtimeSelectionAction(choices.single { it.id == "ROOTLESS" }))
        assertFalse(choices.single { it.id == "ROOTLESS_ARCH" }.available)
    }
}
