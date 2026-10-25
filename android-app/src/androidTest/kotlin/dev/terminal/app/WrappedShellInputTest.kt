package dev.terminal.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.terminal.emulator.TerminalSession
import dev.terminal.runtime.ipc.IRuntimeService
import dev.terminal.runtime.ipc.IRuntimeStateListener
import dev.terminal.runtime.lifecycle.VmRuntimeService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WrappedShellInputTest {
    @Test fun nativeInitialWidthAndLongInputEditing() = checkRuntime("NATIVE")
    @Test fun rootlessInitialWidthAndWrappedInputEditing() = checkRuntime("ROOTLESS")

    private fun checkRuntime(runtime: String) {
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

            assumeTrue("$runtime image must be provisioned", JSONObject(service.runtimeStatusJson).getJSONObject(runtime).getBoolean("available"))
            val previous = ids(service.sessionsJson)
            service.createSession(runtime, if (runtime == "NATIVE") "/system/bin/sh" else "/bin/bash", sessionInitialDirectory(runtime), 24, 80)
            var id: String? = null
            await("test session") { id = (ids(service.sessionsJson) - previous).singleOrNull(); id != null }
            val sessionId = requireNotNull(id)
            val backend = RemoteTerminalBackend(
                write = { service.writeSessionInput(sessionId, it) },
                resize = { cols, rows -> service.resizeSession(sessionId, rows, cols) },
            )
            val listener = object : IRuntimeStateListener.Stub() {
                override fun onSessionTaskCompletionsChanged(completionsJson: String) = Unit
                override fun onTerminalOutput(id: String, bytes: ByteArray, replay: Boolean) {
                    if (id == sessionId) backend.output(bytes, replay)
                }
                override fun onRuntimeChoicesChanged() = Unit
                override fun onSessionFocusRequested(sessionId: String) = Unit
                override fun onSessionsChanged(revision: Long, json: String) = Unit
                override fun onSessionExited(id: String, code: Int) = Unit
                override fun onSessionTerminationFailed(id: String, message: String) = Unit
                override fun onRootlessProgress(runtimeType: String, phase: String, detail: String) = Unit
                override fun onOperationError(operation: String, message: String) = Unit
            }
            try {
                lateinit var terminal: TerminalSession
                instrumentation.runOnMainSync {
                    terminal = TerminalSession("", "/", emptyArray(), emptyArray(), 1000, TerminalCallbacks {}, backend)
                    // No subsequent resize: catches PTYs left at the create-time 80x24.
                    terminal.initializeEmulator(42, 19, 10, 20)
                }
                service.registerListener(listener)
                service.attachSession(sessionId)
                fun screen(): String {
                    var result = ""
                    instrumentation.runOnMainSync { result = terminal.emulator.screen.transcriptText }
                    return result
                }
                await("shell prompt") { screen().isNotBlank() }
                if (runtime == "NATIVE") {
                    instrumentation.runOnMainSync {
                        terminal.write("test \"\$(pwd -P)\" = \"\$(cd \"\$HOME\" && pwd -P)\" && echo NATIVE_HOME_OK\r")
                    }
                    await("Native initial cwd must be HOME") { "NATIVE_HOME_OK" in screen().lines() }
                }
                instrumentation.runOnMainSync { terminal.write("stty size\r") }
                await("PTY must match 19 rows and 42 columns: ${screen()}") { "19 42" in screen() }

                for (paste in listOf(false, true)) {
                    val payload = (if (paste) "abcdefghij" else "0123456789").repeat(10)
                    val command = "echo $payload"
                    instrumentation.runOnMainSync {
                        if (paste) terminal.emulator.paste(command)
                        else command.forEach { terminal.write(it.toString()) }
                    }
                    instrumentation.waitForIdleSync()
                    // Cross a visual wrap boundary, backspace, delete forward, insert.
                    instrumentation.runOnMainSync {
                        terminal.write("\u001b[D".repeat(60) + "\u007f\u001b[3~Z")
                    }
                    val expected = payload.take(39) + "Z" + payload.drop(41)
                    if (runtime == "ROOTLESS") {
                        // Bash redraw must be correct before Enter, not just its internal input.
                        // Native mksh uses a horizontally scrolling edit window instead.
                        await("wrapped redraw") {
                            var visible = ""
                            instrumentation.runOnMainSync {
                                visible = terminal.emulator.screen.getSelectedText(0, 0, 42, 18)
                            }
                            "echo $expected" in visible.replace("\n", "")
                        }
                    }
                    instrumentation.runOnMainSync { terminal.write("\r") }
                    await("edited ${if (paste) "paste" else "typed input"}: ${screen()}") {
                        expected in screen().replace("\n", "")
                    }
                }
            } finally {
                service.unregisterListener(listener)
                backend.close()
                service.terminateSession(sessionId)
            }
        } finally { context.unbindService(connection) }
    }

    private fun ids(json: String): Set<String> = JSONArray(json).let { array ->
        (0 until array.length()).map { array.getJSONObject(it).getString("id") }.toSet()
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        throw AssertionError(description)
    }
}
