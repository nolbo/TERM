package dev.terminal.runtime.workspace

import dev.terminal.runtime.session.SessionLaunchSpec
import dev.terminal.runtime.session.SessionProfile
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class WorkspaceLaunch(val id: String, val name: String, val profileId: String, val directory: String = "",
    val startWithWorkspace: Boolean = false) {
    init {
        uuid(id); label(name)
        require(profileId.isNotBlank() && profileId.length <= 128)
        require(directory.isEmpty() || directory.startsWith('/'))
        require(directory.length <= 4096 && directory.none(Char::isISOControl))
    }
    fun resolve(profile: SessionProfile, resolve: (SessionProfile) -> SessionLaunchSpec): SessionLaunchSpec {
        require(profile.id == profileId) { "Profile was removed" }
        return resolve(if (directory.isEmpty()) profile else profile.copy(workingDirectory = directory))
    }
    fun json() = JSONObject().put("id", id).put("name", name).put("profileId", profileId).put("directory", directory)
        .put("startWithWorkspace", startWithWorkspace)
    companion object {
        fun parse(value: JSONObject) = WorkspaceLaunch(value.getString("id"), value.getString("name"),
            value.getString("profileId"), value.optString("directory"), value.optBoolean("startWithWorkspace"))
    }
}

data class Workspace(val id: String, val name: String, val temporary: Boolean, val launches: List<WorkspaceLaunch> = emptyList()) {
    init { uuid(id); label(name); require(launches.size <= 64 && launches.map { it.id }.distinct().size == launches.size); require(!temporary || launches.isEmpty()) }
    fun json() = JSONObject().put("id", id).put("name", name).put("temporary", temporary).put("launches", JSONArray(launches.map { it.json() }))
    companion object {
        fun parse(value: JSONObject): Workspace {
            val launches = value.optJSONArray("launches") ?: JSONArray()
            return Workspace(value.getString("id"), value.getString("name"), value.getBoolean("temporary"),
                List(launches.length()) { WorkspaceLaunch.parse(launches.getJSONObject(it)) })
        }
    }
}

/** Single runtime-process writer. This registry cannot access a controller or any filesystem owner. */
class WorkspaceRegistry(private val read: () -> String?, private val write: (String) -> Unit) {
    data class State(val workspaces: List<Workspace> = emptyList(), val memberships: Map<String, String> = emptyMap(), val selected: String = ALL) {
        fun json() = JSONObject().put("version", 1).put("workspaces", JSONArray(workspaces.map { it.json() }))
            .put("memberships", JSONObject(memberships)).put("selected", selected)
        fun includes(sessionId: String): Boolean = when (selected) {
            ALL -> true
            UNASSIGNED -> sessionId !in memberships
            else -> memberships[sessionId] == selected
        }
        /** Conservatively reuse any existing or pending member instead of creating replacement sessions. */
        fun startupLaunches(pendingWorkspaceIds: Set<String> = emptySet()): List<WorkspaceLaunch> =
            if (selected in pendingWorkspaceIds || selected in memberships.values) emptyList()
            else workspaces.firstOrNull { it.id == selected }?.launches.orEmpty().filter { it.startWithWorkspace }
        companion object {
            fun parse(encoded: String): State {
                require(encoded.toByteArray().size <= 512 * 1024)
                val json = JSONObject(encoded)
                require(json.getInt("version") == 1)
                val rows = json.getJSONArray("workspaces")
                require(rows.length() <= 128)
                val workspaces = List(rows.length()) { Workspace.parse(rows.getJSONObject(it)) }
                require(workspaces.map { it.id }.distinct().size == workspaces.size)
                val ids = workspaces.mapTo(mutableSetOf()) { it.id }
                val memberships = json.getJSONObject("memberships").let { map -> map.keys().asSequence().associateWith { map.getString(it) } }
                require(memberships.size <= 4096 && memberships.all { (session, workspace) -> session.length in 1..256 && workspace in ids })
                val selected = json.getString("selected")
                require(selected in setOf(ALL, UNASSIGNED) || selected in ids)
                return State(workspaces, memberships, selected)
            }
        }
    }
    @Synchronized fun snapshot(): State = read()?.let(State::parse) ?: State()
    @Synchronized fun save(workspace: Workspace) {
        val state = snapshot()
        val previous = state.workspaces.firstOrNull { it.id == workspace.id }
        require(previous == null || !workspace.temporary || previous.temporary) { "Permanent workspace cannot become temporary" }
        val rows = state.workspaces.filterNot { it.id == workspace.id } + workspace
        require(rows.size <= 128) { "Workspace limit reached" }
        persist(state.copy(workspaces = rows))
    }
    @Synchronized fun createTemporary(name: String): String = UUID.randomUUID().toString().also { save(Workspace(it, name, true)) }
    @Synchronized fun select(id: String) {
        val state = snapshot()
        require(id in setOf(ALL, UNASSIGNED) || state.workspaces.any { it.id == id }) { "Workspace not found" }
        persist(state.copy(selected = id))
    }
    @Synchronized fun move(sessionId: String, workspaceId: String?) {
        require(sessionId.length in 1..256)
        val state = snapshot()
        require(workspaceId == null || state.workspaces.any { it.id == workspaceId }) { "Workspace not found" }
        persist(state.copy(memberships = (state.memberships - sessionId) + if (workspaceId == null) emptyMap() else mapOf(sessionId to workspaceId)))
    }
    @Synchronized fun delete(id: String) {
        val state = snapshot()
        persist(state.copy(workspaces = state.workspaces.filterNot { it.id == id },
            memberships = state.memberships.filterValues { it != id }, selected = if (state.selected == id) UNASSIGNED else state.selected))
    }
    /** Null means the owner could not establish actual session state. Never collect from a UI cache. */
    @Synchronized fun reconcile(liveIds: Set<String>?, pendingWorkspaceIds: Set<String> = emptySet()) {
        if (liveIds == null) return
        val state = snapshot()
        val memberships = state.memberships.filterKeys { it in liveIds }
        val occupied = memberships.values.toSet() + pendingWorkspaceIds
        val rows = state.workspaces.filter { !it.temporary || it.id == state.selected || it.id in occupied }
        val next = state.copy(workspaces = rows, memberships = memberships)
        if (state != next) persist(next)
    }
    private fun persist(state: State) {
        val encoded = state.json().toString()
        State.parse(encoded) // Validate total limits before replacing the last readable state.
        write(encoded)
    }
    companion object { const val ALL = "all"; const val UNASSIGNED = "unassigned" }
}

private fun uuid(value: String) { require(UUID.fromString(value).toString() == value) { "Invalid workspace ID" } }
private fun label(value: String) { require(value.isNotBlank() && value.length <= 128 && value.none(Char::isISOControl)) { "Name must contain 1–128 printable characters" } }
