package dev.terminal.app

import android.content.SharedPreferences
import org.json.JSONObject

internal const val KEY_MEMORY_DEVICE = "toolbar-memory-device"
internal const val KEY_MEMORY_APP = "toolbar-memory-app"
internal const val KEY_MEMORY_SESSIONS = "toolbar-memory-sessions"
internal data class MemoryToolSections(val device: Boolean = true, val app: Boolean = true, val sessions: Boolean = true) {
    val any get() = device || app || sessions
}
internal fun readMemoryToolSections(preferences: SharedPreferences) = MemoryToolSections(
    preferences.getBoolean(KEY_MEMORY_DEVICE, true), preferences.getBoolean(KEY_MEMORY_APP, true),
    preferences.getBoolean(KEY_MEMORY_SESSIONS, true))

/** Missing process measurements must not silently produce a partial app total. */
internal fun combineAppMemory(ui: JSONObject?, runtime: JSONObject?): JSONObject? {
    if (ui == null || runtime == null) return null
    return JSONObject().apply {
        for (key in listOf("total", "java-heap", "native-heap", "code", "stack", "graphics", "private-other", "system")) {
            val a = ui.optLong(key, -1)
            val b = runtime.optLong(key, -1)
            put(key, if (a >= 0 && b >= 0) a + b else JSONObject.NULL)
        }
    }
}
