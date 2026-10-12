package dev.terminal.app.ui

import org.junit.Assert.*
import org.junit.Test

class RootlessProgressUiTest {
    @Test fun reconnectSnapshotRetainsDistributionPhaseAndMeasuredProgress() {
        val json = """{"ROOTLESS_UBUNTU":{"state":"CHECKING","installed":true,"installable":true,"detail":"bash 설치",
          "progress":{"phase":"PACKAGE_INSTALL","label":"패키지 설치","detail":"bash 설치","fraction":0.42,
          "startedAtElapsedMs":1000,"updatedAtElapsedMs":5000}},"ROOTLESS_ARCH":{"state":"READY","available":true}}"""
        val state = parseRootlessSettingsState(json, "ROOTLESS_UBUNTU")
        assertTrue(state.installed)
        assertEquals(RootlessSettingsStatus.CHECKING, state.status)
        assertEquals("PACKAGE_INSTALL", state.progress!!.phase)
        assertEquals(.42f, state.progress.fraction)
        val choices = parseRuntimeChoices(json)
        assertEquals(state.progress, choices.single { it.id == "ROOTLESS_UBUNTU" }.progress)
        assertNull(choices.single { it.id == "ROOTLESS_ARCH" }.progress)
    }

    @Test fun unknownTotalsAndLegacyStatusesDoNotProduceFakePercentages() {
        val unknown = """{"ROOTLESS":{"state":"CHECKING","progress":{"phase":"EXTRACT","label":"압축 해제",
          "startedAtElapsedMs":100,"updatedAtElapsedMs":200,"fraction":200}}}"""
        assertNull(parseRootlessSettingsState(unknown).progress!!.fraction)
        assertNull(parseRootlessSettingsState("""{"ROOTLESS":{"state":"CHECKING"}}""").progress)
    }
}
