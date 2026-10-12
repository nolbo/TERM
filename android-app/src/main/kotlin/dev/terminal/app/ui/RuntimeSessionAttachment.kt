package dev.terminal.app.ui

/** Tracks the session attached to the runtime transport, independently of renderer caching. */
internal class RuntimeSessionAttachment {
    var sessionId: String? = null
        private set

    /**
     * Tracks the selected runtime session and requests a transcript replay only when the UI does
     * not already hold a live local terminal for it.
     */
    fun select(selectedSessionId: String, replayTranscript: Boolean = true): String? {
        if (sessionId == selectedSessionId) return null
        sessionId = selectedSessionId
        return selectedSessionId.takeIf { replayTranscript }
    }

    /** Returns the one attached ID to detach and clears the local binding state. */
    fun detach(): String? = sessionId.also { sessionId = null }

    fun forget(sessionId: String) {
        if (this.sessionId == sessionId) this.sessionId = null
    }

    fun reset() {
        sessionId = null
    }
}
