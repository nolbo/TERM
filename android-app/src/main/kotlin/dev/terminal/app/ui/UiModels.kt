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
            hasExplicitTabTitle = item.optBoolean("explicitTitle", false),
            initialTitle = item.optString("initialTitle", item.optString("title")),
            // The wire default -1 means "not exited" for live sessions, but
            // means SIGHUP for EXITED sessions. State disambiguates the value.
            exitCode = item.takeIf { it.optString("state") == "EXITED" && !it.isNull("exitCode") }
                ?.getInt("exitCode"),
        )
    }
}.getOrDefault(emptyList())

internal data class SessionTabUpdate(
    val activeSessionId: String?,
    val pendingTerminationIds: Set<String>,
    val removedSessionIds: Set<String>,
)

internal fun requestSessionTermination(
    sessions: List<SessionUi>,
    pendingTerminationIds: Set<String>,
    sessionId: String,
): Set<String> = if (sessions.any { it.id == sessionId }) pendingTerminationIds + sessionId else pendingTerminationIds

internal fun failSessionTermination(
    pendingTerminationIds: Set<String>,
    sessionId: String,
): Set<String> = pendingTerminationIds - sessionId

internal fun sessionIdsForUiDetach(sessions: List<SessionUi>): List<String> = sessions.map { it.id }

internal fun reconcileSessionTabs(
    previous: List<SessionUi>,
    activeSessionId: String?,
    pendingTerminationIds: Set<String>,
    updated: List<SessionUi>,
): SessionTabUpdate {
    val updatedIds = updated.mapTo(linkedSetOf()) { it.id }
    val previousIds = previous.mapTo(linkedSetOf()) { it.id }
    val nextActive = when {
        activeSessionId in updatedIds -> activeSessionId
        updated.isEmpty() -> null
        activeSessionId == null -> updated.first().id
        else -> {
            val closedIndex = previous.indexOfFirst { it.id == activeSessionId }
            updated[closedIndex.takeIf { it >= 0 }?.coerceAtMost(updated.lastIndex) ?: 0].id
        }
    }
    return SessionTabUpdate(
        activeSessionId = nextActive,
        pendingTerminationIds = pendingTerminationIds.intersect(updatedIds),
        removedSessionIds = previousIds - updatedIds,
    )
}

internal fun resolvedSessionTitle(session: SessionUi, applicationTitle: String?): String =
    sessionWithDisplayTitles(session, applicationTitle).title

internal fun sessionWithDisplayTitles(session: SessionUi, applicationTitle: String?): SessionUi {
    val automaticTitle = applicationTitle?.trim()?.takeIf { it.isNotEmpty() } ?: session.initialTitle
    return session.copy(
        title = if (session.hasExplicitTabTitle) session.title else automaticTitle,
        subtitle = automaticTitle.takeIf { session.hasExplicitTabTitle },
    )
}

internal val rootlessRuntimeIds = setOf("ROOTLESS", "ROOTLESS_UBUNTU", "ROOTLESS_ARCH")

internal fun parseRuntimeChoices(json: String): List<RuntimeChoiceUi> {
    val status = runCatching { JSONObject(json) }.getOrNull()
    return listOf("NATIVE", "ROOTLESS", "ROOTLESS_UBUNTU", "ROOTLESS_ARCH", "AVF").map { runtime ->
        val entry = status?.optJSONObject(runtime)
        RuntimeChoiceUi(
            id = runtime,
            available = entry?.optBoolean("available", runtime == "NATIVE") ?: (runtime == "NATIVE"),
            detail = entry?.optString("detail").orEmpty(),
            installable = entry?.optBoolean("installable", false) ?: false,
            environmentRequired = runtime in rootlessRuntimeIds && entry?.optString("state") == "ENVIRONMENT_REQUIRED",
            state = entry?.optString("state").orEmpty(),
            progress = parseRootlessProgress(entry),
        )
    }
}

internal enum class RuntimeSelectionAction { CREATE, INSTALL, SETTINGS }

internal const val ROOTLESS_GROUP = "ROOTLESS_GROUP"

internal fun selectableRuntimeChoices(choices: List<RuntimeChoiceUi>) =
    choices.filter { it.available || it.installable }

internal fun groupedRuntimeChoices(choices: List<RuntimeChoiceUi>): List<RuntimeChoiceUi> = buildList {
    selectableRuntimeChoices(choices).forEach { choice ->
        if (choice.id in rootlessRuntimeIds) {
            if (none { it.id == ROOTLESS_GROUP }) add(RuntimeChoiceUi(ROOTLESS_GROUP, false, ""))
        } else add(choice)
    }
}

internal fun runtimeSelectionAction(choice: RuntimeChoiceUi): RuntimeSelectionAction = when {
    choice.available -> RuntimeSelectionAction.CREATE
    choice.id in rootlessRuntimeIds -> RuntimeSelectionAction.INSTALL
    else -> RuntimeSelectionAction.SETTINGS
}

internal enum class RootlessSettingsStatus { CHECKING, SETUP_REQUIRED, ENVIRONMENT_REQUIRED, READY, ERROR }

internal data class RootlessSettingsState(
    val status: RootlessSettingsStatus,
    val detail: String,
    val installed: Boolean = status == RootlessSettingsStatus.READY || status == RootlessSettingsStatus.ENVIRONMENT_REQUIRED,
    val progress: RootlessProgressUi? = null,
)

internal fun parseRootlessSettingsState(json: String, runtimeType: String = "ROOTLESS"): RootlessSettingsState = runCatching {
    val rootless = JSONObject(json).getJSONObject(runtimeType)
    val status = rootless.optString("state").takeIf { it.isNotBlank() }
        ?.let(RootlessSettingsStatus::valueOf)
        ?: if (rootless.optBoolean("available")) RootlessSettingsStatus.READY else RootlessSettingsStatus.SETUP_REQUIRED
    RootlessSettingsState(status, rootless.optString("detail"), rootless.optBoolean("installed",
        status == RootlessSettingsStatus.READY || status == RootlessSettingsStatus.ENVIRONMENT_REQUIRED), parseRootlessProgress(rootless))
}.getOrElse { RootlessSettingsState(RootlessSettingsStatus.ERROR, it.message.orEmpty()) }

internal fun validatedIntegerDraft(value: String, range: IntRange): Int? =
    value.trim().toIntOrNull()?.takeIf(range::contains)

internal fun validatedShellDraft(value: String, canExecute: (String) -> Boolean): String? {
    val candidate = value.trim()
    return candidate.takeIf { it.length <= 256 && it.startsWith('/') && canExecute(it) }
}

internal fun validatedSessionTitle(value: String): String? {
    val candidate = value.trim()
    return candidate.takeIf {
        it.isNotEmpty() &&
            it.codePointCount(0, it.length) <= 64 &&
            it.encodeToByteArray().size <= 256 &&
            it.none(Char::isISOControl)
    }
}
