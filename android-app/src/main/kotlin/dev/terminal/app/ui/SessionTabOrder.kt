package dev.terminal.app.ui

import org.json.JSONArray

internal const val KEY_SESSION_TAB_ORDER = "session-tab-order"

internal fun parseSessionTabOrder(value: String?): List<String> = runCatching {
    val array = JSONArray(value ?: "[]")
    (0 until array.length()).map { array.getString(it) }.distinct()
}.getOrDefault(emptyList())

/** Only live runtime sessions are displayed; new sessions follow the saved tabs. */
internal fun orderSessionTabs(sessions: List<SessionUi>, order: List<String>): List<SessionUi> {
    val live = sessions.associateBy { it.id }
    val ids = order.filter { it in live }.distinct()
    return ids.map { live.getValue(it) } + sessions.filter { it.id !in ids }
}
