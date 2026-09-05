package dev.terminal.app.ui

import org.json.JSONArray
import org.json.JSONObject

internal fun parseSessions(json: String): List<SessionUi> = runCatching {
    val values = JSONArray(json.ifBlank { "[]" })
    List(values.length()) { index ->
        val item = values.getJSONObject(index)
        SessionUi(
            id = item.getString("id"),
            runtime = item.optString("runtime", "NATIVE"),
            title = item.optString("title"),
            state = item.optString("state", "UNKNOWN"),
            exitCode = item.takeIf { it.has("exitCode") }?.optInt("exitCode"),
        )
    }
}.getOrDefault(emptyList())

internal fun parseRuntimeChoices(json: String): List<RuntimeChoiceUi> {
    val status = runCatching { JSONObject(json) }.getOrNull()
    return listOf("NATIVE", "ROOTLESS", "AVF").map { runtime ->
        val entry = status?.optJSONObject(runtime)
        RuntimeChoiceUi(
            id = runtime,
            available = entry?.optBoolean("available", runtime == "NATIVE") ?: (runtime == "NATIVE"),
            detail = entry?.optString("detail").orEmpty(),
        )
    }
}
