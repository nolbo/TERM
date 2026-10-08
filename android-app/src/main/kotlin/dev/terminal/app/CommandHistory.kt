package dev.terminal.app

import com.termux.terminal.CommandMarker
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSearch

internal enum class HistorySearchScope(val title: Int, val cells: TerminalSearch.Scope) {
    Commands(R.string.history_scope_commands, TerminalSearch.Scope.COMMANDS),
    Output(R.string.history_scope_output, TerminalSearch.Scope.OUTPUT),
    All(R.string.history_scope_all, TerminalSearch.Scope.ALL),
}

internal enum class HistoryCommandStatus { Running, Completed, Failed }

internal data class HistoryCommand(
    val marker: CommandMarker,
    val row: Int,
    val status: HistoryCommandStatus,
    val exitCode: Int?,
) {
    val viewportRow get() = row.coerceAtMost(0)
}

internal data class HistoryTarget(val command: HistoryCommand? = null, val bottom: Boolean = false)

/** A view of retained cells, never a second shell history or a session owner. */
internal fun readCommandHistory(emulator: TerminalEmulator): List<HistoryCommand> =
    emulator.history.markers.mapNotNull { marker ->
        val row = marker.anchor.row ?: return@mapNotNull null
        HistoryCommand(marker, row, when {
            marker.isRunning -> HistoryCommandStatus.Running
            marker.isFailed -> HistoryCommandStatus.Failed
            else -> HistoryCommandStatus.Completed
        }, marker.exitCode)
    }

/** At the first/last command, the next press in that direction reaches the history edge.
 * Identity lets commands sharing a viewport row remain individually navigable.
 */
internal fun commandHistoryTarget(commands: List<HistoryCommand>, row: Int, next: Boolean,
    selected: CommandMarker? = null): HistoryTarget {
    val index = commands.indexOfFirst { it.marker === selected && it.viewportRow == row }
    val command = if (index >= 0) commands.getOrNull(index + if (next) 1 else -1)
        else if (next) commands.firstOrNull { it.viewportRow > row }
        else commands.lastOrNull { it.viewportRow < row }
    return HistoryTarget(command, bottom = next)
}

internal fun currentHistoryCommand(commands: List<HistoryCommand>, row: Int,
    selected: CommandMarker?): CommandMarker? =
    commands.firstOrNull { it.marker === selected && it.viewportRow == row }?.marker
        ?: commands.lastOrNull { it.viewportRow <= row }?.marker
