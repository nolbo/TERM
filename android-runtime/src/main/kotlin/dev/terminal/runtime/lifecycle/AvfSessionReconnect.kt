package dev.terminal.runtime.lifecycle

import dev.terminal.protocol.SessionController
import dev.terminal.protocol.SessionStatus
import dev.terminal.runtime.session.Environment
import dev.terminal.runtime.session.SessionRuntime
import dev.terminal.runtime.session.UnifiedSessionRegistry

/** Reconnect failed AVF links without replacing healthy controllers or terminal state. */
internal suspend fun reconnectAvfSessions(
    registry: UnifiedSessionRegistry,
    defaultStorageId: String,
    primaryIsAvf: Boolean,
    environmentForStorage: (String) -> Environment,
    connect: suspend (Environment) -> SessionController?,
    onConnected: () -> Unit,
) {
    for (id in registry.disconnectedStorages(SessionRuntime.AVF)) {
        // ensureSessionRegistry attempts this link only when the primary owner is AVF.
        if (primaryIsAvf && id == defaultStorageId) continue
        val environment = environmentForStorage(id)
        val controller = connect(environment) ?: continue
        registry.register(environment, controller)
        onConnected()
        registry.list().getOrNull().orEmpty().filter { session ->
            registry.runtimeOf(session.id) == SessionRuntime.AVF && registry.storageOf(session.id) == id &&
                session.state == SessionStatus.RUNNING
        }.forEach { session -> registry.attach(session.id) }
    }
}
