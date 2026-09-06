package dev.terminal.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeSessionAttachmentTest {
    @Test fun selectingCachedReplacementStillAttachesItsExactRuntimeSession() {
        val attachment = RuntimeSessionAttachment()
        val cachedRenderers = mapOf("native-a" to Any(), "native-b" to Any())

        assertEquals("native-a", attachment.select("native-a"))
        assertNull(attachment.select("native-a"))
        assertEquals("native-b", cachedRenderers.keys.last())
        assertEquals("native-b", attachment.select("native-b"))
        assertEquals("native-b", attachment.sessionId)
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
