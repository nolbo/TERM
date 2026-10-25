package dev.terminal.runtime.notifications

/** Runtime-owned, bounded unread completions. UI recreation never consumes a message. */
internal class SessionTaskCompletionQueue {
    data class Completion(val id: Long, val sessionId: String, val title: String, val exitCode: Int?)
    private val pending = ArrayDeque<Completion>()
    private var nextId = 0L

    @Synchronized fun add(sessionId: String, title: String, exitCode: Int?) {
        val name = title.filter { !it.isISOControl() && it !in '\u202a'..'\u202e' && it !in '\u2066'..'\u2069' }.take(80)
        pending.addLast(Completion(++nextId, sessionId, name, exitCode))
        while (pending.size > 16) pending.removeFirst()
    }

    @Synchronized fun snapshot(): List<Completion> = pending.toList()
    @Synchronized fun acknowledge(id: Long): Boolean = pending.removeAll { it.id == id }
    @Synchronized fun clear() = pending.clear()
}
