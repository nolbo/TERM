package dev.terminal.runtime.workspace

import dev.terminal.runtime.icon.BuiltInIcon

/** Shared built-in identities; no paths, imported assets or runtime ownership. */
object WorkspaceIcon {
    const val DEFAULT = "team_dashboard"
    val builtIns = BuiltInIcon.ids

    fun validate(value: String) {
        require(value in builtIns) { "Invalid workspace icon" }
    }
}
