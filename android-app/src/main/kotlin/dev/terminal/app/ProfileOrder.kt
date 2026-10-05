package dev.terminal.app

import org.json.JSONArray

internal const val PROFILE_ORDER_PREFERENCES = "session-profile-order"
internal const val KEY_PROFILE_ORDER = "ids"

internal fun parseProfileOrder(raw: String?): List<String> = runCatching {
    val array = JSONArray(raw ?: "[]")
    (0 until array.length()).mapNotNull { (array.opt(it) as? String)?.takeIf(String::isNotBlank) }.distinct()
}.getOrDefault(emptyList())

/** Missing/deleted IDs are ignored; profiles created since the last reorder go at the end. */
internal fun resolveProfileOrder(available: List<String>, saved: List<String>): List<String> {
    val availableIds = available.toSet()
    return (saved.filter { it in availableIds } + available).distinct()
}

/** Retain the slots of omitted profiles, including privilege-hidden AVF defaults. */
internal fun reorderProfileIds(current: List<String>, requested: List<String>): List<String> {
    val moving = requested.distinct().filter { it in current }.toSet()
    val replacements = moving.iterator()
    return current.map { if (it in moving) replacements.next() else it }
}

/** Compact the other rows to keep insertion thresholds stable while the preview moves. */
internal fun profileDropTarget(otherHeights: List<Float>, top: Float, spacing: Float): Int {
    var offset = 0f
    otherHeights.forEachIndexed { index, height ->
        if (top < offset + height / 2) return index
        offset += height + spacing
    }
    return otherHeights.size
}
