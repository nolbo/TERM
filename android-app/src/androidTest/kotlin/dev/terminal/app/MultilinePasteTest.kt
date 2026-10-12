package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.ipc.IRuntimeStateListener
import dev.terminal.runtime.lifecycle.VmRuntimeService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Requires a provisioned Rootless image; creates and terminates only its own test sessions. */
@RunWith(AndroidJUnit4::class)
class MultilinePasteTest {
    @Test fun nativeAndRootlessAcceptImeAndSelectionMultilinePastes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val connected = CountDownLatch(1)
        lateinit var service: IRuntimeService
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = IRuntimeService.Stub.asInterface(binder)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, VmRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(connected.await(10, TimeUnit.SECONDS))
            service.startRuntime()
            await { service.state == "READY" }
            for (runtime in listOf("NATIVE", "ROOTLESS")) {
                val previous = ids(service.sessionsJson)
                service.createSession(runtime, if (runtime == "NATIVE") "/system/bin/sh" else "/bin/bash", "/", 24, 80)
                var id: String? = null
                await { id = (ids(service.sessionsJson) - previous).singleOrNull(); id != null }
                val sessionId = requireNotNull(id)
                val backend = RemoteTerminalBackend(
                    write = { service.writeSessionInput(sessionId, it) },
                    resize = { cols, rows -> service.resizeSession(sessionId, rows, cols) },
                )
                val listener = object : IRuntimeStateListener.Stub() {
                    override fun onTerminalOutput(id: String, bytes: ByteArray, replay: Boolean) { if (id == sessionId) backend.output(bytes, replay) }
                    override fun onRuntimeStateChanged(state: String, detail: String) = Unit
                    override fun onSessionFocusRequested(sessionId: String) = Unit
                    override fun onSessionsChanged(revision: Long, json: String) = Unit
                    override fun onSessionExited(id: String, code: Int) = Unit
                    override fun onSessionTerminationFailed(id: String, message: String) = Unit
                    override fun onRootlessProgress(runtimeType: String, phase: String, detail: String) = Unit
                    override fun onOperationError(operation: String, message: String) = Unit
                }
                try {
                    lateinit var terminal: TerminalSession
                    lateinit var view: TerminalView
                    instrumentation.runOnMainSync {
                        terminal = TerminalSession("", "/", emptyArray(), emptyArray(), 1000, TerminalCallbacks {}, backend)
                        view = TerminalView(context, null)
                        view.setTerminalViewClient(TerminalInputCallbacks())
                        view.setTextSize(20)
                        view.layout(0, 0, 1000, 800)
                        view.attachSession(terminal)
                    }
                    service.registerListener(listener)
                    service.attachSession(sessionId)
                    await {
                        var ready = false
                        instrumentation.runOnMainSync { ready = terminal.emulator.screen.transcriptText.isNotBlank() }
                        ready
                    }
                    for (ime in listOf(true, false)) {
                        val tag = if (ime) "IME" else "MENU"
                        val payload = "echo ${tag}_FIRST\r\necho ${tag}_\\\nJOIN\necho ${tag}_LAST"
                        instrumentation.runOnMainSync {
                            if (ime) view.onCreateInputConnection(EditorInfo()).commitText(payload, 1)
                            else terminal.emulator.paste(payload)
                            // Accept bracketed paste (also executes the last line in mksh).
                            terminal.write("\r")
                        }
                        await {
                            var lines = emptyList<String>()
                            instrumentation.runOnMainSync { lines = terminal.emulator.screen.transcriptText.lines() }
                            listOf("${tag}_FIRST", "${tag}_JOIN", "${tag}_LAST").all { it in lines }
                        }
                    }
                } finally {
                    service.unregisterListener(listener)
                    backend.close()
                    service.terminateSession(sessionId)
                }
            }
        } finally { context.unbindService(connection) }
    }

    private fun ids(json: String): Set<String> = JSONArray(json).let { array ->
        (0 until array.length()).map { array.getJSONObject(it).getString("id") }.toSet()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Multiline paste did not produce the expected complete output")
    }
}
