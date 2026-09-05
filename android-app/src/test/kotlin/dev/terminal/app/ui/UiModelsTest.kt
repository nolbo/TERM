package dev.terminal.app.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.terminal.app.fontSizeSpToPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiModelsTest {
    @Test fun sessionsRetainRuntimeStateAndExitCode() {
        val sessions = parseSessions("""[{"id":"rootless-1","runtime":"ROOTLESS","title":"apt","state":"EXITED","exitCode":7}]""")
        assertEquals(SessionUi("rootless-1", "ROOTLESS", "apt", "EXITED", 7), sessions.single())
    }

    @Test fun malformedSessionsAreEmpty() {
        assertTrue(parseSessions("not-json").isEmpty())
    }

    @Test fun missingTitleRemainsAUiConcern() {
        assertEquals("", parseSessions("""[{"id":"native-1","runtime":"NATIVE","state":"RUNNING"}]""").single().title)
    }

    @Test fun runtimeAvailabilityNeverInventsAvfSupport() {
        val choices = parseRuntimeChoices("""{"NATIVE":{"available":true,"detail":"ready"},"ROOTLESS":{"available":false,"detail":"setup"}}""")
        assertTrue(choices.single { it.id == "NATIVE" }.available)
        assertFalse(choices.single { it.id == "ROOTLESS" }.available)
        assertFalse(choices.single { it.id == "AVF" }.available)
    }

    @Test fun darkSchemeMeetsTextContrastTargets() {
        assertTextContrast(TerminalDarkColors)
    }

    @Test fun integerDraftIsCommittedOnlyWhenTheWholeValueIsValid() {
        assertEquals(8, validatedIntegerDraft("8", 8..72))
        assertEquals(null, validatedIntegerDraft("80", 8..72))
    }

    @Test fun shellDraftRequiresAnExecutableAbsoluteFile() {
        val executable = setOf("/system/bin/sh")
        assertEquals("/system/bin/sh", validatedShellDraft(" /system/bin/sh ") { it in executable })
        assertEquals(null, validatedShellDraft("/missing") { it in executable })
        assertEquals(null, validatedShellDraft("bin/sh") { true })
    }

    @Test fun fontSizeSpUsesThePlatformSpConverter() {
        assertEquals(16, fontSizeSpToPx(16) { it })
        assertEquals(48, fontSizeSpToPx(16) { it * 3f })
    }

    @Test fun rootlessSettingsExposeCheckingReadySetupAndErrorStates() {
        assertEquals(
            RootlessSettingsStatus.CHECKING,
            parseRootlessSettingsState("""{"ROOTLESS":{"state":"CHECKING","available":false,"detail":"checking"}}""").status,
        )
        assertEquals(
            RootlessSettingsStatus.READY,
            parseRootlessSettingsState("""{"ROOTLESS":{"state":"READY","available":true,"detail":"ready"}}""").status,
        )
        assertEquals(
            RootlessSettingsStatus.SETUP_REQUIRED,
            parseRootlessSettingsState("""{"ROOTLESS":{"state":"SETUP_REQUIRED","available":false,"detail":"install"}}""").status,
        )
        assertEquals(RootlessSettingsStatus.ERROR, parseRootlessSettingsState("invalid").status)
    }

    private fun assertTextContrast(colors: ColorScheme) {
        assertTrue(contrast(colors.primary, colors.onPrimary) >= 4.5f)
        assertTrue(contrast(colors.primaryContainer, colors.onPrimaryContainer) >= 4.5f)
        assertTrue(contrast(colors.surface, colors.onSurface) >= 4.5f)
        assertTrue(contrast(colors.error, colors.onError) >= 4.5f)
    }

    private fun contrast(first: Color, second: Color): Float {
        val lighter = maxOf(first.luminance(), second.luminance())
        val darker = minOf(first.luminance(), second.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
