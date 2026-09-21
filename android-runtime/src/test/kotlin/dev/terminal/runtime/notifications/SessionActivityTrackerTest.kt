package dev.terminal.runtime.notifications

import org.junit.Assert.*
import org.junit.Test

class SessionActivityTrackerTest {
    private var now = 0L
    private fun tracker(enabled: Boolean = true) = SessionActivityTracker({ now }).apply { setEnabled(enabled) }
    private fun SessionActivityTracker.send(value: String, id: String = "native-a", replay: Boolean = false) = output(id, value.toByteArray(), replay)
    private fun osc(value: String) = "\u001b]$value\u0007"

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
