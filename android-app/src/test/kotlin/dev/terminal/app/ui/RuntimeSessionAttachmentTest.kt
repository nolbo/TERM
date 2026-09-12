package dev.terminal.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeSessionAttachmentTest {
    @Test fun selectingCachedReplacementDoesNotReplayItsTranscript() {
        val attachment = RuntimeSessionAttachment()
        val cachedRenderers = mapOf("native-a" to Any(), "native-b" to Any())

        assertEquals("native-a", attachment.select("native-a"))
        assertNull(attachment.select("native-a"))
        assertEquals("native-b", cachedRenderers.keys.last())
        assertNull(attachment.select("native-b", replayTranscript = false))
        assertEquals("native-b", attachment.sessionId)
    }

    @Test fun selectingUncachedSessionRequestsOneTranscriptReplay() {
        val attachment = RuntimeSessionAttachment()

        assertEquals("rootless-new", attachment.select("rootless-new", replayTranscript = true))
        assertNull(attachment.select("rootless-new", replayTranscript = true))
    }

    @Test fun closingBackgroundSessionDoesNotReattachTheActiveRuntimeSession() {
        val attachment = RuntimeSessionAttachment()
        attachment.select("native-a")

        attachment.forget("native-b")

        assertEquals("native-a", attachment.sessionId)
        assertNull(attachment.select("native-a"))
    }

    @Test fun uiDetachTargetsOnlyTheActuallyAttachedSession() {
        val attachment = RuntimeSessionAttachment()
        attachment.select("avf-b")

        assertEquals("avf-b", attachment.detach())
        assertNull(attachment.detach())
        assertNull(attachment.sessionId)
    }

    @Test fun removingLastAttachedSessionLeavesAReusableZeroSessionState() {
        val attachment = RuntimeSessionAttachment()
        attachment.select("rootless-only")

        attachment.forget("rootless-only")

        assertNull(attachment.sessionId)
        assertEquals("native-new", attachment.select("native-new"))
    }
}
