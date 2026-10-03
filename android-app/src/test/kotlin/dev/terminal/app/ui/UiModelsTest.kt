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
    @Test fun explicitTitleKeepsLiveAutomaticTitleAsSubtitle() {
        val session = parseSessions("""[{"id":"native-1","title":"Work","explicitTitle":true,"initialTitle":"Native shell","state":"RUNNING"}]""").single()
        assertEquals("Work", sessionWithDisplayTitles(session, "vim").title)
        assertEquals("vim", sessionWithDisplayTitles(session, "vim").subtitle)
        assertEquals("build", sessionWithDisplayTitles(session, "build").subtitle)
        assertEquals("Native shell", sessionWithDisplayTitles(session, "").subtitle)
        assertEquals(null, sessionWithDisplayTitles(session.copy(hasExplicitTabTitle = false), "vim").subtitle)
    }
    @Test fun liveSessionsDoNotInterpretTheWireSentinelAsSighup() {
        for (runtime in listOf("NATIVE", "ROOTLESS", "AVF")) {
            for (state in listOf("CREATED", "RUNNING")) {
                val session = parseSessions("""[{"id":"session-1","runtime":"$runtime","state":"$state","exitCode":-1}]""").single()
                assertEquals("$runtime $state must not finish the renderer", null, session.exitCode)
            }
        }
    }

    @Test fun realExitsRetainSuccessFailureAndSighup() {
        for (code in listOf(0, 7, -1, -9)) {
            val session = parseSessions("""[{"id":"session-1","state":"EXITED","exitCode":$code}]""").single()
            assertEquals(code, session.exitCode)
        }
    }

    @Test fun missingOrNullExitStatusDoesNotInventAnExit() {
        assertEquals(null, parseSessions("""[{"id":"session-1","state":"EXITED"}]""").single().exitCode)
        assertEquals(null, parseSessions("""[{"id":"session-1","state":"EXITED","exitCode":null}]""").single().exitCode)
    }

    @Test fun sessionsRetainRuntimeStateAndExitCode() {
        val sessions = parseSessions("""[{"id":"rootless-1","runtime":"ROOTLESS","title":"apt","state":"EXITED","exitCode":7}]""")
        assertEquals(SessionUi("rootless-1", "ROOTLESS", "apt", "EXITED", 7), sessions.single())
    }

    @Test fun malformedSessionsAreEmpty() {
        assertTrue(parseSessions("not-json").isEmpty())
    }

    @Test fun closingActiveTabSelectsNextThenPreviousAndRetainsSiblingIdentity() {
        val a = session("native-a")
        val b = session("native-b")
        val c = session("native-c")
        val clients = mutableMapOf(a.id to Any(), b.id to Any(), c.id to Any())
        val cClient = clients.getValue(c.id)

        val middleClosed = reconcileSessionTabs(listOf(a, b, c), b.id, setOf(b.id), listOf(a, c))
        clients.keys.retainAll(listOf(a.id, c.id))

        assertEquals(c.id, middleClosed.activeSessionId)
        assertTrue(middleClosed.pendingTerminationIds.isEmpty())
        assertTrue(cClient === clients.getValue(c.id))
        assertEquals(b.id, reconcileSessionTabs(listOf(a, b, c), c.id, setOf(c.id), listOf(a, b)).activeSessionId)
    }

    @Test fun closingBackgroundTabDoesNotChangeActiveRendererIdentity() {
        val a = session("native-a")
        val b = session("native-b")
        val activeClient = Any()
        val clients = mutableMapOf(a.id to activeClient, b.id to Any())

        val update = reconcileSessionTabs(listOf(a, b), a.id, setOf(b.id), listOf(a))
        clients.keys.retainAll(listOf(a.id))

        assertEquals(a.id, update.activeSessionId)
        assertTrue(activeClient === clients.getValue(a.id))
    }

    @Test fun restoredSelectionWinsOverFirstSessionInInitialRuntimeSnapshot() {
        val first = session("native-first")
        val selected = session("native-selected")

        val update = reconcileSessionTabs(emptyList(), selected.id, emptySet(), listOf(first, selected))

        assertEquals(selected.id, update.activeSessionId)
    }

    @Test fun missingRestoredSelectionFallsBackToAnAvailableSession() {
        val first = session("native-first")

        val update = reconcileSessionTabs(emptyList(), "native-no-longer-running", emptySet(), listOf(first))

        assertEquals(first.id, update.activeSessionId)
    }

    @Test fun closingLastTabIsStableEmptyStateWithoutAnAutomaticCreateEffect() {
        val only = session("native-a")

        val update = reconcileSessionTabs(listOf(only), only.id, setOf(only.id), emptyList())

        assertEquals(null, update.activeSessionId)
        assertEquals(setOf(only.id), update.removedSessionIds)
        assertTrue(update.pendingTerminationIds.isEmpty())
    }

    @Test fun terminationFailureClearsOnlyTargetPendingStateAndPreservesTabs() {
        val sessions = listOf(session("native-a"), session("native-b"))
        val pending = requestSessionTermination(sessions, emptySet(), "native-a")

        val afterFailure = failSessionTermination(pending, "native-a")

        assertTrue(afterFailure.isEmpty())
        assertEquals(listOf("native-a", "native-b"), sessions.map { it.id })
    }

    @Test fun duplicateCloseRequestIsIdempotentAndUiDetachOnlyListsDetachTargets() {
        val sessions = listOf(session("native-a"), session("rootless-b"))
        val once = requestSessionTermination(sessions, emptySet(), "native-a")

        assertEquals(once, requestSessionTermination(sessions, once, "native-a"))
        assertEquals(listOf("native-a", "rootless-b"), sessionIdsForUiDetach(sessions))
    }

    @Test fun missingTitleRemainsAUiConcern() {
        assertEquals("", parseSessions("""[{"id":"native-1","runtime":"NATIVE","state":"RUNNING"}]""").single().title)
    }

    @Test fun tabTitlePrefersExplicitThenApplicationThenInitialTitle() {
        val initial = SessionUi("native-1", "NATIVE", "Native shell", "RUNNING")
        assertEquals("vim", resolvedSessionTitle(initial, "vim"))
        assertEquals("Native shell", resolvedSessionTitle(initial, " "))

        val explicit = initial.copy(title = "Build logs", hasExplicitTabTitle = true)
        assertEquals("Build logs", resolvedSessionTitle(explicit, "vim"))
    }

    @Test fun explicitTabTitleIsReadFromSessionSnapshot() {
        val session = parseSessions("""[{"id":"native-1","title":"Build logs","explicitTitle":true,"state":"RUNNING"}]""").single()
        assertTrue(session.hasExplicitTabTitle)
    }

    @Test fun runtimeAvailabilityNeverInventsAvfSupport() {
        val choices = parseRuntimeChoices("""{"NATIVE":{"available":true,"detail":"ready"},"ROOTLESS":{"available":false,"detail":"setup"}}""")
        assertTrue(choices.single { it.id == "NATIVE" }.available)
        assertFalse(choices.single { it.id == "ROOTLESS" }.available)
        assertFalse(choices.single { it.id == "AVF" }.available)
    }

    @Test fun sessionChoicesKeepInstallableDistributionsAndHideBlockedRuntimes() {
        val choices = parseRuntimeChoices("""{
            "NATIVE":{"available":true},
            "ROOTLESS":{"available":false,"installable":true},
            "ROOTLESS_UBUNTU":{"available":true},
            "ROOTLESS_ARCH":{"available":false,"installable":false},
            "AVF":{"available":false,"detail":"permission denied"}
        }""")
        assertEquals(listOf("NATIVE", "ROOTLESS", "ROOTLESS_UBUNTU"), selectableRuntimeChoices(choices).map { it.id })
        assertEquals(listOf("NATIVE", ROOTLESS_GROUP), groupedRuntimeChoices(choices).map { it.id })
        assertEquals(listOf("NATIVE"), groupedRuntimeChoices(parseRuntimeChoices("{}")).map { it.id })
    }

    @Test fun runtimeSelectionCreatesAvailableAndRoutesUnavailableSetup() {
        assertEquals(
            RuntimeSelectionAction.CREATE,
            runtimeSelectionAction(RuntimeChoiceUi("ROOTLESS", available = true, detail = "ready")),
        )
        assertEquals(
            RuntimeSelectionAction.INSTALL,
            runtimeSelectionAction(RuntimeChoiceUi("ROOTLESS", available = false, detail = "install")),
        )
        assertEquals(
            RuntimeSelectionAction.CREATE,
            runtimeSelectionAction(RuntimeChoiceUi("AVF", available = false, detail = "unsupported")),
        )
        assertEquals(
            RuntimeSelectionAction.SETTINGS,
            runtimeSelectionAction(RuntimeChoiceUi("NATIVE", available = false, detail = "unavailable")),
        )
    }

    @Test fun darkSchemeMeetsTextContrastTargets() {
        assertTextContrast(TerminalDarkColors)
        assertEquals(Color(0xFFF1F3F5), TerminalDarkColors.primary)
        assertTrue(contrast(TerminalStatusBlue, TerminalDarkColors.surface) >= 4.5f)
        assertTrue(contrast(TerminalStatusBlueContainer, TerminalOnStatusBlueContainer) >= 4.5f)
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

    @Test fun sessionTitleIsTrimmedAndBounded() {
        assertEquals("빌드 로그", validatedSessionTitle("  빌드 로그  "))
        assertEquals(null, validatedSessionTitle(" "))
        assertEquals(null, validatedSessionTitle("a".repeat(65)))
        assertEquals(null, validatedSessionTitle("line\nbreak"))
    }

    @Test fun sessionBadgeColorsDistinguishEveryWireState() {
        val colors = listOf("CREATED", "RUNNING", "EXITED", "UNKNOWN").map(::sessionStateBadgeColor)

        assertEquals(colors.size, colors.toSet().size)
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

    private fun session(id: String) = SessionUi(id, "NATIVE", id, "RUNNING")

    private fun contrast(first: Color, second: Color): Float {
        val lighter = maxOf(first.luminance(), second.luminance())
        val darker = minOf(first.luminance(), second.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
