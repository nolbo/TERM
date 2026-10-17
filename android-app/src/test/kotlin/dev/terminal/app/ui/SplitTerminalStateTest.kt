package dev.terminal.app.ui

import dev.terminal.runtime.workspace.Workspace
import dev.terminal.runtime.workspace.WorkspaceRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SplitTerminalStateTest {
    private val a = SessionUi("a", "NATIVE", "a", "RUNNING")
    private val b = SessionUi("b", "ROOTLESS", "b", "RUNNING")
    private val c = SessionUi("c", "AVF", "c", "RUNNING")
    private val sessions = listOf(a, b, c)
    private val split = TerminalPaneState("a", "b", 1, SplitDirection.SideBySide, 0.35f)

    @Test fun splitAllowsDifferentEnvironmentsOnlyInTheSameWorkspaceIncludingUnassigned() {
        val single = TerminalPaneState("a")
        assertTrue(single.canSplitWith("b", sessions, emptyMap()))
        assertTrue(single.canSplitWith("c", sessions, mapOf("a" to "w", "c" to "w")))
        assertFalse(single.canSplitWith("b", sessions, mapOf("a" to "w")))
        assertFalse(single.canSplitWith("b", sessions, mapOf("a" to "w", "b" to "other")))
        assertFalse(single.canSplitWith("a", sessions, emptyMap()))
        assertFalse(single.canSplitWith("missing", sessions, emptyMap()))
    }

    @Test fun preparingExitedAndDuplicateSessionsCannotBeSplit() {
        val single = TerminalPaneState("a")
        assertFalse(single.canSplitWith("b", listOf(a, b.copy(pendingLaunch = true)), emptyMap()))
        assertFalse(single.canSplitWith("b", listOf(a, b.copy(state = "EXITED", exitCode = 0)), emptyMap()))
        assertFalse(TerminalPaneState("b").canSplitWith("a", listOf(a, b.copy(state = "EXITED")), emptyMap()))
        assertFalse(split.canSplitWith("a", sessions, emptyMap()))
        assertFalse(split.canSplitWith("b", sessions, emptyMap()))
    }

    @Test fun droppingNamesBothSessionsWithoutDependingOnTheSelectedTabOrItsMenu() {
        val single = TerminalPaneState("a")
        assertFalse(single.canSplitWith("a", sessions, emptyMap()))
        assertEquals(TerminalPaneState("b", "a", 1),
            single.pair("a", "b", sessions, emptyMap(), SplitDirection.Stacked))
        assertEquals(TerminalPaneState("c", "b", 1),
            single.pair("b", "c", sessions, emptyMap(), SplitDirection.Stacked))
        assertEquals(split.select("a"), split.pair("a", "b", sessions, emptyMap(), SplitDirection.Stacked, sourceFirst = true))
        assertEquals(split.copy(first = "c"), split.pair("b", "c", sessions, emptyMap(), SplitDirection.Stacked))
    }

    @Test fun droppingStillRejectsDuplicatesDeadSessionsAndDifferentWorkspaces() {
        val single = TerminalPaneState("a")
        assertEquals(single, single.pair("a", "a", sessions, emptyMap(), SplitDirection.Stacked))
        assertEquals(single, single.pair("b", "c", sessions, mapOf("b" to "one", "c" to "two"), SplitDirection.Stacked))
        assertEquals(single, single.pair("b", "c", listOf(b.copy(state = "EXITED"), c), emptyMap(), SplitDirection.Stacked))
        assertEquals(single, single.pair("b", "c", listOf(b.copy(pendingLaunch = true), c), emptyMap(), SplitDirection.Stacked))
        assertEquals(single, single.pair("b", "missing", sessions, emptyMap(), SplitDirection.Stacked))
    }

    @Test fun dropOrderSwapsPanePositionsButKeepsFocusOnTheDraggedSession() {
        assertEquals(TerminalPaneState("a", "b", 0),
            TerminalPaneState("a").pair("a", "b", sessions, emptyMap(), SplitDirection.Stacked, true))
        assertEquals(TerminalPaneState("b", "a", 1),
            TerminalPaneState("a").pair("a", "b", sessions, emptyMap(), SplitDirection.Stacked, false))
        assertEquals(split.copy(first = "b", second = "a", focus = 0),
            split.pair("b", "a", sessions, emptyMap(), SplitDirection.Stacked, true))
        assertEquals(split.copy(first = "b", second = "a", focus = 1),
            split.pair("a", "b", sessions, emptyMap(), SplitDirection.Stacked, false))
    }

    @Test fun anotherTabOpensAloneAndSelectingEitherSplitTabRestoresThePair() {
        assertEquals(split.copy(focus = 0), split.select("a"))
        assertEquals(split, split.select("b"))
        val solo = split.select("c")
        assertFalse(solo.split)
        assertEquals(listOf("c"), solo.sessionIds)
        assertEquals(listOf("a", "b"), solo.splitSessionIds)
        assertEquals("c", solo.focusedSessionId)
        assertEquals(0, solo.focusedPaneIndex)
        assertEquals(split.copy(focus = 0), solo.select("a"))
        assertEquals(split, solo.select("b"))
        assertEquals(solo, solo.select("c"))
    }

    @Test fun unsplittingOrChangingAxisWhileAnotherTabIsSelectedKeepsThatTabAlone() {
        val solo = split.select("c")
        val changed = solo.copy(direction = SplitDirection.Stacked)
        assertEquals(listOf("c"), changed.sessionIds)
        assertEquals(SplitDirection.Stacked, changed.select("a").direction)
        val cleared = solo.unsplit()
        assertEquals(TerminalPaneState("c", direction = split.direction, fraction = split.fraction), cleared)
        assertTrue(cleared.splitSessionIds.isEmpty())
        assertEquals(listOf("a"), cleared.select("a").sessionIds)
    }

    @Test fun explicitSplitFromAnIndependentTabUsesThatTabAndReplacesTheSavedPair() {
        val opened = split.select("c").openSplit("a", sessions, emptyMap(), SplitDirection.Stacked)
        assertEquals(TerminalPaneState("c", "a", 1), opened)
        assertEquals(listOf("c", "a"), opened.splitSessionIds)
        assertNull(opened.soloSessionId)
    }

    @Test fun splitIsBoundedToTwoPanesAndUnsplitKeepsFocusedSession() {
        val opened = TerminalPaneState("a").openSplit("b", sessions, emptyMap(), SplitDirection.Stacked)
        assertEquals(TerminalPaneState("a", "b", 1), opened)
        val replaced = split.openSplit("c", sessions, emptyMap(), SplitDirection.Stacked)
        assertEquals(listOf("c", "b"), replaced.sessionIds)
        assertEquals(SplitDirection.SideBySide, replaced.direction)
        assertEquals(listOf("b"), split.unsplit().sessionIds)
        assertEquals(0, split.unsplit().focus)
        assertEquals(split.fraction, split.unsplit().fraction)
    }

    @Test fun authoritativeExitOrMembershipChangeCollapsesToTheSurvivingPane() {
        assertEquals("a", split.reconcile(listOf(a, b.copy(state = "EXITED", exitCode = 0)), emptyMap()).first)
        assertFalse(split.reconcile(listOf(a, b.copy(state = "EXITED")), emptyMap()).split)
        assertEquals("b", split.reconcile(listOf(b), emptyMap()).first)
        assertEquals("b", split.reconcile(sessions, mapOf("a" to "one", "b" to "two")).first)
        assertEquals("c", split.reconcile(listOf(c), emptyMap()).first)
        assertNull(split.reconcile(emptyList(), emptyMap()).first)
    }

    @Test fun workspaceLayoutsRoundTripFocusAxisRatioAndPruneDeadOrMovedSessions() {
        val w = Workspace(UUID.randomUUID().toString(), "Test", false)
        val state = WorkspaceRegistry.State(listOf(w), mapOf("a" to w.id, "b" to w.id), w.id)
        val layouts = mapOf(w.id to split, WorkspaceRegistry.UNASSIGNED to TerminalPaneState("c"))
        val restored = parseTerminalLayouts(encodeTerminalLayouts(layouts))
        assertEquals(layouts, restored)
        assertEquals(layouts, reconcileWorkspaceLayouts(restored, sessions, state))
        val moved = reconcileWorkspaceLayouts(restored, sessions, state.copy(memberships = mapOf("a" to w.id)))
        assertEquals(TerminalPaneState("a", direction = split.direction, fraction = split.fraction), moved[w.id])
        val lost = reconcileWorkspaceLayouts(restored, listOf(c), state)
        assertNull(lost[w.id]?.first)
        val deleted = reconcileWorkspaceLayouts(restored, sessions, WorkspaceRegistry.State())
        assertEquals(setOf(WorkspaceRegistry.UNASSIGNED), deleted.keys)
    }

    @Test fun independentSelectionAndItsRetainedPairRoundTripAndReconcileAuthoritativeChanges() {
        val solo = split.select("c")
        val layouts = mapOf(WorkspaceRegistry.UNASSIGNED to solo)
        assertEquals(layouts, parseTerminalLayouts(encodeTerminalLayouts(layouts)))
        assertEquals(solo, solo.reconcile(sessions, emptyMap()))
        assertEquals(split, solo.reconcile(listOf(a, b), emptyMap()))
        assertEquals(solo.unsplit(), solo.reconcile(listOf(a, c), emptyMap()))
        assertEquals(solo.unsplit(), solo.reconcile(listOf(a, b.copy(state = "EXITED", exitCode = 0), c), emptyMap()))
        assertEquals(solo.unsplit(), solo.reconcile(sessions, mapOf("b" to "other")))
        assertEquals("new-c", solo.remap(mapOf("c" to "new-c")).focusedSessionId)
        assertEquals(listOf("new-a", "b"), solo.remap(mapOf("a" to "new-a")).splitSessionIds)
        val pending = c.copy(pendingLaunch = true)
        val workspace = WorkspaceRegistry.State()
        assertEquals(layouts, reconcileWorkspaceLayouts(layouts, listOf(a, b, pending), workspace))
        assertEquals(solo, solo.reconcile(listOf(a, b, c.copy(state = "EXITED", exitCode = 0)), emptyMap()))
        assertEquals(split, reconcileWorkspaceLayouts(layouts,
            listOf(a, b, c.copy(state = "EXITED", exitCode = 0)), workspace).getValue(WorkspaceRegistry.UNASSIGNED))
    }

    @Test fun workspaceRestoreDiscardsMovedIndependentTabsAndPreservesLiveIndependentTabsWhenThePairMoves() {
        val w = Workspace(UUID.randomUUID().toString(), "Test", false)
        val other = Workspace(UUID.randomUUID().toString(), "Other", false)
        val workspace = WorkspaceRegistry.State(listOf(w, other),
            mapOf("a" to w.id, "b" to w.id, "c" to w.id), w.id)
        val solo = split.select("c")
        val layouts = mapOf(w.id to solo)
        assertEquals(split, reconcileWorkspaceLayouts(layouts, sessions,
            workspace.copy(memberships = workspace.memberships + ("c" to other.id))).getValue(w.id))
        assertEquals(solo.unsplit(), reconcileWorkspaceLayouts(layouts, sessions,
            workspace.copy(memberships = workspace.memberships + ("b" to other.id))).getValue(w.id))
    }

    @Test fun restoreRejectsCorruptLayoutsAndNeverCreatesSessions() {
        assertTrue(parseTerminalLayouts("not json").isEmpty())
        val invalid = "{\"unassigned\":{\"first\":\"a\",\"second\":\"a\",\"focus\":1,\"direction\":\"Stacked\",\"fraction\":0.5}}"
        assertTrue(parseTerminalLayouts(invalid).isEmpty())
        assertNull(parseTerminalLayouts(encodeTerminalLayouts(mapOf("unassigned" to split)))
            .getValue("unassigned").reconcile(emptyList(), emptyMap()).first)
    }

    @Test fun exitedScreensAreNotRestoredAsLiveSessionsButExplicitSingleTabSelectionStillWorks() {
        val exited = a.copy(state = "EXITED", exitCode = 0)
        val layout = TerminalPaneState("a")
        assertEquals("a", layout.reconcile(listOf(exited), emptyMap()).first)
        val restored = reconcileWorkspaceLayouts(mapOf("unassigned" to layout), listOf(exited, b), WorkspaceRegistry.State())
        assertEquals("b", restored.getValue("unassigned").first)
        assertNull(TerminalPaneState().reconcile(listOf(exited), emptyMap(), "a").first)
    }

    @Test fun defaultAxisUsesDisplayOrientationAndNarrowImeGeometryRemainsBounded() {
        assertEquals(SplitDirection.Stacked, SplitDirection.default(false))
        assertEquals(SplitDirection.SideBySide, SplitDirection.default(true))
        assertEquals(144, splitPaneExtent(300, 12, 0.5f, 96))
        assertEquals(96, splitPaneExtent(300, 12, 0.2f, 96))
        assertEquals(44, splitPaneExtent(100, 12, 0.8f, 96))
        assertEquals(0, splitPaneExtent(0, 12, 0.5f, 96))
    }
}
