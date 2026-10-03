package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.termux.terminal.TerminalCheckpoint
import dev.terminal.runtime.ipc.ITerminalStateListener
import org.json.JSONObject
import java.io.DataInputStream
import java.util.Properties
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.ipc.IRuntimeStateListener
import dev.terminal.runtime.lifecycle.VmRuntimeService
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.WeakHashMap

/** UI-process mirrors survive short Activity transitions; long absences detach only subscriptions.
 * The runtime's session list remains authoritative; this store never creates or terminates PTYs.
 */
internal class TerminalSessionStore(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    val sessions = mutableMapOf<String, TerminalSession>()
    val scrollPositions = WeakHashMap<TerminalSession, TerminalView.ScrollPosition>()
    private val restoredPositions = mutableSetOf<TerminalSession>()
    private val backends = ConcurrentHashMap<String, RemoteTerminalBackend>()
    private val attached = mutableSetOf<String>()
    private val terminalListeners = ConcurrentHashMap<String, ITerminalStateListener>()
    private val palettes = mutableMapOf<String, Properties>()
    private val ready = ConcurrentHashMap.newKeySet<String>()
    private var service: IRuntimeService? = null
    private var binding = false
    @Volatile private var generation = 0L
    private var revision = -1L
    private var listener: IRuntimeStateListener? = null
    private var observerOwner: Any? = null
    private var observer: ((String) -> Unit)? = null
    private val retry = dev.terminal.runtime.lifecycle.ReconnectBackoff()
    private val staleMirrors = mutableSetOf<String>()
    private val redrawIds = mutableSetOf<String>()
    private var redrawPending = false
    private val redrawFrame = android.view.Choreographer.FrameCallback {
        redrawPending = false
        val ids = redrawIds.toList()
        redrawIds.clear()
        ids.forEach { observer?.invoke(it) }
    }
    private fun redraw(id: String) {
        if (observer == null) return
        redrawIds.add(id)
        if (!redrawPending) {
            redrawPending = true
            android.view.Choreographer.getInstance().postFrameCallback(redrawFrame)
        }
    }
    private val detachBackground = Runnable {
        if (observer != null) return@Runnable
        staleMirrors += sessions.keys
        sessions.keys.toList().forEach(::remove)
        releaseUnusedBinding()
    }
    internal fun hasMirror(id: String) = id in attached
    companion object { const val BACKGROUND_GRACE_MS = 5_000L }
    private val exitCodes = mutableMapOf<String, Int>()

    fun observe(owner: Any, changed: (String) -> Unit) {
        main.removeCallbacks(detachBackground)
        retry.reset()
        observerOwner = owner
        observer = changed
        connect()
        val stale = staleMirrors.toList()
        staleMirrors.clear()
        stale.forEach(changed)
        sessions.keys.toList().forEach(::attachWhenReady)
    }

    fun unobserve(owner: Any) {
        if (observerOwner === owner) {
            observerOwner = null
            observer = null
            android.view.Choreographer.getInstance().removeFrameCallback(redrawFrame)
            redrawPending = false
            redrawIds.clear()
            main.removeCallbacks(detachBackground)
            main.postDelayed(detachBackground, BACKGROUND_GRACE_MS)
        }
    }

    fun getOrCreate(id: String, transcriptRows: Int, palette: Properties): TerminalSession = sessions.getOrPut(id) {
        palettes[id] = palette
        val backend = RemoteTerminalBackend(
            write = { bytes -> call { it.writeSessionInput(id, bytes) } },
            resize = { columns, rows -> call { it.resizeSession(id, rows, columns) } },
            onStarted = { attachWhenReady(id) },
            authoritative = true,
            onGeometry = { configure(id) },
        )
        backends[id] = backend
        TerminalSession("", "/", emptyArray(), emptyArray(), transcriptRows,
            TerminalCallbacks(redraw = { redraw(id) }), backend)
    }

    fun attachWhenReady(id: String) {
        val runtime = service ?: return
        if (observer == null && sessions.isEmpty()) return
        if (sessions[id]?.emulator == null || !attached.add(id)) return
        val currentGeneration = generation
        val backend = backends.getValue(id)
        val stream = object : ITerminalStateListener.Stub() {
            override fun onCheckpoint(state: ParcelFileDescriptor) {
                try {
                    DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(state)).use { input ->
                        val position = if (input.readBoolean()) TerminalView.ScrollPosition(input.readInt(), input.readFloat(), input.readLong()) else null
                        val emulator = TerminalCheckpoint.read(input)
                        if (currentGeneration != generation) return
                        backend.checkpoint(emulator)
                        ready.add(id)
                        main.post {
                            if (currentGeneration != generation) return@post
                            if (position != null) {
                                sessions[id]?.let { scrollPositions[it] = position }
                                sessions[id]?.let { restoredPositions.add(it) }
                            }
                            observer?.invoke(id)
                        }
                    }
                } catch (error: Exception) { onError(error.message ?: "체크포인트 오류") }
            }
            override fun onOutput(bytes: ByteArray) {
                if (currentGeneration == generation && id in ready) backend.mirrorOutput(bytes)
            }
            override fun onResize(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
                if (currentGeneration == generation && id in ready) backend.resized(columns, rows, cellWidth, cellHeight)
            }
            override fun onExited(code: Int) {
                if (currentGeneration == generation && id in ready) backend.exit(code)
            }
            override fun onError(message: String) {
                main.post {
                    if (currentGeneration == generation && terminalListeners[id] === this) {
                        ready.remove(id)
                        attached.remove(id)
                        terminalListeners.remove(id)
                        runCatching { service?.detachTerminal(id, this) }
                        android.widget.Toast.makeText(context, "터미널 화면 복원 실패: $message", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        terminalListeners[id] = stream
        runCatching {
            runtime.attachTerminal(id, stream, backend.rows, backend.columns, backend.cellWidth, backend.cellHeight, paletteJson(id))
        }.onFailure { disconnected() }
    }

    fun setPalette(id: String, palette: Properties) {
        if (palettes[id] == palette) return
        palettes[id] = palette
        configure(id)
    }

    private fun paletteJson(id: String) = JSONObject().apply {
        palettes[id]?.forEach { key, value -> put(key.toString(), value.toString()) }
    }.toString()

    private fun configure(id: String) {
        val backend = backends[id] ?: return
        if (id !in attached) return
        call { it.configureTerminal(id, backend.rows, backend.columns, backend.cellWidth, backend.cellHeight, paletteJson(id)) }
    }

    fun saveViewport(session: TerminalSession, position: TerminalView.ScrollPosition) {
        val id = sessions.entries.firstOrNull { it.value === session }?.key ?: return
        if (id !in ready) return
        call { it.setTerminalViewport(id, position.row, position.offset, position.transcriptScroll) }
    }

    fun restoreViewport(view: TerminalView) {
        if (restoredPositions.remove(view.currentSession)) view.restoreSessionScrollPosition()
    }

    fun remove(id: String) {
        backends.remove(id)?.close()
        sessions.remove(id)?.let(restoredPositions::remove)
        attached.remove(id)
        terminalListeners.remove(id)?.let { stream -> runCatching { service?.detachTerminal(id, stream) } }
        palettes.remove(id)
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
            // The dedicated checkpoint stream owns this mirror's bytes.
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
                    }
                }
                releaseUnusedBinding()
            }
        }

        override fun onSessionExited(sessionId: String, exitCode: Int) {
            main.post {
                if (currentGeneration == generation) {
                    exitCodes[sessionId] = exitCode
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
            retry.reset()
            val current = createListener(++generation)
            listener = current
            revision = -1L
            runCatching {
                runtime.registerListener(current)
                sessions.keys.toList().forEach { id ->
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
        if (!binding) main.postDelayed(reconnect, retry.nextDelayMs())
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
        if (observer != null) main.postDelayed(reconnect, retry.nextDelayMs())
    }
}
