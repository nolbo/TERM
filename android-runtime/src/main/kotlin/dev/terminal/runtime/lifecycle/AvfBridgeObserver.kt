package dev.terminal.runtime.lifecycle

import dev.terminal.runtime.api.GuestConnection
import dev.terminal.runtime.api.RuntimeBackend
import dev.terminal.runtime.api.RuntimeState
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** The Android bridge follows exactly one Environment's VM and work demand. */
internal class AvfBridgeObserver(
    private val scope: CoroutineScope,
    private val environmentId: String,
    private val owner: RuntimeBackend,
    private val canReconnect: () -> Boolean,
    private val onWorkChanged: (Boolean) -> Unit,
    private val serve: suspend (GuestConnection, (Boolean) -> Unit) -> Unit,
    private val retry: ReconnectBackoff = ReconnectBackoff(),
) : Closeable {
    private var ready = false
    private var closed = false
    private var generation = 0L
    private var worker: Job? = null
    private var connection: GuestConnection? = null
    private val stateJob = scope.launch {
        owner.observeState().collect { state ->
            synchronized(this@AvfBridgeObserver) {
                ready = state is RuntimeState.Ready || state is RuntimeState.Active || state is RuntimeState.Idle
                if (ready) reconcile() else disconnect()
            }
        }
    }

    @Synchronized fun resetBackoff() { retry.reset(); reconcile() }

    @Synchronized fun reconcile() {
        if (closed || !ready || worker?.isActive == true || !canReconnect()) return
        val epoch = generation
        worker = scope.launch(start = CoroutineStart.LAZY) {
            while (eligible(epoch)) {
                var transport: GuestConnection? = null
                try {
                    ConnectionDiagnostics.record("bridge.connect", environmentId)
                    transport = owner.connectGuestBridge().getOrNull()
                    if (transport != null) {
                        synchronized(this@AvfBridgeObserver) {
                            if (!eligible(epoch)) { transport.close(); return@launch }
                            connection = transport
                        }
                        retry.reset()
                        ConnectionDiagnostics.record("bridge.connected", environmentId)
                        serve(transport) { active -> synchronized(this@AvfBridgeObserver) {
                            if (!closed && generation == epoch) onWorkChanged(active)
                        } }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    ConnectionDiagnostics.record("bridge.read.failed", environmentId, error = error)
                } finally {
                    transport?.let { runCatching { it.close() } }
                    synchronized(this@AvfBridgeObserver) {
                        if (connection === transport) connection = null
                        if (generation == epoch) onWorkChanged(false)
                    }
                }
                if (eligible(epoch)) retry.awaitRetry()
            }
        }.also { it.start() }
    }

    @Synchronized private fun eligible(epoch: Long) = !closed && ready && generation == epoch && canReconnect()

    @Synchronized private fun disconnect() {
        generation++
        runCatching { connection?.close() }; connection = null
        worker?.cancel(); worker = null
        onWorkChanged(false)
    }

    @Synchronized override fun close() {
        closed = true
        stateJob.cancel()
        disconnect()
    }
}
