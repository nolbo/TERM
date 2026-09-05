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

internal enum class RootlessSettingsStatus { CHECKING, SETUP_REQUIRED, READY, ERROR }

internal data class RootlessSettingsState(
    val status: RootlessSettingsStatus,
    val detail: String,
)

internal fun parseRootlessSettingsState(json: String): RootlessSettingsState = runCatching {
    val rootless = JSONObject(json).getJSONObject("ROOTLESS")
    val status = rootless.optString("state").takeIf { it.isNotBlank() }
        ?.let(RootlessSettingsStatus::valueOf)
        ?: if (rootless.optBoolean("available")) RootlessSettingsStatus.READY else RootlessSettingsStatus.SETUP_REQUIRED
    RootlessSettingsState(status, rootless.optString("detail"))
}.getOrElse { RootlessSettingsState(RootlessSettingsStatus.ERROR, it.message.orEmpty()) }

internal fun validatedIntegerDraft(value: String, range: IntRange): Int? =
    value.trim().toIntOrNull()?.takeIf(range::contains)

internal fun validatedShellDraft(value: String, canExecute: (String) -> Boolean): String? {
    val candidate = value.trim()
    return candidate.takeIf { it.length <= 256 && it.startsWith('/') && canExecute(it) }
}
