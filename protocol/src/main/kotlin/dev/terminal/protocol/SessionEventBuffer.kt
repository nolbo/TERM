package dev.terminal.protocol

/** Pending delivery, not a replay log. Slow consumers must not stop RPC reception. */
internal class SessionEventBuffer(
    // Accommodate one supported 6 MiB graphic after base64 expansion, as in
    // the guest tail, without allocating that storage for an idle connection.
    private val maxBytes: Int = 9 * 1024 * 1024,
    private val maxEvents: Int = 512,
) {
    private var events = ArrayDeque<SessionEvent>()
    private var bytes = 0
    private var closed = false

    @Synchronized fun offer(event: SessionEvent): Boolean {
        if (closed) return false
        events.addLast(event)
        if (event is SessionEvent.Output) bytes += event.bytes.size
        if (bytes > maxBytes || events.size > maxEvents) shedOutput()
        // Control-only overload cannot be repaired by silently dropping a resize
        // barrier or an exit. Bound it too; the caller reconciles the connection.
        return (bytes <= maxBytes && events.size <= maxEvents).also { if (!it) close() }
    }

    @Synchronized fun poll(): SessionEvent? = events.removeFirstOrNull()?.also {
        if (it is SessionEvent.Output) bytes -= it.bytes.size
    }

    @Synchronized fun close() {
        closed = true
        events.clear()
        bytes = 0
    }

    private fun shedOutput() {
        val retained = ArrayDeque<SessionEvent>()
        val lost = linkedMapOf<String, Long>()
        // Shed a batch rather than a packet at a time to avoid reset storms.
        // Keep the newest output, if it fits, and every control boundary.
        bytes = 0
        fun gap(id: String) { lost.remove(id)?.let { retained.addLast(SessionEvent.OutputGap(id, it)) } }
        fun lose(id: String, count: Long) {
            val before = lost[id] ?: 0L
            lost[id] = if (before < 0 || count < 0) -1L else before + count.coerceAtMost(Long.MAX_VALUE - before)
        }
        for ((index, event) in events.withIndex()) {
            when (event) {
                is SessionEvent.Output -> if (index == events.lastIndex && event.bytes.size <= maxBytes) {
                    gap(event.sessionId)
                    retained.addLast(event)
                    bytes += event.bytes.size
                } else lose(event.sessionId, event.bytes.size.toLong())
                is SessionEvent.OutputGap -> lose(event.sessionId, event.droppedBytes)
                is SessionEvent.Resized -> { gap(event.sessionId); retained.addLast(event) }
                is SessionEvent.Exited -> { gap(event.sessionId); retained.addLast(event) }
            }
        }
        lost.forEach { (id, count) -> retained.addLast(SessionEvent.OutputGap(id, count)) }
        events = retained
    }
}
