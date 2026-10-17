package dev.terminal.app.ui

import dev.terminal.runtime.workspace.WorkspaceRegistry
import org.json.JSONObject
import kotlin.math.roundToInt

internal const val KEY_TERMINAL_LAYOUTS = "workspace-terminal-layouts"

internal enum class SplitDirection {
    SideBySide, Stacked;

    companion object {
        fun default(landscape: Boolean) = if (landscape) SideBySide else Stacked
    }
}

/** A screen projection only. IDs refer to service-owned sessions; no operation owns a PTY. */
internal data class TerminalPaneState(
    val first: String? = null,
    val second: String? = null,
    val focus: Int = 0,
    val direction: SplitDirection = SplitDirection.Stacked,
    val fraction: Float = 0.5f,
    /** The split group stays in first/second while this independent tab is displayed. */
    val soloSessionId: String? = null,
) {
    init {
        require(second == null || (first != null && first != second))
        require(focus == 0 || (focus == 1 && second != null))
        require(fraction.isFinite() && fraction in MIN_FRACTION..MAX_FRACTION)
        require(soloSessionId == null || (second != null && soloSessionId != first && soloSessionId != second))
    }

    val split get() = second != null && soloSessionId == null
    val focusedPaneIndex get() = if (soloSessionId != null) 0 else focus
    val focusedSessionId get() = soloSessionId ?: if (focus == 0) first else second
    val sessionIds get() = soloSessionId?.let(::listOf) ?: listOfNotNull(first, second)
    val splitSessionIds get() = if (second != null) listOfNotNull(first, second) else emptyList()

    fun select(id: String): TerminalPaneState = when (id) {
        first -> copy(focus = 0, soloSessionId = null)
        second -> copy(focus = 1, soloSessionId = null)
        else -> if (second != null) copy(soloSessionId = id) else copy(first = id)
    }

    fun unsplit() = copy(first = focusedSessionId, second = null, focus = 0, soloSessionId = null)

    fun canSplitWith(id: String, sessions: List<SessionUi>, memberships: Map<String, String>): Boolean {
        val current = sessions.firstOrNull { it.id == focusedSessionId } ?: return false
        val candidate = sessions.firstOrNull { it.id == id } ?: return false
        return current.canSplit && candidate.canSplit && id !in sessionIds &&
            memberships[current.id] == memberships[candidate.id]
    }

    fun openSplit(id: String, sessions: List<SessionUi>, memberships: Map<String, String>,
        defaultDirection: SplitDirection): TerminalPaneState {
        if (!canSplitWith(id, sessions, memberships)) return this
        return if (!split) copy(first = focusedSessionId, second = id, focus = 1,
            direction = defaultDirection, fraction = 0.5f, soloSessionId = null)
        else if (focus == 0) copy(second = id, focus = 1) else copy(first = id, focus = 0)
    }

    /** A drop names both sessions explicitly, independently of the currently focused tab. */
    fun pair(source: String, target: String, sessions: List<SessionUi>, memberships: Map<String, String>,
        defaultDirection: SplitDirection, sourceFirst: Boolean = false): TerminalPaneState {
        if (!TerminalPaneState(target).canSplitWith(source, sessions, memberships)) return this
        return copy(first = if (sourceFirst) source else target, second = if (sourceFirst) target else source,
            focus = if (sourceFirst) 0 else 1,
            direction = if (second != null) direction else defaultDirection,
            fraction = if (second != null) fraction else 0.5f, soloSessionId = null)
    }

    /** Restore callers supply live/pending descriptors; current tabs can retain an exited screen. */
    fun reconcile(visible: List<SessionUi>, memberships: Map<String, String>, fallback: String? = null): TerminalPaneState {
        val byId = visible.associateBy { it.id }
        if (second != null && byId[first]?.canSplit == true && byId[second]?.canSplit == true &&
            memberships[first] == memberships[second]) {
            return if (soloSessionId == null || soloSessionId in byId) this else copy(soloSessionId = null)
        }
        if (second == null && first in byId) return this
        if (soloSessionId != null && soloSessionId in byId) return unsplit()
        val survivor = listOfNotNull(focusedSessionId, first, second).distinct()
            .firstOrNull { byId[it]?.canSplit == true }
        val next = survivor ?: fallback?.takeIf { byId[it]?.restorable == true }
            ?: focusedSessionId?.takeIf { byId[it]?.restorable == true } ?: visible.firstOrNull { it.restorable }?.id
        return copy(first = next, second = null, focus = 0, soloSessionId = null)
    }

    fun remap(replacements: Map<String, String>) = copy(
        first = replacements[first] ?: first, second = replacements[second] ?: second,
        soloSessionId = replacements[soloSessionId] ?: soloSessionId)

    companion object {
        const val MIN_FRACTION = 0.2f
        const val MAX_FRACTION = 0.8f
    }
}

private val SessionUi.canSplit get() = !pendingLaunch && state == "RUNNING" && exitCode == null
private val SessionUi.restorable get() = canSplit || pendingLaunch

internal fun reconcileWorkspaceLayouts(layouts: Map<String, TerminalPaneState>, sessions: List<SessionUi>,
    workspace: WorkspaceRegistry.State): Map<String, TerminalPaneState> {
    val keys = workspace.workspaces.mapTo(mutableSetOf(WorkspaceRegistry.UNASSIGNED)) { it.id }
    return layouts.filterKeys { it in keys }.mapValues { (id, layout) ->
        layout.reconcile(sessions.filter { it.restorable && workspace.copy(selected = id).includes(it.id) }, workspace.memberships)
    }
}

internal fun encodeTerminalLayouts(layouts: Map<String, TerminalPaneState>): String = JSONObject().apply {
    layouts.forEach { (workspace, layout) -> put(workspace, JSONObject()
        .put("first", layout.first ?: JSONObject.NULL).put("second", layout.second ?: JSONObject.NULL)
        .put("focus", layout.focus).put("direction", layout.direction.name).put("fraction", layout.fraction)
        .put("solo", layout.soloSessionId ?: JSONObject.NULL)) }
}.toString()

internal fun parseTerminalLayouts(encoded: String?): Map<String, TerminalPaneState> = runCatching {
    require((encoded?.length ?: 0) <= 128 * 1024)
    val json = JSONObject(encoded ?: "{}")
    require(json.length() <= 129)
    json.keys().asSequence().mapNotNull { key -> runCatching {
        val value = json.getJSONObject(key)
        fun session(name: String) = value.optString(name).takeUnless { value.isNull(name) || it.isBlank() }
        key to TerminalPaneState(session("first"), session("second"), value.getInt("focus"),
            SplitDirection.valueOf(value.getString("direction")), value.getDouble("fraction").toFloat(), session("solo"))
    }.getOrNull() }.toMap()
}.getOrDefault(emptyMap())

/** Clamp each pane to a useful minimum, including small windows reduced by the IME. */
internal fun splitPaneExtent(total: Int, divider: Int, fraction: Float, minimum: Int): Int {
    val available = (total - divider).coerceAtLeast(0)
    val bound = minimum.coerceIn(0, available / 2)
    return (available * fraction).roundToInt().coerceIn(bound, available - bound)
}
