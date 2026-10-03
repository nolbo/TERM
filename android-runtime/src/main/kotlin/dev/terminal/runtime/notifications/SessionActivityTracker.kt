package dev.terminal.runtime.notifications

/** Observes live PTY bytes only. No timers, polling, shell ownership, or output-text guessing. */
internal class SessionActivityTracker(
    private val clock: () -> Long,
    private val longCommandMs: Long = 10_000,
) {
    enum class Work { UNKNOWN, IDLE, ACTIVE }
    enum class Kind { COMPLETED, ATTENTION }
    data class Alert(val sessionId: String, val kind: Kind, val exitCode: Int? = null)
    private class Session {
        var state = 0 // ground, ESC, OSC, OSC-ESC, ignored string, string-ESC, CSI
        var utf8 = 0
        val osc = StringBuilder()
        var overflow = false
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

    @Synchronized fun output(id: String, bytes: ByteArray, replay: Boolean): List<Alert> {
        if (replay) return emptyList()
        val session = sessions.getOrPut(id, ::Session)
        session.observedAt = clock()
        val alerts = mutableListOf<Alert>()
        fun alert(kind: Kind, code: Int? = null) {
            val now = clock()
            if (!enabled || session.lastAlert?.let { now - it < 10_000 } == true || lastAlert?.let { now - it < 1_000 } == true) return
            session.lastAlert = now
            lastAlert = now
            alerts += Alert(id, kind, code)
        }
        fun complete(code: Int?) {
            val start = session.started
            session.started = null
            session.work = Work.IDLE
            if (start != null && clock() - start >= longCommandMs) alert(Kind.COMPLETED, code)
        }
        fun osc() {
            if (session.overflow) return
            val value = session.osc.toString()
            when {
                value == "133;C" || value.startsWith("133;C;") -> {
                    session.started = clock(); session.atPrompt = false; session.work = Work.ACTIVE
                }
                value == "133;D" || value.startsWith("133;D;") -> complete(value.substringAfter("133;D;", "").substringBefore(';').toIntOrNull())
                value == "133;A" || value.startsWith("133;A;") -> { complete(null); session.atPrompt = true }
                value.startsWith("9;") && !value.startsWith("9;4;") -> alert(Kind.ATTENTION)
                value.startsWith("777;notify;") -> alert(Kind.ATTENTION)
            }
        }
        for (raw in bytes) {
            val b = raw.toInt() and 255
            val continuation = session.utf8 > 0 && b in 0x80..0xbf
            session.utf8 = if (continuation) session.utf8 - 1 else when (b) {
                in 0xc2..0xdf -> 1; in 0xe0..0xef -> 2; in 0xf0..0xf4 -> 3; else -> 0
            }
            if (!continuation && (b == 0x18 || b == 0x1a)) { session.state = 0; continue }
            when (session.state) {
                0 -> if (!continuation) when (b) {
                    7 -> alert(Kind.ATTENTION)
                    27 -> session.state = 1
                    0x9d -> { session.state = 2; session.osc.setLength(0); session.overflow = false }
                    0x90, 0x98, 0x9e, 0x9f -> session.state = 4
                    0x9b -> session.state = 6
                }
                1 -> when (b) {
                    93 -> { session.state = 2; session.osc.setLength(0); session.overflow = false }
                    80, 88, 94, 95 -> session.state = 4
                    91 -> session.state = 6
                    27 -> Unit
                    else -> session.state = 0
                }
                2 -> when {
                    b == 7 || (!continuation && b == 0x9c) -> { osc(); session.state = 0 }
                    b == 27 -> session.state = 3
                    session.osc.length < 1024 -> session.osc.append(b.toChar())
                    else -> session.overflow = true
                }
                3 -> {
                    if (b == 92) osc()
                    // Malformed OSC is discarded, never reinterpreted as a bell/request.
                    session.state = if (b == 92) 0 else 4
                }
                4 -> if (b == 27) session.state = 5 else if (!continuation && b == 0x9c) session.state = 0
                5 -> session.state = if (b == 92) 0 else if (b == 27) 5 else 4
                6 -> if (b in 0x40..0x7e) session.state = 0 else if (b == 27) session.state = 1
            }
        }
        return alerts
    }

    @Synchronized fun exited(id: String, code: Int): List<Alert> {
        val session = sessions.remove(id) ?: return emptyList()
        val start = session.started ?: return emptyList()
        val now = clock()
        if (!enabled || now - start < longCommandMs || session.lastAlert?.let { now - it < 10_000 } == true ||
            lastAlert?.let { now - it < 1_000 } == true) return emptyList()
        lastAlert = now
        return listOf(Alert(id, Kind.COMPLETED, code))
    }
    @Synchronized fun forget(id: String) { sessions.remove(id) }
}
