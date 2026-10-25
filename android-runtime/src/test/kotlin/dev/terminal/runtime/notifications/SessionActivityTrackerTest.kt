package dev.terminal.runtime.notifications

import org.junit.Assert.*
import org.junit.Test

class SessionActivityTrackerTest {
    private var now = 0L
    private fun tracker(enabled: Boolean = true) = SessionActivityTracker({ now }).apply { setEnabled(enabled) }
    private fun SessionActivityTracker.send(value: String, id: String = "native-a", replay: Boolean = false) = output(id, value.toByteArray(), replay)
    private fun osc(value: String) = "\u001b]$value\u0007"

    @Test fun idleAndLongSilentCommandWorkAreIndependentOfNotifications() {
        val tracker = tracker(false)
        tracker.running(setOf("native-a"))
        assertTrue(tracker.workActive())
        tracker.send(osc("133;A"))
        assertFalse(tracker.workActive())
        tracker.input("native-a", "sleep 7200".toByteArray())
        assertFalse(tracker.workActive())
        tracker.input("native-a", "\r".toByteArray())
        now = 7_200_000
        assertTrue(tracker.workActive())
        tracker.setEnabled(true)
        assertTrue(tracker.workActive())
        tracker.send(osc("133;D;0"))
        assertFalse(tracker.workActive())
        assertNull(tracker.nextExpiryMs())
    }

    @Test fun fragmentedBoundariesAndSiblingExitPreserveOtherCommands() {
        val tracker = tracker(false)
        tracker.running(setOf("native-a", "rootless-b"))
        tracker.send(osc("133;A"))
        tracker.send("\u001b]133;", "rootless-b")
        tracker.send("C\u0007", "rootless-b")
        tracker.forget("native-a")
        now = 10_000_000
        assertTrue(tracker.workActive())
        tracker.running(setOf("rootless-b"))
        assertTrue(tracker.workActive())
        tracker.exited("rootless-b", 0)
        assertFalse(tracker.workActive())
    }

    @Test fun unknownShellLeaseExpiresAndReplayOrSnapshotNeverRenewsIt() {
        val tracker = tracker(false)
        tracker.running(setOf("native-a"))
        now = SessionActivityTracker.UNKNOWN_WORK_LEASE_MS - 1
        tracker.running(setOf("native-a"))
        tracker.send("old output", replay = true)
        assertEquals(1L, tracker.nextExpiryMs())
        now++
        assertFalse(tracker.workActive())
        assertNull(tracker.nextExpiryMs())
        tracker.input("native-a", "sleep 30\r".toByteArray())
        assertTrue(tracker.workActive())
        now += SessionActivityTracker.UNKNOWN_WORK_LEASE_MS
        assertFalse(tracker.workActive())
        tracker.send("live output")
        assertTrue(tracker.workActive())
        tracker.running(emptySet())
        assertFalse(tracker.workActive())
    }

    @Test fun pasteAtPromptNeverBecomesActiveWork() {
        val tracker = tracker(false)
        tracker.send(osc("133;A"))
        tracker.input("native-a", "\u001b[200~sleep 30\n\u001b[201~".toByteArray())
        assertFalse(tracker.workActive())
    }

    @Test fun onlyLongCommandsCompleteAndReplayedHistoryNeverNotifies() {
        val tracker = tracker()
        tracker.send(osc("133;C")); now = 9999
        assertTrue(tracker.send(osc("133;D;0")).isEmpty())
        tracker.send(osc("133;C")); now += 10000
        assertEquals(SessionActivityTracker.Alert("native-a", SessionActivityTracker.Kind.COMPLETED, 7), tracker.send(osc("133;D;7")).single())
        now += 20000
        assertTrue(tracker.send(osc("133;C") + osc("133;D;0") + "\u0007", replay = true).isEmpty())
        assertTrue(tracker.send(osc("133;D;0")).isEmpty())
    }

    @Test fun nativePromptSubmissionIgnoresTypingAndBracketedPasteUntilEnter() {
        val tracker = tracker()
        tracker.send(osc("133;A"))
        tracker.input("native-a", "\u001b[20".toByteArray())
        tracker.input("native-a", "0~sleep 30\n\u001b[201~".toByteArray())
        now = 30000
        assertTrue(tracker.send(osc("133;A")).isEmpty())
        tracker.input("native-a", "\r".toByteArray())
        now += 10000
        assertEquals(SessionActivityTracker.Kind.COMPLETED, tracker.send(osc("133;D;0") + osc("133;A")).single().kind)
    }

    @Test fun focusedCompletionUpdatesWorkWithoutConsumingNotificationLimits() {
        val tracker = tracker()
        tracker.setFocusedSessions(setOf("native-a"))
        tracker.send(osc("133;C"))
        tracker.send(osc("133;C"), "native-b")
        now = 10000
        assertTrue(tracker.send(osc("133;D;1") + osc("133;A")).isEmpty())
        assertTrue(tracker.workActive())
        assertEquals("native-b", tracker.send(osc("133;D;0"), "native-b").single().sessionId)
        assertFalse(tracker.workActive())
        now += 1000
        assertEquals("Suppressed completion must not consume the session limit",
            SessionActivityTracker.Kind.ATTENTION, tracker.send("\u0007").single().kind)
        tracker.setFocusedSessions(emptySet())
        now += 10000
        assertTrue("Returning to a background prompt must not replay completion", tracker.send(osc("133;A")).isEmpty())
    }

