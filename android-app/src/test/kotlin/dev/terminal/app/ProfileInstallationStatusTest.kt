package dev.terminal.app

import dev.terminal.app.ui.ProfileInstallationState
import dev.terminal.app.ui.ProfileInstallationStatus
import org.junit.Assert.*
import org.junit.Test

class ProfileInstallationStatusTest {
    @Test fun availabilityDoesNotReplaceInstallationState() {
        val installed = ProfileInstallationStatus.fromJson("""{"installed":true,"available":false,"state":"ENVIRONMENT_REQUIRED"}""")
        assertEquals(ProfileInstallationState.INSTALLED, installed.state)
        assertFalse(installed.available)
        assertEquals(ProfileInstallationState.MISSING,
            ProfileInstallationStatus.fromJson("""{"installed":false,"available":true}""").state)
    }

    @Test fun activePreparationAndPendingDownloadAreAlreadyInstalling() {
        listOf("""{"installed":true,"state":"CHECKING"}""", """{"installed":false,"busy":true}""").forEach {
            assertEquals(ProfileInstallationState.INSTALLING, ProfileInstallationStatus.fromJson(it).state)
        }
        assertEquals(ProfileInstallationState.INSTALLED,
            ProfileInstallationStatus.fromJson("""{"installed":true,"busy":true,"state":"READY"}""").state)
    }

    @Test fun partialDataAndFinishedWorkDoNotImplyAnInstalledImage() {
        assertEquals(ProfileInstallationState.MISSING,
            ProfileInstallationStatus.fromJson("""{"installed":false,"hasData":true,"busy":false,"state":"SETUP_REQUIRED"}""").state)
    }

    @Test fun unreadableStatusNeverAssumesMissingOrInstalled() {
        listOf("", "{}", """{"error":"Unknown environment"}""", "[]").forEach {
            assertEquals(ProfileInstallationState.UNKNOWN, ProfileInstallationStatus.fromJson(it).state)
        }
    }
}
