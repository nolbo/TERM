package dev.terminal.runtime.lifecycle

import dev.terminal.protocol.SessionController
import dev.terminal.protocol.SessionStatus
import dev.terminal.runtime.session.Environment
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.session.UnifiedSessionRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Reconnect failed AVF links without replacing healthy controllers or terminal state. */
internal suspend fun reconnectAvfSessions(
    registry: UnifiedSessionRegistry,
    environmentForStorage: (String) -> Environment,
    connect: suspend (Environment) -> SessionController?,
    onConnected: (Environment) -> Unit,
    canReconnect: (Environment) -> Boolean = { true },
    withEnvironmentLease: suspend (Environment, suspend () -> Unit) -> Unit = { _, action -> action() },
    storageIds: List<String> = registry.disconnectedStorages(SessionRuntime.AVF),
) = coroutineScope {
    storageIds.map { id -> async {
        var replacement: SessionController? = null
        var committed = false
        try {
            val environment = environmentForStorage(id)
            if (!canReconnect(environment)) return@async
            withEnvironmentLease(environment) retry@{
                if (id !in registry.disconnectedStorages(SessionRuntime.AVF) || !canReconnect(environment)) return@retry
                ConnectionDiagnostics.record("session.reconnect.owner", environment.id.value, details = mapOf("storage" to id))
                val controller = connect(environment) ?: return@retry
                replacement = controller
                registry.register(environment, controller)
                val sessions = registry.refreshEnvironment(environment).getOrElse {
                    ConnectionDiagnostics.record("session.reconnect.list.failed", environment.id.value, error = it,
                        details = mapOf("storage" to id))
                    runCatching { controller.close() }; return@retry
                }
                for (session in sessions.filter { it.state == SessionStatus.RUNNING }) {
                    val attached = registry.attach(session.id)
                    if (attached.isFailure) {
                        ConnectionDiagnostics.record("session.reconnect.attach.failed", environment.id.value, error = attached.exceptionOrNull(),
                            details = mapOf("storage" to id, "session" to session.id))
                        runCatching { controller.close() }; return@retry
                    }
                }
                ConnectionDiagnostics.record("session.reconnect.ready", environment.id.value,
                    details = mapOf("storage" to id, "sessions" to sessions.size.toString(),
                        "running" to sessions.count { it.state == SessionStatus.RUNNING }.toString()))
                onConnected(environment)
                committed = true
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            ConnectionDiagnostics.record("session.reconnect.failed", id, error = error)
        } finally {
            if (!committed) replacement?.let { runCatching { it.close() } }
        }
    } }.awaitAll()
    Unit
}
