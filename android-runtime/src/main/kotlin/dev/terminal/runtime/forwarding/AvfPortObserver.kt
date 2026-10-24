package dev.terminal.runtime.forwarding

import dev.terminal.runtime.api.GuestConnection
import dev.terminal.runtime.api.RuntimeBackend
import dev.terminal.runtime.api.RuntimeState
import dev.terminal.runtime.lifecycle.ReconnectBackoff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.Closeable

/** One event-driven link per host-selected VM. No Activity owns or starts this observer. */
class AvfPortObserver(
    private val scope: CoroutineScope,
    private val environmentId: String,
    private val backend: RuntimeBackend,
    private val manager: LocalServiceManager,
    private val canReconnect: () -> Boolean,
    private val changed: () -> Unit,
    private val retry: ReconnectBackoff = ReconnectBackoff(),
) : Closeable {
    @Volatile private var ready = false
    private var closed = false
    @Volatile private var generation = 0L
    private var agent: Job? = null
    private var connection: GuestConnection? = null
    private val stateJob = scope.launch {
        backend.observeState().collect { state ->
            synchronized(this@AvfPortObserver) {
                ready = state is RuntimeState.Ready || state is RuntimeState.Active || state is RuntimeState.Idle
                if (ready) reconcile() else disconnect(state is RuntimeState.Stopped || state is RuntimeState.Stopping)
            }
        }
    }

    @Synchronized fun reconcile() {
        if (closed || !ready || agent?.isActive == true || !canReconnect()) return
        val currentGeneration = generation
        agent = scope.launch(start = CoroutineStart.LAZY) {
            while (ready && canReconnect() && generation == currentGeneration) {
                manager.link(environmentId, "CONNECTING"); changed()
                dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record("ports.connect", environmentId)
                val transport = backend.connectGuestPortAgent().onFailure {
                    dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record("ports.connect.failed", environmentId, error = it,
                        details = mapOf("generation" to currentGeneration.toString()))
                }.getOrNull()
                if (transport != null) {
                    synchronized(this@AvfPortObserver) {
                        if (closed || !ready || generation != currentGeneration) { transport.close(); return@launch }
                        connection = transport
                    }
                    retry.reset()
                    dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record("ports.connected", environmentId)
                    manager.link(environmentId, "CONNECTED"); changed()
                    try { PortAgentCollector { event -> synchronized(this@AvfPortObserver) {
                        if (generation == currentGeneration) manager.event(environmentId, event)
                    } }.collect(transport) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record("ports.read.failed", environmentId, error = error)
                    }
                    finally {
                        dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record("ports.disconnected", environmentId)
                        runCatching { transport.close() }
                        synchronized(this@AvfPortObserver) { if (connection === transport) connection = null }
                        synchronized(this@AvfPortObserver) {
                            if (generation == currentGeneration) { manager.disconnected(environmentId); changed() }
                        }
                    }
                } else { synchronized(this@AvfPortObserver) {
                    if (generation == currentGeneration) { manager.disconnected(environmentId); changed() }
                } }
                if (ready && canReconnect()) retry.awaitRetry { delay ->
                    dev.terminal.runtime.lifecycle.ConnectionDiagnostics.record("ports.retry.wait", environmentId,
                        details = mapOf("delay_ms" to delay.toString(), "generation" to currentGeneration.toString()))
                }
            }
        }.also { it.start() }
    }

    @Synchronized private fun disconnect(stopped: Boolean) {
        generation++
        runCatching { connection?.close() }; connection = null
        agent?.cancel(); agent = null
        manager.disconnected(environmentId, stopped); changed()
    }
    @Synchronized fun invalidate() { disconnect(false); reconcile() }
    @Synchronized override fun close() {
        closed = true
        stateJob.cancel()
        disconnect(true)
    }
}
