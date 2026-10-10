package dev.terminal.app.ui

import dev.terminal.app.R
import org.junit.Assert.*
import org.junit.Test

class InstallationStepsTest {
    @Test fun packageOperationsShareOneStageWithoutAdvancingDuringRetries() {
        val downloading = rootlessInstallationSteps("PACKAGE_DOWNLOAD", active = true)
        val recovering = rootlessInstallationSteps("PACKAGE_RECOVERY", active = true)
        assertEquals(downloading, recovering)
        assertEquals(R.string.install_stage_packages, downloading.single { it.state == InstallationStepState.RUNNING }.title)
        assertTrue(downloading.takeLast(3).all { it.state == InstallationStepState.PENDING })
    }

    @Test fun failureStopsAtObservedStageAndSuccessCompletesTheList() {
        val failed = rootlessInstallationSteps("VERIFY_COMMANDS", active = false, failed = true)
        assertEquals(R.string.install_stage_environment_verify, failed.single { it.state == InstallationStepState.FAILED }.title)
        assertEquals(InstallationStepState.PENDING, failed.last().state)
        assertTrue(rootlessInstallationSteps("READY", active = false).all { it.state == InstallationStepState.COMPLETE })
    }

    @Test fun missingUnknownAndCancelledPhasesNeverInventCompletion() {
        for (phase in listOf(null, "NEW_PHASE", "CANCELLED")) {
            assertTrue(rootlessInstallationSteps(phase, active = false).isEmpty())
        }
        assertTrue(rootlessInstallationSteps("DOWNLOAD", active = false, cancelled = true).isEmpty())
        assertTrue(installationSteps(listOf(1, 2, 3), null, active = true).isEmpty())
    }
}
