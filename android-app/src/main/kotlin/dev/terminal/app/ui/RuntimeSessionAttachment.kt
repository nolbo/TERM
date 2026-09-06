package dev.terminal.app.ui

/** Tracks the session attached to the runtime transport, independently of renderer caching. */
internal class RuntimeSessionAttachment {
    var sessionId: String? = null
        private set

    /** Returns the exact ID that must be attached, or null when it is already attached. */
    fun select(selectedSessionId: String): String? {
        if (sessionId == selectedSessionId) return null
        sessionId = selectedSessionId
        return selectedSessionId
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
