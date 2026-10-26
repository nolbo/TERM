package dev.terminal.runtime.notifications

/** Consumes live semantic events from the authoritative terminal parser, never parses PTY output. */
internal class SessionActivityTracker(
    private val clock: () -> Long,
    private val longCommandMs: Long = 10_000,
) {
    enum class Work { UNKNOWN, IDLE, ACTIVE }
    enum class Kind { COMPLETED, ATTENTION }
    data class Alert(val sessionId: String, val kind: Kind, val exitCode: Int? = null)
    private class Session {
        var started: Long? = null
        var work = Work.UNKNOWN
        var observedAt = 0L
        var atPrompt = false
        var lastAlert: Long? = null
        var inputEscape = ""
        var paste = false
    }
    private val sessions = mutableMapOf<String, Session>()
    private var enabled = false
    private var lastAlert: Long? = null
    private var focusedSessions = emptySet<String>()

    @Synchronized fun setFocusedSessions(ids: Set<String>) { focusedSessions = ids.toSet() }

    /** Unknown shells retain the old protection only for a bounded inactivity lease. */
    @Synchronized fun running(ids: Set<String>) {
        sessions.keys.retainAll(ids)
        ids.forEach { id -> sessions.getOrPut(id) { Session().apply { observedAt = clock() } } }
    }

    @Synchronized fun workActive(): Boolean = sessions.values.any {
        it.work == Work.ACTIVE || it.work == Work.UNKNOWN && clock() - it.observedAt < UNKNOWN_WORK_LEASE_MS
    }

    @Synchronized fun nextExpiryMs(): Long? = sessions.values.filter { it.work == Work.UNKNOWN }
        .map { UNKNOWN_WORK_LEASE_MS - (clock() - it.observedAt) }.filter { it > 0 }.minOrNull()

    companion object { const val UNKNOWN_WORK_LEASE_MS = 10 * 60 * 1000L }

    @Synchronized fun setEnabled(value: Boolean) {
        if (value == enabled) return
        enabled = value
        sessions.values.forEach { it.started = null; it.lastAlert = null }
        lastAlert = null
    }

    @Synchronized fun input(id: String, bytes: ByteArray) {
        val session = sessions.getOrPut(id, ::Session)
        session.observedAt = clock()
        // Pasted newlines inside bracketed paste are editing, not command submission.
        for (byte in bytes) {
            val char = (byte.toInt() and 255).toChar()
            if (session.inputEscape.isNotEmpty()) {
                session.inputEscape += char
                if (session.inputEscape == "\u001b[200~") session.paste = true
                if (session.inputEscape == "\u001b[201~") session.paste = false
                if (!"\u001b[200~".startsWith(session.inputEscape) && !"\u001b[201~".startsWith(session.inputEscape) || session.inputEscape.length >= 6) session.inputEscape = ""
            } else if (char == '\u001b') session.inputEscape = "\u001b"
            else if (!session.paste && char in "\r\n" && session.atPrompt) {
                session.started = clock()
                session.work = Work.ACTIVE
                session.atPrompt = false
            }
        }
    }

    data class Activity(val alerts: List<Alert>, val wakeChanged: Boolean)

    @Synchronized fun activity(id: String, events: List<String>): Activity {
        val previous = sessions[id]
        val now = clock()
        val wasActive = previous?.let { it.work == Work.ACTIVE ||
            it.work == Work.UNKNOWN && now - it.observedAt < UNKNOWN_WORK_LEASE_MS } ?: false
        val session = sessions.getOrPut(id, ::Session)
        val oldWork = session.work
        session.observedAt = now
        if (events.isEmpty()) return Activity(emptyList(), previous == null || wasActive != (session.work != Work.IDLE))
        val alerts = mutableListOf<Alert>()
        fun alert(kind: Kind, code: Int? = null) {
            val now = clock()
            if (!enabled || kind == Kind.COMPLETED && id in focusedSessions) return
            if (kind == Kind.ATTENTION) {
                if (session.lastAlert?.let { now - it < 10_000 } == true || lastAlert?.let { now - it < 1_000 } == true) return
                session.lastAlert = now
                lastAlert = now
            }
            alerts += Alert(id, kind, code)
        }
        fun complete(code: Int?) {
            val start = session.started
            session.started = null
            session.work = Work.IDLE
            if (start != null && clock() - start >= longCommandMs) alert(Kind.COMPLETED, code)
        }
        for (value in events) {
            when {
                value == "133;C" || value.startsWith("133;C;") -> {
                    session.started = clock(); session.atPrompt = false; session.work = Work.ACTIVE
                }
                value == "133;D" || value.startsWith("133;D;") -> complete(value.substringAfter("133;D;", "").substringBefore(';').toIntOrNull())
                value == "133;A" || value.startsWith("133;A;") -> { complete(null); session.atPrompt = true }
                value.isEmpty() || value.startsWith("9;") && !value.startsWith("9;4;") ||
                    value.startsWith("777;notify;") -> alert(Kind.ATTENTION)
            }
        }
        // Extending an already-live unknown-shell lease needs no scan or timer reset:
        // the existing expiry job will observe its new deadline when it wakes.
        val active = session.work != Work.IDLE
        return Activity(alerts, previous == null || oldWork != session.work || wasActive != active)
    }

    @Synchronized fun exited(id: String, code: Int): List<Alert> {
        val session = sessions.remove(id) ?: return emptyList()
        val start = session.started ?: return emptyList()
        val now = clock()
        if (!enabled || id in focusedSessions || now - start < longCommandMs) return emptyList()
        return listOf(Alert(id, Kind.COMPLETED, code))
    }
    @Synchronized fun forget(id: String) { sessions.remove(id) }
}