    @Test fun completionUsesFocusAtFinishRatherThanAtCommandStart() {
        val tracker = tracker()
        tracker.setFocusedSessions(setOf("native-a"))
        tracker.send(osc("133;C"))
        tracker.setFocusedSessions(emptySet())
        now = 10000
        assertEquals(SessionActivityTracker.Kind.COMPLETED, tracker.send(osc("133;D;0")).single().kind)
        tracker.send(osc("133;C"))
        tracker.setFocusedSessions(setOf("native-a"))
        now += 10000
        assertTrue(tracker.send(osc("133;A")).isEmpty())
        tracker.setFocusedSessions(setOf("native-b"))
        tracker.send(osc("133;C"))
        now += 10000
        assertEquals(SessionActivityTracker.Kind.COMPLETED, tracker.send(osc("133;D;0")).single().kind)
    }

    @Test fun focusedSessionExitDoesNotNotifyOrLimitOtherSessionExits() {
        val tracker = tracker()
        tracker.setFocusedSessions(setOf("native-a"))
        tracker.send(osc("133;C"))
        tracker.send(osc("133;C"), "native-b")
        now = 10000
        assertTrue(tracker.exited("native-a", 0).isEmpty())
        assertEquals("native-b", tracker.exited("native-b", 7).single().sessionId)
        assertFalse(tracker.workActive())
    }

    @Test fun explicitAttentionStillNotifiesForFocusedSessions() {
        val tracker = tracker()
        tracker.setFocusedSessions(setOf("native-a"))
        assertEquals(SessionActivityTracker.Kind.ATTENTION, tracker.send("\u0007").single().kind)
        now += 10000
        assertEquals(SessionActivityTracker.Kind.ATTENTION, tracker.send(osc("777;notify;title;body")).single().kind)
    }

    @Test fun fragmentedFramesAndUtf8DoNotInventBellOrControlRequests() {
        val tracker = tracker()
        assertTrue(tracker.send("\u001b]77").isEmpty())
        assertTrue(tracker.send("7;notify;Title;Text\u001b").isEmpty())
        assertEquals(SessionActivityTracker.Kind.ATTENTION, tracker.send("\\").single().kind)
        now += 11000
        assertTrue(tracker.send("\u001b]0;title\u0007\u001bPignored\u0007\u001b\\\u001b_Gignored\u0007\u001b\\").isEmpty())
        assertTrue(tracker.send("Ýhello\u001b]9;4;1;50\u0007").isEmpty())
        assertEquals(1, tracker.send("\u0007").size)
    }

    @Test fun rateLimitAndDisableAreIndependentOfForegroundServiceLifetime() {
        val tracker = tracker(false)
        assertTrue(tracker.send("\u0007").isEmpty())
        tracker.setEnabled(true)
        assertEquals(1, tracker.send("\u0007".repeat(10000)).size)
        assertTrue(tracker.send("\u0007", "native-b").isEmpty())
        now = 1000
        assertEquals(1, tracker.send(osc("9;attention"), "native-b").size)
        now = 2000
        assertTrue(tracker.send("\u0007").isEmpty())
        tracker.setEnabled(false)
        now = 20000
        assertTrue(tracker.send("\u0007").isEmpty())
    }

    @Test fun concurrentCompletionsAreQueuedWithoutSharingTheSystemAttentionLimiter() {
        val tracker = tracker()
        listOf("native-a", "native-b", "native-c").forEach { tracker.send(osc("133;C"), it) }
        now = 10_000
        assertEquals(SessionActivityTracker.Kind.ATTENTION, tracker.send("\u0007").single().kind)
        assertEquals("native-a", tracker.send(osc("133;D;1")).single().sessionId)
        assertEquals("native-b", tracker.send(osc("133;D;0"), "native-b").single().sessionId)
        assertEquals("native-c", tracker.exited("native-c", 7).single().sessionId)
        assertTrue(tracker.send(osc("133;A"), "native-b").isEmpty())
        assertTrue(tracker.send("\u0007", "native-b").isEmpty())
    }

    @Test fun oversizedOrCanceledStringsCannotBecomeNotifications() {
        val tracker = tracker()
        assertTrue(tracker.send(osc("777;notify;" + "x".repeat(2000))).isEmpty())
        assertTrue(tracker.send("\u001b]777;notify;\u0018").isEmpty())
        assertEquals(1, tracker.send(osc("777;notify;ok")).size)
    }

    @Test fun exitingOrExplicitlyTerminatingCannotLeaveOldTimersForOtherSessions() {
        val tracker = tracker()
        tracker.send(osc("133;C")); now = 10000
        assertEquals(1, tracker.exited("native-a", 0).size)
        assertTrue(tracker.exited("native-a", 0).isEmpty())
        now += 10000
        tracker.send(osc("133;C")); now += 20000
        tracker.forget("native-a")
        assertTrue(tracker.exited("native-a", 9).isEmpty())
        tracker.send(osc("133;C"), "native-b"); now += 20000
        assertEquals("native-b", tracker.exited("native-b", 3).single().sessionId)
    }

    @Test fun enablingMidCommandDoesNotSendStaleCompletion() {
        val tracker = tracker(false)
        tracker.send(osc("133;C")); now = 10000
        tracker.setEnabled(true)
        assertTrue(tracker.send(osc("133;D;0")).isEmpty())
    }
}
