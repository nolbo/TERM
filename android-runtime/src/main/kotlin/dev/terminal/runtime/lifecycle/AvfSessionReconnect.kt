package dev.terminal.runtime.lifecycle

import dev.terminal.protocol.SessionController
import dev.terminal.protocol.SessionStatus
import dev.terminal.runtime.session.Environment
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.session.UnifiedSessionRegistry

/** Reconnect failed AVF links without replacing healthy controllers or terminal state. */
internal suspend fun reconnectAvfSessions(
    registry: UnifiedSessionRegistry,
    environmentForStorage: (String) -> Environment,
    connect: suspend (Environment) -> SessionController?,
    onConnected: () -> Unit,
    canReconnect: (Environment) -> Boolean = { true },
) {
    for (id in registry.disconnectedStorages(SessionRuntime.AVF)) {
        val environment = environmentForStorage(id)
        if (!canReconnect(environment)) continue
        val controller = connect(environment) ?: continue
        registry.register(environment, controller)
        onConnected()
        registry.list().getOrNull().orEmpty().filter { session ->
            registry.runtimeOf(session.id) == SessionRuntime.AVF && registry.storageOf(session.id) == id &&
                session.state == SessionStatus.RUNNING
        }.forEach { session -> registry.attach(session.id) }
    }
}
