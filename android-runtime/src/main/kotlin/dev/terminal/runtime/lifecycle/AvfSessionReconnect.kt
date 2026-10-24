package dev.terminal.runtime.lifecycle

import dev.terminal.protocol.SessionController
import dev.terminal.protocol.SessionStatus
import dev.terminal.runtime.session.Environment
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.session.UnifiedSessionRegistry
import kotlinx.coroutines.CancellationException

/** Reconnect failed AVF links without replacing healthy controllers or terminal state. */
internal suspend fun reconnectAvfSessions(
    registry: UnifiedSessionRegistry,
    environmentForStorage: (String) -> Environment,
    connect: suspend (Environment) -> SessionController?,
    onConnected: (Environment) -> Unit,
    canReconnect: (Environment) -> Boolean = { true },
    withEnvironmentLease: suspend (Environment, suspend () -> Unit) -> Unit = { _, action -> action() },
) {
    for (id in registry.disconnectedStorages(SessionRuntime.AVF)) {
        var replacement: SessionController? = null
        try {
            val environment = environmentForStorage(id)
            if (!canReconnect(environment)) continue
            withEnvironmentLease(environment) retry@{
                if (id !in registry.disconnectedStorages(SessionRuntime.AVF) || !canReconnect(environment)) return@retry
                val controller = connect(environment) ?: return@retry
                replacement = controller
                registry.register(environment, controller)
                val sessions = registry.refreshEnvironment(environment).getOrElse { runCatching { controller.close() }; return@retry }
                for (session in sessions.filter { it.state == SessionStatus.RUNNING }) {
                    if (registry.attach(session.id).isFailure) { runCatching { controller.close() }; return@retry }
                }
                onConnected(environment)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            replacement?.let { runCatching { it.close() } }
            ConnectionDiagnostics.record("session.reconnect.failed", id, error = error)
        }
    }
}
