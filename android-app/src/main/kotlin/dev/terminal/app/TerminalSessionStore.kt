package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.ipc.IRuntimeStateListener
import dev.terminal.runtime.lifecycle.VmRuntimeService
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.WeakHashMap

/** UI-process mirrors and their output subscription survive destruction of an Activity.
 * The runtime's session list remains authoritative; this store never creates or terminates PTYs.
 */
internal class TerminalSessionStore(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    val sessions = mutableMapOf<String, TerminalSession>()
    val scrollPositions = WeakHashMap<TerminalSession, TerminalView.ScrollPosition>()
    private val backends = ConcurrentHashMap<String, RemoteTerminalBackend>()
    private val attached = mutableSetOf<String>()
    private val pendingReplay = ConcurrentHashMap.newKeySet<String>()
    private val replaying = ConcurrentHashMap.newKeySet<String>()
    private val ready = ConcurrentHashMap.newKeySet<String>()
    private var service: IRuntimeService? = null
    private var binding = false
    @Volatile private var generation = 0L
    private var revision = -1L
    private var listener: IRuntimeStateListener? = null
    private var observerOwner: Any? = null
    private var observer: ((String) -> Unit)? = null
    private val exitCodes = mutableMapOf<String, Int>()

    fun observe(owner: Any, changed: (String) -> Unit) {
        observerOwner = owner
        observer = changed
        connect()
    }

    fun unobserve(owner: Any) {
        if (observerOwner === owner) {
            observerOwner = null
            observer = null
            releaseUnusedBinding()
        }
    }

    fun getOrCreate(id: String, transcriptRows: Int, replayExpected: Boolean = true): TerminalSession = sessions.getOrPut(id) {
        if (!replayExpected) ready.add(id)
        val backend = RemoteTerminalBackend(
            write = { bytes -> call { it.writeSessionInput(id, bytes) } },
            resize = { columns, rows -> call { it.resizeSession(id, rows, columns) } },
            onStarted = { attachWhenReady(id) },
        )
        backends[id] = backend
        TerminalSession("", "/", emptyArray(), emptyArray(), transcriptRows,
            TerminalCallbacks(redraw = { observer?.invoke(id) }), backend)
    }

    fun attachWhenReady(id: String) {
        val runtime = service ?: return
        if (sessions[id]?.emulator == null || !attached.add(id)) return
        if (id !in ready) pendingReplay.add(id)
        runCatching { runtime.attachSession(id) }.onFailure { disconnected() }
    }

    fun remove(id: String) {
        backends.remove(id)?.close()
        sessions.remove(id)
        attached.remove(id)
        pendingReplay.remove(id)
        replaying.remove(id)
        ready.remove(id)
        exitCodes.remove(id)
    }

    private fun call(action: (IRuntimeService) -> Unit) {
        val runtime = service ?: return
        runCatching { action(runtime) }.onFailure { main.post { disconnected() } }
    }

    private fun createListener(currentGeneration: Long) = object : IRuntimeStateListener.Stub() {
        override fun onTerminalOutput(sessionId: String, bytes: ByteArray, replay: Boolean) {
            if (currentGeneration != generation) return
            if (replay) {
                // Empty replay frames bracket a snapshot under the PTY output mutex. Ignore
                // another listener's attach replay when this mirror already has live state.
                if (sessionId !in pendingReplay) return
                if (bytes.isEmpty()) {
                    if (!replaying.add(sessionId)) {
                        replaying.remove(sessionId)
                        pendingReplay.remove(sessionId)
                        ready.add(sessionId)
                        main.post {
                            if (currentGeneration == generation) exitCodes[sessionId]?.let { backends[sessionId]?.exit(it) }
                        }
                    }
                    return
                }
                if (sessionId in replaying) backends[sessionId]?.output(bytes, replay = true)
            } else if (sessionId in ready) {
                backends[sessionId]?.output(bytes)
            }
        }

        override fun onSessionsChanged(newRevision: Long, sessionsJson: String) {
            main.post {
                if (currentGeneration != generation || newRevision < revision) return@post
                revision = newRevision
                val descriptors = JSONArray(sessionsJson)
                val ids = (0 until descriptors.length()).mapTo(mutableSetOf()) {
                    descriptors.getJSONObject(it).getString("id")
                }
                sessions.keys.filter { it !in ids }.forEach(::remove)
                exitCodes.keys.retainAll(ids)
                for (i in 0 until descriptors.length()) {
                    val descriptor = descriptors.getJSONObject(i)
                    if (!descriptor.isNull("exitCode")) {
                        val id = descriptor.getString("id")
                        val code = descriptor.getInt("exitCode")
                        exitCodes[id] = code
                        if (id in ready) backends[id]?.exit(code)
                    }
                }
                releaseUnusedBinding()
            }
        }

        override fun onSessionExited(sessionId: String, exitCode: Int) {
            main.post {
                if (currentGeneration == generation) {
                    exitCodes[sessionId] = exitCode
                    if (sessionId in ready) backends[sessionId]?.exit(exitCode)
                }
            }
        }
        override fun onRuntimeStateChanged(state: String, detail: String) = Unit
        override fun onSessionFocusRequested(sessionId: String) = Unit
        override fun onSessionTerminationFailed(sessionId: String, message: String) = Unit
        override fun onRootlessProgress(runtimeType: String, phase: String, detail: String) = Unit
        override fun onOperationError(operation: String, message: String) = Unit
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val runtime = IRuntimeService.Stub.asInterface(binder)
            service = runtime
            val current = createListener(++generation)
            listener = current
            revision = -1L
            runCatching {
                runtime.registerListener(current)
                sessions.toList().forEach { (id, session) ->
                    session.emulator?.let { runtime.resizeSession(id, it.mRows, it.mColumns) }
                    attachWhenReady(id)
                }
            }.onFailure { disconnected() }
        }

        override fun onServiceDisconnected(name: ComponentName) = disconnected()
        override fun onBindingDied(name: ComponentName) = disconnected()
        override fun onNullBinding(name: ComponentName) = disconnected()
    }

    private val reconnect = Runnable { if (observer != null || sessions.isNotEmpty()) connect() }

    private fun releaseUnusedBinding() {
        if (observer != null || sessions.isNotEmpty()) return
        generation++
        listener?.let { current -> runCatching { service?.unregisterListener(current) } }
        service = null
        listener = null
        if (binding) runCatching { context.unbindService(connection) }
        binding = false
        main.removeCallbacks(reconnect)
    }

    private fun connect() {
        if (binding) return
        main.removeCallbacks(reconnect)
        binding = context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!binding) main.postDelayed(reconnect, 1000)
    }

    private fun disconnected() {
        generation++
        service = null
        listener = null
        if (binding) runCatching { context.unbindService(connection) }
        binding = false
        // Never append an old transcript over a retained screen after missing live bytes.
        val lost = sessions.keys.toList()
        lost.forEach(::remove)
        lost.forEach { observer?.invoke(it) }
        main.removeCallbacks(reconnect)
        main.postDelayed(reconnect, 1000)
    }
}
